package com.tvcast.dlna.cp

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class GenaState(
  val transportState: String? = null,
  val volume: Int? = null,
  val avtUri: String? = null,
)

/**
 * 控制点 GENA 订阅器：
 * - SUBSCRIBE/UNSUBSCRIBE 走原生 Socket（非标准动词，Android HttpURLConnection 拒绝）
 * - 内置迷你 HTTP 服务收 NOTIFY（RECEIVER 侧的 MiniHttpServer 在纯 JVM 模块里可直接复用场景不成立时独立实现，
 *   这里保持零依赖：只解析 NOTIFY 的 LastChange）
 * - 线程模型：notifyLoop 守护线程逐条回调 [onState]
 */
class GenaSubscriber(
  private val eventUrl: String,
  private val onState: (GenaState) -> Unit,
  private val log: (String) -> Unit = {},
) {

  @Volatile private var sid: String? = null
  private val serverSocket = AtomicReference<ServerSocket?>(null)

  /** 绑定本机地址与端口，发订阅；返回是否成功 */
  fun subscribe(timeoutSeconds: Int = 1800): Boolean {
    val socket = try {
      ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
      }
    } catch (e: Exception) {
      log("GENA CP bind failed: $e")
      return false
    }
    serverSocket.set(socket)
    val localPort = socket.localPort
    val localIp = localLanIp() ?: "127.0.0.1"
    val callback = "http://$localIp:$localPort/callback"

    val response = rawRequest(
      "SUBSCRIBE", eventUrl,
      mapOf("CALLBACK" to "<$callback>", "NT" to "upnp:event", "TIMEOUT" to "Second-$timeoutSeconds"),
    )
    val newSid = Regex("SID:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(response)?.groupValues?.get(1)
    if (response.lineSequence().firstOrNull()?.contains(" 200 ") != true || newSid == null) {
      log("GENA subscribe failed: ${response.lineSequence().firstOrNull()}")
      closeServer()
      return false
    }
    sid = newSid
    thread(name = "tvcast-cp-gena", isDaemon = true) { notifyLoop(socket) }
    log("GENA subscribed sid=$newSid callback=$callback")
    return true
  }

  fun unsubscribe() {
    val current = sid ?: return
    sid = null
    try {
      rawRequest("UNSUBSCRIBE", eventUrl, mapOf("SID" to current))
    } catch (_: Exception) {
    }
    closeServer()
  }

  fun close() = unsubscribe()

  private fun closeServer() {
    try {
      serverSocket.getAndSet(null)?.close()
    } catch (_: Exception) {
    }
  }

  private fun notifyLoop(socket: ServerSocket) {
    val buffer = ByteArray(64 * 1024)
    while (sid != null && !socket.isClosed) {
      val client = try {
        socket.accept()
      } catch (_: Exception) {
        break
      }
      try {
        client.soTimeout = 5000
        val text = readHttpRequest(client.getInputStream(), buffer)
        if (text != null && text.startsWith("NOTIFY")) {
          val seq = Regex("SEQ:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
          val bodyStart = text.indexOf("\r\n\r\n")
          if (bodyStart > 0) {
            val body = text.substring(bodyStart + 4)
            val state = LastChangeParser.parsePropertyset(body)
            log("GENA notify seq=$seq -> $state")
            onState(state)
          }
        }
        // 200 OK 简单应答后立即断开
        client.getOutputStream().apply {
          write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
          flush()
        }
      } catch (_: Exception) {
      } finally {
        try {
          client.close()
        } catch (_: Exception) {
        }
      }
    }
  }

  private fun readHttpRequest(input: InputStream, buffer: ByteArray): String? = try {
    // NOTIFY 报文远小于 buffer，一次读入即可（接收端 Connection: close 语义）
    val n = input.read(buffer)
    if (n <= 0) null else String(buffer, 0, n, Charsets.UTF_8)
  } catch (_: Exception) {
    null
  }

  private fun rawRequest(method: String, urlText: String, headers: Map<String, String>): String = try {
    // Android HttpURLConnection 对 SUBSCRIBE 等非标准动词抛异常，必须原生 Socket
    val url = URL(urlText)
    val port = if (url.port > 0) url.port else 80
    val path = url.path.ifEmpty { "/" } + (url.query?.let { "?$it" } ?: "")
    Socket().use { socket ->
      socket.connect(InetSocketAddress(url.host, port), 3000)
      socket.soTimeout = 5000
      val request = buildString {
        append("$method $path HTTP/1.1\r\n")
        append("HOST: ${url.host}:$port\r\n")
        headers.forEach { (k, v) -> append("$k: $v\r\n") }
        append("Content-Length: 0\r\n")
        append("Connection: close\r\n\r\n")
      }
      socket.getOutputStream().apply {
        write(request.toByteArray(Charsets.UTF_8))
        flush()
      }
      val buffer = ByteArray(8 * 1024)
      val out = StringBuilder()
      val input = socket.getInputStream()
      while (true) {
        val n = try {
          input.read(buffer)
        } catch (_: Exception) {
          break
        }
        if (n <= 0) break
        out.append(String(buffer, 0, n, Charsets.UTF_8))
        if (out.length > 64 * 1024) break
      }
      out.toString()
    }
  } catch (e: Exception) {
    "HTTP/1.1 000 ${e.message}\r\n\r\n"
  }

  private fun localLanIp(): String? = try {
    NetworkInterface.getNetworkInterfaces().asSequence()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { it.interfaceAddresses.asSequence() }
      .mapNotNull { it.address?.hostAddress }
      .firstOrNull { it.contains('.') && !it.startsWith("127.") }
  } catch (_: Exception) {
    null
  }
}

/** LastChange propertyset 解析：解出嵌套 Event XML 中的 AVT/RCS 字段 */
object LastChangeParser {

  fun parsePropertyset(propertysetXml: String): GenaState {
    var state = GenaState()
    // 第一层：取 LastChange 文本（接收端将 Event XML 整体转义后放入）
    val raw = extractTag(propertysetXml, "LastChange")
    if (raw != null) {
      val lastChange: String = unescape(raw)
      state = state.copy(
        transportState = extractAttr(lastChange, "TransportState"),
        avtUri = extractAttr(lastChange, "AVTransportURI")?.let { unescape(it) },
      )
      val volumeAttr = Regex("<Volume[^>]*channel=\"Master\"[^>]*val=\"(\\d+)\"")
        .find(lastChange)?.groupValues?.get(1)?.toIntOrNull()
      if (volumeAttr != null) state = state.copy(volume = volumeAttr)
    }
    return state
  }

  fun unescape(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

  private fun extractTag(xml: String, tag: String): String? {
    val start = xml.indexOf("<$tag")
    if (start < 0) return null
    val openEnd = xml.indexOf('>', start)
    if (openEnd < 0) return null
    val close = xml.indexOf("</$tag>", openEnd)
    if (close < 0) return null
    return xml.substring(openEnd + 1, close)
  }

  /** 提取 <Name val="..."/> 形式的属性值 */
  private fun extractAttr(eventXml: String, name: String): String? =
    Regex("<$name[^>]*val=\"([^\"]*)\"").find(eventXml)?.groupValues?.get(1)
}
