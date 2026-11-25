package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal pure-Java HTTP/1.1 + RFC 6455 WebSocket upgrade handler multiplexed onto
 * {@link RemoteStreamServer}'s TLS and ADB LocalSocket listeners via first-4-byte sniffing.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code OPTIONS *} — CORS + Chrome Private Network Access ({@code Access-Control-Allow-Private-Network: true})</li>
 *   <li>{@code GET /health} — lightweight JSON liveness/metadata probe for GitHub Pages</li>
 *   <li>{@code GET /trust}, {@code GET /} — 1.5 KiB self-signed TLS trust confirmation page returning to GitHub Pages</li>
 *   <li>{@code GET /ws} — strict-Origin-checked RFC 6455 WebSocket upgrade returning {@link OpalCastFrameTransport}</li>
 * </ul>
 */
public final class WebSocketHttpHandler {
    public static final String DEFAULT_WEB_CAST_URL = "https://lengsukq.github.io/OpalDesk/cast/";
    private static final String ALLOWED_GH_HOST = "lengsukq.github.io";
    private static final String ALLOWED_GH_PATH_PREFIX = "/OpalDesk/";
    private static final int MAX_HEADER_BLOCK_BYTES = 16 * 1024;
    private static final int MAX_HEADER_COUNT = 64;

    private WebSocketHttpHandler() {}

    public static final class ServerMeta {
        public final String deviceName;
        public final String certShortFp;
        public final int listenPort;
        public final boolean tls;

        public ServerMeta(String deviceName, String certShortFp, int listenPort, boolean tls) {
            this.deviceName = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "Android";
            this.certShortFp = (certShortFp != null) ? certShortFp : "";
            this.listenPort = listenPort;
            this.tls = tls;
        }
    }

    /**
     * Checks whether the first 4 peeked bytes start an HTTP/1.1 request line rather than OpalCast {@code OPD1}.
     */
    public static boolean isHttpRequestPrefix(byte[] first4) {
        if (first4 == null || first4.length < 4) return false;
        String s = new String(first4, 0, 4, StandardCharsets.US_ASCII);
        return "GET ".equals(s) || "OPTI".equals(s) || "HEAD".equals(s) || "POST".equals(s);
    }

    /**
     * Strict Origin allowlist to prevent Cross-Site WebSocket Hijacking (CSWSH) from arbitrary web origins.
     * Allows only official GitHub Pages ({@code https://lengsukq.github.io}), Tauri desktop origins
     * ({@code http(s)://tauri.localhost}, {@code tauri://localhost}), and loopback dev origins.
     */
    public static boolean isAllowedOrigin(String rawOrigin) {
        if (rawOrigin == null) return false;
        String trimmed = rawOrigin.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return false;
        try {
            URI uri = URI.create(trimmed);
            String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.US) : "";
            String host = (uri.getHost() != null) ? uri.getHost().toLowerCase(Locale.US) : "";
            if (host.isEmpty() || uri.getRawUserInfo() != null) return false;
            if (ALLOWED_GH_HOST.equals(host)) {
                return "https".equals(scheme) && (uri.getPort() == -1 || uri.getPort() == 443);
            }
            if ("tauri.localhost".equals(host)) {
                return "http".equals(scheme) || "https".equals(scheme);
            }
            if ("tauri".equals(scheme) && "localhost".equals(host)) {
                return true;
            }
            if ("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)) {
                return "http".equals(scheme) || "https".equals(scheme);
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates the {@code return=} redirect target on {@code /trust} against Open Redirect abuse.
     */
    public static String sanitizeReturnUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return DEFAULT_WEB_CAST_URL;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.US) : "";
            String host = (uri.getHost() != null) ? uri.getHost().toLowerCase(Locale.US) : "";
            String path = (uri.getRawPath() != null) ? uri.getRawPath() : "";
            if (uri.getRawUserInfo() != null || host.isEmpty()) {
                return DEFAULT_WEB_CAST_URL;
            }
            if (ALLOWED_GH_HOST.equals(host) && "https".equals(scheme)
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && path.startsWith(ALLOWED_GH_PATH_PREFIX)) {
                return uri.toASCIIString();
            }
            if (("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host))
                    && ("http".equals(scheme) || "https".equals(scheme))) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to safe default URL.
        }
        return DEFAULT_WEB_CAST_URL;
    }

