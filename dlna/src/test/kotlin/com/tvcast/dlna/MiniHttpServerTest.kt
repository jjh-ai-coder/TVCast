package com.tvcast.dlna.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL

/**
 * 粘性端口策略：控制点缓存 LOCATION（含端口），栈重建必须优先沿用同一端口；
 * 被占则顺延，不行再随机。这是"连上后过一会儿设备离线"的根治点。
 */
class MiniHttpServerTest {

  private val handler = object : HttpHandler {
    override fun handle(request: HttpRequest): HttpResponse = HttpResponse.text(200, "ok")
  }

  @Test
  fun `preferred port is honored`() {
    val server = MiniHttpServer(handler)
    val port = server.start(preferredPort = 49580)
    try {
      assertEquals(49580, port)
    } finally {
      server.stop()
    }
  }

  @Test
  fun `occupied preferred port falls to next in range`() {
    val blocker = ServerSocket(49581)
    val server = MiniHttpServer(handler)
    val port = server.start(preferredPort = 49581)
    try {
      assertEquals(49582, port)
    } finally {
      server.stop()
      blocker.close()
    }
  }

  @Test
  fun `server serves http on sticky port`() {
    val server = MiniHttpServer(handler)
    val port = server.start(preferredPort = 49583)
    try {
      val conn = URL("http://127.0.0.1:$port/dd.xml").openConnection() as HttpURLConnection
      conn.connectTimeout = 2000
      conn.readTimeout = 2000
      assertEquals(200, conn.responseCode)
      conn.disconnect()
    } finally {
      server.stop()
    }
  }

  @Test
  fun `rebind after stop keeps same port`() {
    val server = MiniHttpServer(handler)
    val first = server.start(preferredPort = 49584)
    server.stop()
    val second = MiniHttpServer(handler)
    try {
      assertEquals(first, second.start(preferredPort = first))
    } finally {
      second.stop()
    }
  }
}
