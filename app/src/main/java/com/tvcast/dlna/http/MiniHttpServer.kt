package com.tvcast.dlna.http

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread
import kotlin.random.Random

class HttpRequest(
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray = ByteArray(0),
) {
    private val headerMap: Map<String, String> =
        headers.associate { (k, v) -> k.lowercase() to v.trim() }

    fun header(name: String): String? = headerMap[name.lowercase()]

    val bodyText: String get() = String(body, Charsets.UTF_8)
}

class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
) {
    companion object {
        fun xml(status: Int, reason: String, xml: String) =
            HttpResponse(status, reason, "text/xml; charset=\"utf-8\"", body = xml.toByteArray(Charsets.UTF_8))

        fun text(status: Int, reason: String) = HttpResponse(status, reason, "text/plain")
    }
}

interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse
}

/**
 * 自研 mini HTTP server（spec §2：NanoHTTPD 对 GENA 非标准动词 SUBSCRIBE/UNSUBSCRIBE
 * 直接回 400，故只解析 request line + headers + body，方法名不设白名单）。
 * 端口策略：尝试 preferredPort（如 80，原生 ROM 必然 EACCES）→ 49152+ 高位随机 → 0 兜底。
 */
class MiniHttpServer(private val handler: HttpHandler) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var executor: ExecutorService? = null

    val port: Int get() = serverSocket?.localPort ?: -1
    val isRunning: Boolean get() = serverSocket != null && serverSocket?.isClosed == false

    /** 返回实际绑定端口 */
    fun start(preferredPort: Int = 0): Int {
        check(serverSocket == null) { "MiniHttpServer already started" }
        val socket = bind(preferredPort)
            ?: bindHighRandom()
            ?: bind(0)
            ?: throw IllegalStateException("MiniHttpServer: no port available")
        serverSocket = socket
        executor = Executors.newCachedThreadPool { r -> Thread(r, "tvcast-http") }
        thread(name = "tvcast-http-accept", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor?.shutdownNow()
        executor = null
        serverSocket = null
    }

    private fun bind(port: Int): ServerSocket? = try {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 16)
        s
    } catch (e: Exception) {
        null
    }

    private fun bindHighRandom(): ServerSocket? {
        repeat(10) {
            bind(49152 + Random.nextInt(16000))?.let { return it }
        }
        return null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            try {
                executor?.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val request = try {
                parse(input)
            } catch (_: Exception) {
                null
            }
            val response = if (request == null) {
                HttpResponse.text(400, "Bad Request")
            } else {
                try {
                    handler.handle(request)
                } catch (t: Throwable) {
                    HttpResponse.text(500, "Internal Server Error")
                }
            }
            write(client.getOutputStream(), response)
        } catch (_: Exception) {
            // 客户端中途断开等，忽略
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parse(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        var path = parts[1]
        // 容错 absolute-form 请求行：GET http://192.168.1.2:80/dd.xml HTTP/1.1
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            path = "/" + path.substringAfter("://").substringAfter('/', "")
        }
        val headers = ArrayList<Pair<String, String>>(16)
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers += line.substring(0, idx).trim() to line.substring(idx + 1)
            if (headers.size > 128) break
        }
        val length = headers
            .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
            ?.second?.trim()?.toIntOrNull() ?: 0
        if (length < 0 || length > 8 * 1024 * 1024) return null
        val body = if (length > 0) ByteArray(length).also { readFully(input, it) } else ByteArray(0)
        return HttpRequest(method, path, headers, body)
    }

    private fun readLine(input: InputStream, limit: Int = 8192): String? {
        val sb = StringBuilder(64)
        while (sb.length < limit) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n == -1) break
            offset += n
        }
    }

    private fun write(out: OutputStream, r: HttpResponse) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason).append("\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        r.contentType?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(r.body)
        out.flush()
    }
}