    /**
     * Handles one HTTP/1.1 request on {@code (in, out)}.
     *
     * @return an upgraded {@link OpalCastFrameTransport} for {@code /ws}, or {@code null} after
     *         sending a complete HTTP response (caller should then close the socket).
     */
    public static OpalCastFrameTransport handle(
            InputStream in,
            OutputStream out,
            ServerMeta meta) throws IOException {
        HttpRequest req = readHttpRequest(in);
        if (req == null) {
            return null;
        }

        String origin = req.headers.get("origin");
        String allowedCorsOrigin = isAllowedOrigin(origin) ? origin.trim() : null;

        if ("OPTIONS".equals(req.method)) {
            writeSimpleResponse(out, "204 No Content", null, OpalCastProtocol.EMPTY_BYTES, allowedCorsOrigin);
            return null;
        }
        if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
            writeSimpleResponse(out, "405 Method Not Allowed", "text/plain; charset=utf-8",
                    "Method Not Allowed".getBytes(StandardCharsets.UTF_8), allowedCorsOrigin);
            return null;
        }

        if ("/health".equals(req.path)) {
            String json = new SimpleJson.ObjectBuilder()
                    .put("ok", true)
                    .put("role", "cast")
                    .put("deviceName", meta.deviceName)
                    .put("protocolVersion", OpalCastProtocol.PROTOCOL_VERSION)
                    .put("certShortFp", meta.certShortFp)
                    .put("tls", meta.tls)
                    .put("wsPath", "/ws")
                    .build();
            byte[] body = "HEAD".equals(req.method) ? OpalCastProtocol.EMPTY_BYTES : json.getBytes(StandardCharsets.UTF_8);
            writeSimpleResponse(out, "200 OK", "application/json; charset=utf-8", body, allowedCorsOrigin);
            return null;
        }

        if ("/".equals(req.path) || "/trust".equals(req.path)) {
            String returnUrl = sanitizeReturnUrl(req.query.get("return"));
            String html = buildTrustHtml(meta, returnUrl);
            byte[] body = "HEAD".equals(req.method) ? OpalCastProtocol.EMPTY_BYTES : html.getBytes(StandardCharsets.UTF_8);
            writeSimpleResponse(out, "200 OK", "text/html; charset=utf-8", body, allowedCorsOrigin);
            return null;
        }

        if ("/ws".equals(req.path)) {
            return handleWebSocketUpgrade(req, in, out, allowedCorsOrigin);
        }

