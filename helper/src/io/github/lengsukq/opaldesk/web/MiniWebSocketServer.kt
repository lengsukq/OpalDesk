package io.github.lengsukq.opaldesk.web

import android.util.Log
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Lightweight zero-dependency HTTPS + RFC 6455 JSON WebSocket server for [WebActivationService].
 * Serves:
 * - `OPTIONS *` with CORS + Chrome Private Network Access (`Access-Control-Allow-Private-Network: true`)
 * - `GET /health` liveness JSON probe
 * - `GET /trust` / `GET /` self-signed TLS trust confirmation page returning to GitHub Pages
 * - `GET /activate` origin-checked WebSocket upgrade for live LAN activation control
 */
class MiniWebSocketServer(
    private val identity: LocalTlsCert.Identity,
    private val deviceName: String,
    private val lanIpProvider: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        fun onClientConnected(client: WsClient)
        fun onClientTextMessage(client: WsClient, text: String)
        fun onClientDisconnected(client: WsClient)
    }

    class WsClient(
        val peerIp: String,
        private val socket: SSLSocket,
        private val out: OutputStream,
        private val writeExec: ExecutorService? = null,
    ) {
        private val writeLock = Any()
        private val closed = AtomicBoolean(false)

        @Volatile
        var authenticated: Boolean = false

        fun isClosed(): Boolean = closed.get()

        fun sendText(text: String) {
            if (closed.get()) return
            val exec = writeExec
            if (exec != null && !exec.isShutdown) {
                exec.execute { sendTextBlocking(text) }
            } else {
                sendTextBlocking(text)
            }
        }

        private fun sendTextBlocking(text: String) {
            if (closed.get()) return
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            try {
                synchronized(writeLock) {
                    writeServerFrame(out, OPCODE_TEXT, bytes)
                    out.flush()
                }
            } catch (_: IOException) {
                close()
            }
        }

        internal fun sendControl(opcode: Int, payload: ByteArray) {
            if (closed.get()) return
            try {
                synchronized(writeLock) {
                    writeServerFrame(out, opcode, payload.copyOf(minOf(payload.size, 125)))
                    out.flush()
                }
            } catch (_: IOException) {
                close()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "OpalWebActSrv"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OPCODE_CONT = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_WS_TEXT_BYTES = 64 * 1024

        fun computeAcceptKey(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((clientKey.trim() + WS_GUID).toByteArray(StandardCharsets.US_ASCII))
            return Base64.getEncoder().encodeToString(sha1)
        }

        private fun writeServerFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
            out.write(0x80 or (opcode and 0x0F))
            val len = payload.size
            when {
                len <= 125 -> out.write(len)
                len <= 65_535 -> {
                    out.write(126)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                }
                else -> {
                    out.write(127)
                    val l64 = len.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        out.write(((l64 ushr shift) and 0xFFL).toInt())
                    }
                }
            }
            if (len > 0) out.write(payload)
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArraySet<WsClient>()
    private val writeExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalWebActWsWrite").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = 0
        private set

    fun start(preferredPorts: IntRange = WebActivationPolicy.DEFAULT_ACTIVATION_PORT..(WebActivationPolicy.DEFAULT_ACTIVATION_PORT + 6)): Int {
        if (!running.compareAndSet(false, true)) return boundPort
        val sslCtx = LocalTlsCert.createServerSslContext(identity)
        var lastErr: Throwable? = null
        for (port in preferredPorts) {
            try {
                val ss = sslCtx.serverSocketFactory.createServerSocket() as SSLServerSocket
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port), 8)
                val secure = ss.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
                if (secure.isNotEmpty()) ss.enabledProtocols = secure.toTypedArray()
                serverSocket = ss
                boundPort = ss.localPort
                acceptThread = Thread({ acceptLoop(ss) }, "OpalWebActAccept").apply {
                    isDaemon = true
                    start()
                }
                return boundPort
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        running.set(false)
        throw IOException("无法绑定局域网激活端口: ${lastErr?.message}", lastErr)
    }

    fun broadcastAuthenticated(jsonText: String) {
        if (writeExec.isShutdown) return
        writeExec.execute {
            for (c in clients) {
                if (!c.isClosed() && c.authenticated) {
                    c.sendText(jsonText)
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        for (c in clients) {
            c.close()
        }
        clients.clear()
        runCatching { writeExec.shutdownNow() }
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (running.get() && !ss.isClosed) {
            try {
                val sock = ss.accept() as? SSLSocket ?: continue
                Thread({ handleConnection(sock) }, "OpalWebActClient").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                if (running.get() && !ss.isClosed) {
                    OpalLogStore.w(TAG, "Accept error", t)
                }
            }
        }
    }

    private fun handleConnection(sock: SSLSocket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 10_000
            val secure = sock.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
            if (secure.isNotEmpty()) sock.enabledProtocols = secure.toTypedArray()
            sock.startHandshake()

            val peerIp = sock.inetAddress?.hostAddress ?: "unknown"
            val input = BufferedInputStream(sock.inputStream, 32 * 1024)
            val output = BufferedOutputStream(sock.outputStream, 32 * 1024)

            val req = readHttpRequest(input) ?: run { sock.close(); return }
            val origin = req.headers["origin"]
            val allowedOrigin = if (WebActivationPolicy.isAllowedOrigin(origin)) origin!!.trim() else null

            if (req.method == "OPTIONS") {
                writeHttp(output, "204 No Content", null, ByteArray(0), allowedOrigin)
                sock.close()
                return
            }
            if (req.method != "GET" && req.method != "HEAD") {
                writeHttp(output, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed".toByteArray(), allowedOrigin)
                sock.close()
                return
            }

            when (req.path) {
                "/health" -> {
                    val json = """{"ok":true,"role":"activate","deviceName":${jsonStr(deviceName)},"lanIp":${jsonStr(lanIpProvider())},"port":$boundPort,"shortCertFp":${jsonStr(identity.shortFingerprint)},"wsPath":"/activate"}"""
                    val body = if (req.method == "HEAD") ByteArray(0) else json.toByteArray(StandardCharsets.UTF_8)
                    writeHttp(output, "200 OK", "application/json; charset=utf-8", body, allowedOrigin)
                    sock.close()
                }
                "/", "/trust" -> {
                    val safeRet = WebActivationPolicy.sanitizeReturnUrl(req.query["return"])
                    val html = buildTrustHtml(safeRet)
                    val body = if (req.method == "HEAD") ByteArray(0) else html.toByteArray(StandardCharsets.UTF_8)
                    writeHttp(output, "200 OK", "text/html; charset=utf-8", body, allowedOrigin)
                    sock.close()
                }
                "/activate" -> handleWebSocket(sock, peerIp, req, input, output, allowedOrigin)
                else -> {
                    writeHttp(output, "404 Not Found", "text/plain; charset=utf-8", "Not Found".toByteArray(), allowedOrigin)
                    sock.close()
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Client connection ended: ${t.message}")
            runCatching { sock.close() }
        }
    }

    private fun handleWebSocket(
        sock: SSLSocket,
        peerIp: String,
        req: HttpReq,
        input: InputStream,
        output: OutputStream,
        allowedOrigin: String?,
    ) {
        val origin = req.headers["origin"]
        if (!origin.isNullOrBlank() && !WebActivationPolicy.isAllowedOrigin(origin)) {
            writeHttp(output, "403 Forbidden", "text/plain; charset=utf-8", "Origin not allowed".toByteArray(), null)
            sock.close()
            return
        }
        val upgrade = req.headers["upgrade"]
        val connection = req.headers["connection"]
        val wsKey = req.headers["sec-websocket-key"]
        if (!"websocket".equals(upgrade?.trim(), ignoreCase = true)
            || connection?.lowercase(Locale.US)?.contains("upgrade") != true
            || wsKey.isNullOrBlank()
        ) {
            writeHttp(output, "400 Bad Request", "text/plain; charset=utf-8", "Invalid WebSocket headers".toByteArray(), allowedOrigin)
            sock.close()
            return
        }

        val accept = computeAcceptKey(wsKey)
        val upgradeResp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(upgradeResp.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        sock.soTimeout = 0

        val client = WsClient(peerIp, sock, output, writeExec)
        clients.add(client)
        listener.onClientConnected(client)
        try {
            while (running.get() && !client.isClosed()) {
                val text = readClientTextMessage(input, client) ?: break
                listener.onClientTextMessage(client, text)
            }
        } finally {
            clients.remove(client)
            client.close()
            listener.onClientDisconnected(client)
        }
    }

    private fun readClientTextMessage(input: InputStream, client: WsClient): String? {
        var fragBuf: ByteArrayOutputStream? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = readByte(input)
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            if (!masked) throw IOException("Client WebSocket frame must be masked")
            val len7 = b1 and 0x7F
            val frameLen = when (len7) {
                126 -> ((readByte(input).toLong() shl 8) or readByte(input).toLong())
                127 -> {
                    var l = 0L
                    repeat(8) { l = (l shl 8) or readByte(input).toLong() }
                    l
                }
                else -> len7.toLong()
            }
            val isControl = (opcode and 0x08) != 0
            if (isControl) {
                if (!fin || frameLen > 125L) throw IOException("Invalid WebSocket control frame")
                val ctrl = readMasked(input, frameLen.toInt())
                when (opcode) {
                    OPCODE_CLOSE -> {
                        client.sendControl(OPCODE_CLOSE, if (ctrl.size >= 2) ctrl.copyOf(2) else ByteArray(0))
                        return null
                    }
                    OPCODE_PING -> client.sendControl(OPCODE_PONG, ctrl)
                }
                continue
            }
            if (frameLen > MAX_WS_TEXT_BYTES) throw IOException("Activation message exceeds 64 KiB limit")
            val chunk = readMasked(input, frameLen.toInt())
            if (opcode == OPCODE_CONT) {
                val buf = fragBuf ?: throw IOException("Unexpected continuation frame")
                if (buf.size() + chunk.size > MAX_WS_TEXT_BYTES) throw IOException("Fragmented message too large")
                buf.write(chunk)
                if (fin) return String(buf.toByteArray(), StandardCharsets.UTF_8)
                continue
            }
            if (opcode != OPCODE_TEXT) throw IOException("Expected text frame on /activate")
            if (fin) return String(chunk, StandardCharsets.UTF_8)
            fragBuf = ByteArrayOutputStream(maxOf(chunk.size, 256)).apply { write(chunk) }
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Unexpected EOF")
        return b
    }

    private fun readMasked(input: InputStream, len: Int): ByteArray {
        val mask = ByteArray(4)
        readFully(input, mask)
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        readFully(input, out)
        for (i in 0 until len) {
            out[i] = (out[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return out
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var pos = 0
        while (pos < buf.size) {
            val n = input.read(buf, pos, buf.size - pos)
            if (n < 0) throw EOFException("Unexpected EOF")
            pos += n
        }
    }

    private data class HttpReq(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    private fun readHttpRequest(input: InputStream): HttpReq? {
        val buf = ByteArrayOutputStream(512)
        var state = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else break
            buf.write(b)
            state = when {
                (state == 0 || state == 2) && b == '\r'.code -> state + 1
                (state == 1 || state == 3) && b == '\n'.code -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val lines = String(buf.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
        if (lines.isEmpty()) return null
        val reqParts = lines[0].split(" ")
        if (reqParts.size < 2) return null
        val method = reqParts[0].uppercase(Locale.US)
        val rawTarget = reqParts[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val query = LinkedHashMap<String, String>()
        if (qIdx >= 0) {
            rawTarget.substring(qIdx + 1).split('&').forEach { pair ->
                if (pair.isNotEmpty()) {
                    val eq = pair.indexOf('=')
                    val k = runCatching { URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, "UTF-8") }.getOrNull() ?: return@forEach
                    val v = runCatching { URLDecoder.decode(if (eq >= 0) pair.substring(eq + 1) else "", "UTF-8") }.getOrDefault("")
                    query[k] = v
                }
            }
        }
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
            }
        }
        return HttpReq(method, path, query, headers)
    }

    private fun writeHttp(
        out: OutputStream,
        status: String,
        contentType: String?,
        body: ByteArray,
        allowedOrigin: String?,
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        if (allowedOrigin != null) {
            sb.append("Access-Control-Allow-Origin: ").append(allowedOrigin).append("\r\n")
            sb.append("Access-Control-Allow-Private-Network: true\r\n")
            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            sb.append("Access-Control-Allow-Headers: Content-Type\r\n")
            sb.append("Vary: Origin\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun buildTrustHtml(safeReturnUrl: String): String {
        val dev = escapeHtml(deviceName)
        val fp = escapeHtml(identity.shortFingerprint)
        val href = escapeHtml(safeReturnUrl)
        val jsRet = jsStr(safeReturnUrl)
        val origin = runCatching {
            val u = URI.create(safeReturnUrl)
            "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
        }.getOrDefault("https://lengsukq.github.io")
        val jsOrigin = jsStr(origin)
        return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OpalDesk 局域网激活证书已信任</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#0b101b;color:#f4f7ff}.card{max-width:420px;margin:20px;padding:28px;border-radius:22px;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.16);text-align:center}.ok{font-size:36px;margin-bottom:8px}h1{margin:0 0 8px;font-size:20px}p{margin:6px 0;color:#b8c4dc;font-size:13.5px;line-height:1.55}.fp{font-family:ui-monospace,monospace;font-size:12px;color:#7dd3fc}a{display:inline-block;margin-top:16px;padding:10px 20px;border-radius:999px;background:#0071e3;color:#fff;text-decoration:none;font-weight:600;font-size:14px}</style></head><body><div class="card"><div class="ok">✓</div><h1>已信任 $dev 的激活服务证书</h1><p>正在返回 OpalDesk Web 激活向导…</p><p class="fp">指纹: $fp</p><a href="$href">立即返回 Web 激活页</a></div><script>(function(){try{if(window.opener){window.opener.postMessage({type:'opaldesk-tls-trusted',role:'activate',port:$boundPort},$jsOrigin);setTimeout(function(){window.close();},450);return;}}catch(_){}setTimeout(function(){location.replace($jsRet);},550);})();</script></body></html>"""
    }

    private fun escapeHtml(s: String): String = buildString(s.length + 12) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun jsStr(s: String): String =
        jsonStr(s).replace("<", "\\u003c").replace(">", "\\u003e").replace("</", "\\u003c\\/")

    private fun jsonStr(s: String): String = buildString(s.length + 8) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append(String.format(Locale.US, "\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
