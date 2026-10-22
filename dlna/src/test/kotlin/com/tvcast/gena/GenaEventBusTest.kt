package com.tvcast.gena

import com.tvcast.FakeRenderer
import com.tvcast.RecordingSender
import com.tvcast.dlna.gena.EventBus
import com.tvcast.dlna.gena.GenaService
import com.tvcast.dlna.gena.LastChangeXml
import com.tvcast.dlna.http.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class GenaEventBusTest {

  private val sender = RecordingSender()
  private var now = 1_000_000L
  private val directExecutor = Executor { it.run() }

  private fun newBus(): EventBus {
    val bus = EventBus(sender = sender, executorOverride = directExecutor, clock = { now })
    bus.setSnapshotProvider(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(FakeRenderer()) }
    bus.start()
    return bus
  }

  private fun subscribeRequest(
    callback: String? = "<http://127.0.0.1:9000/cb>",
    nt: String? = "upnp:event",
    timeout: String? = "Second-300",
    sid: String? = null,
  ) = HttpRequest(
    "SUBSCRIBE", "/event/avtransport",
    buildList {
      if (sid == null) {
        callback?.let { add("CALLBACK" to it) }
        nt?.let { add("NT" to it) }
      }
      timeout?.let { add("TIMEOUT" to it) }
      sid?.let { add("SID" to it) }
    },
  )

  @Test
  fun newSubscribeReturnsSidAndInitialSnapshot() {
    val bus = newBus()
    val response = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest())
    assertEquals(200, response.status)
    val sid = response.headers.first { it.first == "SID" }.second
    assertTrue(sid.startsWith("uuid:"))
    assertEquals("Second-300", response.headers.first { it.first == "TIMEOUT" }.second)
    // 初始 SEQ:0 全量快照立即投递
    assertEquals(1, sender.sent.size)
    assertEquals(0L, sender.sent[0].seq)
    assertEquals(sid, sender.sent[0].sid)
    assertTrue(sender.sent[0].body.contains("LastChange"))
    bus.stop()
  }

  @Test
  fun renewWithSidOnly() {
    val bus = newBus()
    val first = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest())
    val sid = first.headers.first { it.first == "SID" }.second
    val renewed = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest(sid = sid))
    assertEquals(200, renewed.status)
    // 续订不再发初始快照
    assertEquals(1, sender.sent.size)
    bus.stop()
  }

  @Test
  fun renewUnknownSidFails412() {
    val bus = newBus()
    val response = bus.handleSubscribe(
      GenaService.AV_TRANSPORT,
      subscribeRequest(sid = "uuid:00000000-0000-0000-0000-000000000000"),
    )
    assertEquals(412, response.status)
    bus.stop()
  }

  @Test
  fun renewWithNtIs400() {
    val bus = newBus()
    val first = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest())
    val sid = first.headers.first { it.first == "SID" }.second
    // 续订同时携带 NT 属于不兼容请求
    val response = bus.handleSubscribe(
      GenaService.AV_TRANSPORT,
      HttpRequest(
        "SUBSCRIBE", "/event/avtransport",
        listOf("SID" to sid, "NT" to "upnp:event"),
      ),
    )
    assertEquals(400, response.status)
    bus.stop()
  }

  @Test
  fun missingNtOrCallbackFails412() {
    val bus = newBus()
    assertEquals(412, bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest(nt = null)).status)
    assertEquals(412, bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest(callback = null)).status)
    bus.stop()
  }

  @Test
  fun badNtFails400() {
    val bus = newBus()
    val response = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest(nt = "upnp:whatever"))
    assertEquals(400, response.status)
    bus.stop()
  }

  @Test
  fun invalidCallbackFails400() {
    val bus = newBus()
    val response = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest(callback = "not-a-url"))
    assertEquals(400, response.status)
    bus.stop()
  }

  @Test
  fun unsubscribeFlow() {
    val bus = newBus()
    val first = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest())
    val sid = first.headers.first { it.first == "SID" }.second
    val request = HttpRequest("UNSUBSCRIBE", "/event/avtransport", listOf("SID" to sid))
    assertEquals(200, bus.handleUnsubscribe(GenaService.AV_TRANSPORT, request).status)
    assertEquals(412, bus.handleUnsubscribe(GenaService.AV_TRANSPORT, request).status)
    bus.stop()
  }

  @Test
  fun unsubscribeWithoutSidFails400() {
    val bus = newBus()
    val response = bus.handleUnsubscribe(
      GenaService.AV_TRANSPORT,
      HttpRequest("UNSUBSCRIBE", "/event/avtransport", emptyList()),
    )
    assertEquals(400, response.status)
    bus.stop()
  }

  @Test
  fun notifyIncrementsSeqPerSubscription() {
    val bus = newBus()
    val renderer = FakeRenderer()
    bus.setSnapshotProvider(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
    val first = bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest())
    val sid = first.headers.first { it.first == "SID" }.second
    renderer.pause() // 触发 listener 由 DmrStack 负责，这里直接 notify
    bus.notify(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
    bus.notify(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
    assertEquals(3, sender.sent.size)
    assertEquals(0L, sender.sent[0].seq)
    assertEquals(1L, sender.sent[1].seq)
    assertEquals(2L, sender.sent[2].seq)
    assertTrue(sender.sent[2].body.contains("PAUSED_PLAYBACK"))
    bus.stop()
  }

  @Test
  fun expiredSubscriptionPurgedAndSkipped() {
    val bus = newBus()
    val renderer = FakeRenderer()
    bus.setSnapshotProvider(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
    bus.handleSubscribe(GenaService.AV_TRANSPORT, subscribeRequest(timeout = "Second-10"))
    now += 60_000L // 超时
    bus.purgeExpired()
    assertEquals(0, bus.subscriptionCount())
    // 过期后 notify 不再投递
    bus.notify(GenaService.AV_TRANSPORT) { LastChangeXml.avtSnapshot(renderer) }
    assertEquals(1, sender.sent.size) // 仅初始快照
    bus.stop()
  }

  @Test
  fun timeoutParsing() {
    assertEquals(300, EventBus.parseTimeout("Second-300"))
    assertEquals(1800, EventBus.parseTimeout("Second-99999")) // 上限
    assertEquals(1800, EventBus.parseTimeout("Second-infinite"))
    assertEquals(1800, EventBus.parseTimeout(null))
    assertEquals(60, EventBus.parseTimeout("Second-60"))
  }

  @Test
  fun httpDateFormat() {
    val date = EventBus.httpDate(0L)
    assertTrue(date.endsWith("GMT"))
    assertTrue(date.contains("1970"))
  }
}
