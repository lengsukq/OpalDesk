package io.github.lengsukq.opaldesk.daemon.stream;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebSocketHttpHandlerTest {

    private static final WebSocketHttpHandler.ServerMeta TEST_META =
            new WebSocketHttpHandler.ServerMeta("Xiaomi 14 Pro", "aabbccddeeff", 19878, true);

    @Test
    public void testPrefixSniffing() {
        assertTrue(WebSocketHttpHandler.isHttpRequestPrefix("GET ".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(WebSocketHttpHandler.isHttpRequestPrefix("OPTI".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(WebSocketHttpHandler.isHttpRequestPrefix("HEAD".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(WebSocketHttpHandler.isHttpRequestPrefix("OPD1".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(WebSocketHttpHandler.isHttpRequestPrefix(new byte[]{0, 0, 0, 1}));
    }

    @Test
    public void testOriginAllowlistBlocksSpoofedDomains() {
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("https://lengsukq.github.io"));
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("http://localhost:8080"));
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("http://127.0.0.1:5173"));
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("https://localhost"));
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("http://tauri.localhost"));
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("https://tauri.localhost"));
        assertTrue(WebSocketHttpHandler.isAllowedOrigin("tauri://localhost"));

        assertFalse(WebSocketHttpHandler.isAllowedOrigin("http://lengsukq.github.io"));
        assertFalse(WebSocketHttpHandler.isAllowedOrigin("https://lengsukq.github.io.evil.com"));
        assertFalse(WebSocketHttpHandler.isAllowedOrigin("https://evil.example"));
        assertFalse(WebSocketHttpHandler.isAllowedOrigin("https://tauri.localhost.evil.com"));
        assertFalse(WebSocketHttpHandler.isAllowedOrigin("null"));
        assertFalse(WebSocketHttpHandler.isAllowedOrigin(""));
    }

    @Test
    public void testReturnUrlSanitizationBlocksOpenRedirect() {
        assertEquals(
                "https://lengsukq.github.io/OpalDesk/cast/?host=192.168.1.9",
                WebSocketHttpHandler.sanitizeReturnUrl("https://lengsukq.github.io/OpalDesk/cast/?host=192.168.1.9"));
        assertEquals(
                "http://127.0.0.1:8080/cast/",
                WebSocketHttpHandler.sanitizeReturnUrl("http://127.0.0.1:8080/cast/"));
        assertEquals(
                WebSocketHttpHandler.DEFAULT_WEB_CAST_URL,
                WebSocketHttpHandler.sanitizeReturnUrl("https://evil.example/phish"));
        assertEquals(
                WebSocketHttpHandler.DEFAULT_WEB_CAST_URL,
                WebSocketHttpHandler.sanitizeReturnUrl("javascript:alert(1)"));
    }

    @Test
    public void testOptionsPreflightIncludesPnaHeaders() throws Exception {
        String req = "OPTIONS /health HTTP/1.1\r\n"
                + "Host: 192.168.1.8:19878\r\n"
                + "Origin: https://lengsukq.github.io\r\n"
                + "Access-Control-Request-Private-Network: true\r\n\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpalCastFrameTransport ws = WebSocketHttpHandler.handle(
                new ByteArrayInputStream(req.getBytes(StandardCharsets.US_ASCII)), out, TEST_META);
        assertNull(ws);
        String resp = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(resp.startsWith("HTTP/1.1 204 No Content\r\n"));
        assertTrue(resp.contains("Access-Control-Allow-Origin: https://lengsukq.github.io\r\n"));
        assertTrue(resp.contains("Access-Control-Allow-Private-Network: true\r\n"));
    }

    @Test
    public void testHealthAndTrustRoutes() throws Exception {
        String healthReq = "GET /health HTTP/1.1\r\n"
                + "Host: 192.168.1.8:19878\r\n"
                + "Origin: https://lengsukq.github.io\r\n\r\n";
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        assertNull(WebSocketHttpHandler.handle(
                new ByteArrayInputStream(healthReq.getBytes(StandardCharsets.US_ASCII)), out1, TEST_META));
        String r1 = out1.toString(StandardCharsets.UTF_8.name());
        assertTrue(r1.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(r1.contains("\"role\":\"cast\""));
        assertTrue(r1.contains("\"deviceName\":\"Xiaomi 14 Pro\""));

        String trustReq = "GET /trust?return=https%3A%2F%2Fevil.example%2Fbad HTTP/1.1\r\n"
                + "Host: 192.168.1.8:19878\r\n\r\n";
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        assertNull(WebSocketHttpHandler.handle(
                new ByteArrayInputStream(trustReq.getBytes(StandardCharsets.US_ASCII)), out2, TEST_META));
        String r2 = out2.toString(StandardCharsets.UTF_8.name());
        assertTrue(r2.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(r2.contains(WebSocketHttpHandler.DEFAULT_WEB_CAST_URL));
        assertFalse(r2.contains("evil.example"));
    }

    @Test
    public void testWebSocketUpgradeEnforcesOriginAndReturnsTransport() throws Exception {
        String badOriginReq = "GET /ws HTTP/1.1\r\n"
                + "Host: 192.168.1.8:19878\r\n"
                + "Origin: https://evil.example\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
        ByteArrayOutputStream badOut = new ByteArrayOutputStream();
        assertNull(WebSocketHttpHandler.handle(
                new ByteArrayInputStream(badOriginReq.getBytes(StandardCharsets.US_ASCII)), badOut, TEST_META));
        assertTrue(badOut.toString(StandardCharsets.UTF_8.name()).startsWith("HTTP/1.1 403 Forbidden"));

        String okReq = "GET /ws HTTP/1.1\r\n"
                + "Host: 192.168.1.8:19878\r\n"
                + "Origin: https://lengsukq.github.io\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
        ByteArrayOutputStream okOut = new ByteArrayOutputStream();
        OpalCastFrameTransport transport = WebSocketHttpHandler.handle(
                new ByteArrayInputStream(okReq.getBytes(StandardCharsets.US_ASCII)), okOut, TEST_META);
        assertNotNull(transport);
        String resp = okOut.toString(StandardCharsets.UTF_8.name());
        assertTrue(resp.startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
        assertTrue(resp.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"));
    }
}