        writeSimpleResponse(out, "404 Not Found", "text/plain; charset=utf-8",
                "Not Found".getBytes(StandardCharsets.UTF_8), allowedCorsOrigin);
        return null;
    }

    private static OpalCastFrameTransport handleWebSocketUpgrade(
            HttpRequest req,
            InputStream in,
            OutputStream out,
            String allowedCorsOrigin) throws IOException {
        String origin = req.headers.get("origin");
        if (origin != null && !origin.trim().isEmpty() && !isAllowedOrigin(origin)) {
            System.err.println("OPALDESK_WS_REJECT_ORIGIN origin=" + origin);
            writeSimpleResponse(out, "403 Forbidden", "text/plain; charset=utf-8",
                    "Origin not allowed".getBytes(StandardCharsets.UTF_8), null);
            return null;
        }
        String upgrade = req.headers.get("upgrade");
        String connection = req.headers.get("connection");
        String wsKey = req.headers.get("sec-websocket-key");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())
                || connection == null || !connection.toLowerCase(Locale.US).contains("upgrade")
                || wsKey == null || wsKey.trim().isEmpty()) {
            writeSimpleResponse(out, "400 Bad Request", "text/plain; charset=utf-8",
                    "Invalid WebSocket upgrade headers".getBytes(StandardCharsets.UTF_8), allowedCorsOrigin);
            return null;
        }

        String accept = WebSocketCodec.computeAcceptKey(wsKey);
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 101 Switching Protocols\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return OpalCastFrameTransport.webSocket(in, out);
    }

    private static void writeSimpleResponse(
            OutputStream out,
            String status,
            String contentType,
            byte[] body,
            String allowedCorsOrigin) throws IOException {
        StringBuilder sb = new StringBuilder(320);
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        if (allowedCorsOrigin != null) {
            sb.append("Access-Control-Allow-Origin: ").append(allowedCorsOrigin).append("\r\n");
            sb.append("Access-Control-Allow-Private-Network: true\r\n");
            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Content-Type\r\n");
            sb.append("Vary: Origin\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static String buildTrustHtml(ServerMeta meta, String safeReturnUrl) {
        String escapedDev = escapeHtml(meta.deviceName);
        String escapedFp = escapeHtml(meta.certShortFp);
        String escapedHref = escapeHtml(safeReturnUrl);
        String jsReturn = quoteJsString(safeReturnUrl);
        String targetOrigin = extractOrigin(safeReturnUrl);
        String jsOrigin = quoteJsString(targetOrigin);

        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>OpalDesk 证书已信任</title>"
                + "<style>body{margin:0;min-height:100vh;display:grid;place-items:center;"
                + "font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#0b101b;color:#f4f7ff}"
                + ".card{max-width:420px;margin:20px;padding:28px;border-radius:22px;"
                + "background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.16);text-align:center}"
                + ".ok{font-size:36px;margin-bottom:8px}h1{margin:0 0 8px;font-size:20px}"
                + "p{margin:6px 0;color:#b8c4dc;font-size:13.5px;line-height:1.55}"
                + ".fp{font-family:ui-monospace,monospace;font-size:12px;color:#7dd3fc}"
                + "a{display:inline-block;margin-top:16px;padding:10px 20px;border-radius:999px;"
                + "background:#0071e3;color:#fff;text-decoration:none;font-weight:600;font-size:14px}</style>"
                + "</head><body><div class=\"card\"><div class=\"ok\">✓</div>"
                + "<h1>已信任 " + escapedDev + " 的本地加密证书</h1>"
                + "<p>正在返回 OpalDesk Web 投屏端…</p>"
                + (escapedFp.isEmpty() ? "" : "<p class=\"fp\">指纹: " + escapedFp + "</p>")
                + "<a href=\"" + escapedHref + "\">立即返回 Web 投屏端</a></div>"
                + "<script>(function(){try{if(window.opener){window.opener.postMessage("
                + "{type:'opaldesk-tls-trusted',role:'cast',port:" + meta.listenPort + "}," + jsOrigin + ");"
                + "setTimeout(function(){window.close();},450);return;}}catch(_){}"
                + "setTimeout(function(){location.replace(" + jsReturn + ");},550);})();</script>"
                + "</body></html>";
    }

    private static String quoteJsString(String s) {
        StringBuilder sb = new StringBuilder((s != null ? s.length() : 0) + 8);
        SimpleJson.appendEscapedString(sb, s);
        return sb.toString()
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("</", "\\u003c\\/");
    }

    private static String extractOrigin(String url) {
        try {
            URI u = URI.create(url);
            int port = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (IllegalArgumentException e) {
            return "https://" + ALLOWED_GH_HOST;
        }
    }

    private static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c); break;
            }
        }
        return out.toString();
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;

        HttpRequest(String method, String path, Map<String, String> query, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
        }
    }

    private static HttpRequest readHttpRequest(InputStream in) throws IOException {
        byte[] raw = readHeaderBlock(in);
        if (raw == null || raw.length == 0) return null;
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n");
        if (lines.length == 0) return null;

        String[] reqLine = lines[0].split(" ");
        if (reqLine.length < 2) {
            throw new OpalCastProtocol.ProtocolException("Malformed HTTP request line");
        }
        String method = reqLine[0].toUpperCase(Locale.US);
        String rawTarget = reqLine[1];
        String path = rawTarget;
        Map<String, String> query = new LinkedHashMap<>();
        int qIdx = rawTarget.indexOf('?');
        if (qIdx >= 0) {
            path = rawTarget.substring(0, qIdx);
            parseQuery(rawTarget.substring(qIdx + 1), query);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        int headerCount = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            if (++headerCount > MAX_HEADER_COUNT) {
                throw new OpalCastProtocol.ProtocolException("Too many HTTP headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String val = line.substring(colon + 1).trim();
            headers.put(key, val);
        }
        return new HttpRequest(method, path, query, headers);
    }

    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0; // tracks \r\n\r\n
        while (buf.size() < MAX_HEADER_BLOCK_BYTES) {
            int b = in.read();
            if (b < 0) {
                return (buf.size() == 0) ? null : buf.toByteArray();
            }
            buf.write(b);
            if ((state == 0 && b == '\r') || (state == 2 && b == '\r')) {
                state++;
            } else if ((state == 1 && b == '\n') || (state == 3 && b == '\n')) {
                state++;
                if (state == 4) {
                    return buf.toByteArray();
                }
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        throw new OpalCastProtocol.ProtocolException("HTTP header exceeds 16 KiB limit");
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        if (qs == null || qs.isEmpty()) return;
        String[] pairs = qs.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = (eq >= 0) ? pair.substring(0, eq) : pair;
            String rawVal = (eq >= 0) ? pair.substring(eq + 1) : "";
            try {
                String k = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name());
                String v = URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name());
                out.put(k, v);
            } catch (Exception ignored) {
                // Ignore malformed percent-encoding in optional query params.
            }
        }
    }
}
