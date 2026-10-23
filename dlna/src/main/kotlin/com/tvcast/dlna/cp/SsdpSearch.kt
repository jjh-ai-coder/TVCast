package com.tvcast.dlna.cp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** M-SEARCH 响应（单播回包） */
data class SearchHit(val st: String?, val usn: String?, val location: String?)

/** SSDP 控制点搜索：发 M-SEARCH 组播、收单播响应（发组播不需要 MulticastLock）。 */
object SsdpSearch {

  const val MULTICAST_HOST = "239.255.255.250"
  const val MULTICAST_PORT = 1900
  const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

  fun mSearchPacket(st: String, mx: Int): String = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: $mx\r\n")
    append("ST: $st\r\n")
    append("\r\n")
  }

  /** 解析单播响应；非 200/无 LOCATION 返回 null */
  fun parseResponse(packetText: String): SearchHit? {
    val lines = packetText.split("\r\n", "\n")
    if (lines.isEmpty() || !lines[0].uppercase().contains(" 200 ")) return null
    var st: String? = null
    var usn: String? = null
    var location: String? = null
    for (i in 1 until lines.size) {
      val idx = lines[i].indexOf(':')
      if (idx <= 0) continue
      val key = lines[i].substring(0, idx).trim().lowercase()
      val value = lines[i].substring(idx + 1).trim()
      when (key) {
        "st" -> st = value
        "usn" -> usn = value
        "location" -> location = value
      }
    }
    return location?.let { SearchHit(st, usn, it) }
  }

  /**
   * 阻塞搜索：发送 M-SEARCH 并在 [waitMs] 窗口内收集去重后的 LOCATION。
   * 兼容组播被禁的网络时可改用 [directProbes] 逐个单播探测（发给已知地址不适用——
   * 单播 M-SEARCH 需设备支持，这里保留接口）。
   */
  fun search(
    waitMs: Long = 3500,
    st: String = ST_MEDIA_RENDERER,
    log: (String) -> Unit = {},
  ): List<SearchHit> {
    val hits = LinkedHashMap<String, SearchHit>()
    DatagramSocket(null).use { socket ->
      socket.reuseAddress = true
      socket.bind(InetSocketAddress(0))
      socket.soTimeout = 500
      val group = InetAddress.getByName(MULTICAST_HOST)
      val packet = mSearchPacket(st, mx = 3)
      // 发 2 次防丢包
      repeat(2) {
        val bytes = packet.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, group, MULTICAST_PORT))
      }
      val deadline = System.currentTimeMillis() + waitMs
      val buffer = ByteArray(4096)
      while (System.currentTimeMillis() < deadline) {
        val response = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(response)
        } catch (_: SocketTimeoutException) {
          continue
        }
        val text = String(response.data, 0, response.length, Charsets.UTF_8)
        val hit = try {
          parseResponse(text)
        } catch (_: Exception) {
          null
        } ?: continue
        val key = hit.usn ?: hit.location ?: continue
        hits.putIfAbsent(key, hit)
      }
    }
    log("SSDP search found ${hits.size} device(s)")
    return hits.values.toList()
  }
}
