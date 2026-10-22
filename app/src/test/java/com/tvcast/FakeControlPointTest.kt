package com.tvcast

import com.tvcast.dlna.DmrStack
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.Socket
import java.util.concurrent.Executor

/**
 * fake 控制点回归测试（spec §7）：JVM 裸 socket 直打 MiniHTTPServer，
 * 覆盖 M2/M3 关键路径：发现描述 → 隐式播放 → 未知 Action → 切台 → 控制/查询 → GENA。
 */
class FakeControlPointTest {

  companion object {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"

    private lateinit var stack: DmrStack
    private lateinit var renderer: FakeRenderer
    private lateinit var volume: FakeVolume
    private val sender = RecordingSender()
    private var port: Int = 0

    @JvmStatic
    @BeforeClass
    fun setUp() {
      renderer = FakeRenderer()
      volume = FakeVolume()
      stack = DmrStack(
        renderer = renderer,
        volume = volume,
        udn = "uuid:11112222-3333-4444-5555-666677778888",
        friendlyName = "TVCast-TEST",
        serverString = "JUnit/1.0 UPnP/1.0 TVCast/1.0",
        ipProvider = { "127.0.0.1" },
        enableSsdp = false,
        notifySender = sender,
        notifyExecutor = Executor { it.run() },
      )
      assertTrue(stack.start())
      port = stack.httpPort
    }

    @JvmStatic
    @AfterClass
    fun tearDown() {
      stack.shutdown()
    }
  }

  data class Resp(val status: Int, val headers: Map<String, String>, val body: String)

  private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = ""): Resp {
    Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 10_000
      val text = buildString {
        append("$method $path HTTP/1.1\r\n")
        append("Host: 127.0.0.1:$port\r\n")
        headers.forEach { (k, v) -> append("$k: $v\r\n") }
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        append("\r\n")
        append(body)
      }
      socket.getOutputStream().apply {
        write(text.toByteArray(Charsets.UTF_8))
        flush()
      }
      val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
      val idx = raw.indexOf("\r\n\r\n")
      assertTrue("response must have header block", idx > 0)
      val head = raw.substring(0, idx)
      val respBody = raw.substring(idx + 4)
      val statusLine = head.lineSequence().first()
      val status = Regex("\\d{3}").find(statusLine)?.value?.toInt()
        ?: error("bad status line: $statusLine")
      val headMap = head.lineSequence().drop(1).mapNotNull { line ->
        val i = line.indexOf(':')
        if (i > 0) line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim() else null
      }.toMap()
      return Resp(status, headMap, respBody)
    }
  }

  private fun soapBody(serviceType: String, action: String, args: String): String =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
      "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
      "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
      "<u:$action xmlns:u=\"$serviceType\">$args</u:$action>" +
      "</s:Body></s:Envelope>"

  private fun soap(serviceType: String, action: String, args: String = "<InstanceID>0</InstanceID>"): Resp =
    request(
      "POST", "/control/" + when (serviceType) {
        AVT -> "avtransport"
        RCS -> "renderingcontrol"
        else -> "connectionmanager"
      },
      mapOf(
        "Content-Type" to "text/xml; charset=\"utf-8\"",
        "SOAPACTION" to "\"$serviceType#$action\"",
      ),
      soapBody(serviceType, action, args),
    )

  // ---- M1：描述与 SCPD ----

  @Test
  fun fetchDeviceDescription() {
    val resp = request("GET", "/dd.xml")
    assertEquals(200, resp.status)
    assertTrue(resp.headers["content-type"]!!.startsWith("text/xml"))
    assertTrue(resp.body.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
    assertTrue(resp.body.contains("TVCast-TEST"))
    assertTrue(resp.body.contains("uuid:11112222-3333-4444-5555-666677778888"))
    // 描述中的 URL 必须是绝对地址且指向实际端口
    assertTrue(resp.body.contains("http://127.0.0.1:$port/control/avtransport"))
    assertTrue(resp.body.contains("http://127.0.0.1:$port/event/renderingcontrol"))
    assertTrue(resp.body.contains("http://127.0.0.1:$port/scpd/connectionmanager.xml"))
  }

  @Test
  fun fetchAllScpds() {
    for ((path, mustContain) in listOf(
      "/scpd/avtransport.xml" to "SetAVTransportURI",
      "/scpd/renderingcontrol.xml" to "SetVolume",
      "/scpd/connectionmanager.xml" to "GetProtocolInfo",
    )) {
      val resp = request("GET", path)
      assertEquals(200, resp.status)
      assertTrue(resp.body.contains(mustContain))
      assertTrue(resp.body.contains("<actionList>"))
    }
  }

  @Test
  fun unknownPathIs404() {
    assertEquals(404, request("GET", "/nope").status)
  }

  // ---- M2：AVTransport 控制与隐式播放 ----

  @Test
  fun implicitPlayOnSetAvTransportUri() {
    val before = renderer.calls.size
    val resp = soap(
      AVT, "SetAVTransportURI",
      "<InstanceID>0</InstanceID>" +
        "<CurrentURI>http://example.com/big_buck_bunny.mp4</CurrentURI>" +
        "<CurrentURIMetaData></CurrentURIMetaData>",
    )
    assertEquals(200, resp.status)
    assertTrue(resp.body.contains("SetAVTransportURIResponse"))
    // 不发 Play 也必须自动起播
    assertTrue(renderer.calls.contains("setMedia:http://example.com/big_buck_bunny.mp4"))
    assertTrue(before < renderer.calls.size)
  }
  @Test
  fun didlTitleAndClassFlowThrough() {
    val didl = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;照片 001&lt;/dc:title&gt;" +
      "&lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
    soap(
      AVT, "SetAVTransportURI",
      "<InstanceID>0</InstanceID>" +
        "<CurrentURI>http://example.com/photo001.jpg</CurrentURI>" +
        "<CurrentURIMetaData>$didl</CurrentURIMetaData>",
    )
    assertEquals("照片 001", renderer.title)
    assertEquals(com.tvcast.dlna.MediaKind.IMAGE, renderer.kind)
  }

  @Test
  fun unknownActionReturns401FaultWithoutCrash() {
    val before = renderer.calls.size
    val resp = soap(
      AVT, "X_SetAVTransportURI",
      "<InstanceID>0</InstanceID><CurrentURI>http://example.com/v.mp4</CurrentURI>",
    )
    assertEquals(500, resp.status)
    assertTrue(resp.body.contains("<errorCode>401</errorCode>"))
    assertEquals(before, renderer.calls.size)
    // 服务不崩溃：后续请求仍可用
    assertEquals(200, soap(AVT, "Play").status)
  }

  @Test
  fun rapidSwitchKeepsConsistentState() {
    val before = renderer.calls.count { it.startsWith("setMedia:") }
    soap(AVT, "SetAVTransportURI", "<CurrentURI>http://example.com/1.mp4</CurrentURI>")
    soap(AVT, "SetAVTransportURI", "<CurrentURI>http://example.com/2.mp4</CurrentURI>")
    soap(AVT, "SetAVTransportURI", "<CurrentURI>http://example.com/3.mp4</CurrentURI>")
    assertEquals("http://example.com/3.mp4", renderer.uri)
    assertEquals(before + 3, renderer.calls.count { it.startsWith("setMedia:") })
  }

  @Test
  fun transportControlsAndQueries() {
    assertEquals(200, soap(AVT, "Play").status)
    assertEquals("PLAYING", renderer.state.name)
    assertEquals(200, soap(AVT, "Pause").status)
    assertEquals("PAUSED_PLAYBACK", renderer.state.name)
    assertEquals(
      200,
      soap(AVT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:30</Target>").status,
    )
    assertEquals(90_000L, renderer.positionMsImpl)
    val position = soap(AVT, "GetPositionInfo")
    assertEquals(200, position.status)
    assertTrue(position.body.contains("<TrackDuration>0:01:40</TrackDuration>"))
    assertTrue(position.body.contains("<RelTime>0:01:30</RelTime>"))
    val transport = soap(AVT, "GetTransportInfo")
    assertTrue(transport.body.contains("PAUSED_PLAYBACK"))
    val media = soap(AVT, "GetMediaInfo")
    assertTrue(media.body.contains("<NrTracks>1</NrTracks>"))
    assertEquals(200, soap(AVT, "Stop").status)
    assertEquals("STOPPED", renderer.state.name)
  }

  @Test
  fun renderingControlVolumeAndMute() {
    assertEquals(
      200,
      soap(
        RCS, "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>55</DesiredVolume>",
      ).status,
    )
    assertEquals(55, volume.volumeValue)
    val getVolume = soap(RCS, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
    assertTrue(getVolume.body.contains("<CurrentVolume>55</CurrentVolume>"))
    assertEquals(
      200,
      soap(
        RCS, "SetMute",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>",
      ).status,
    )
    assertTrue(volume.muted)
    val getMute = soap(RCS, "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
    assertTrue(getMute.body.contains("<CurrentMute>1</CurrentMute>"))
  }

  @Test
  fun connectionManagerProtocolInfo() {
    val resp = soap(CM, "GetProtocolInfo")
    assertEquals(200, resp.status)
    assertTrue(resp.body.contains("video/mp4"))
    assertTrue(resp.body.contains("image/jpeg"))
    assertTrue(resp.body.contains("audio/mpeg"))
  }

  // ---- M3：GENA ----

  @Test
  fun subscribeDeliversInitialSnapshotThenIncrements() {
    val sentBefore = sender.sent.size
    val resp = request(
      "SUBSCRIBE", "/event/avtransport",
      mapOf(
        "CALLBACK" to "<http://127.0.0.1:9001/dev/cb>",
        "NT" to "upnp:event",
        "TIMEOUT" to "Second-600",
      ),
    )
    assertEquals(200, resp.status)
    val sid = resp.headers["sid"]
    assertTrue(sid!!.startsWith("uuid:"))
    assertEquals("Second-600", resp.headers["timeout"])
    // SEQ:0 初始全量快照
    assertEquals(sentBefore + 1, sender.sent.size)
    val initial = sender.sent.last()
    assertEquals(0L, initial.seq)
    assertEquals(sid, initial.sid)
    assertTrue(initial.body.contains("TransportState"))
    // 状态变化 → SEQ:1
    renderer.pause()
    assertEquals(sentBefore + 2, sender.sent.size)
    val increment = sender.sent.last()
    assertEquals(1L, increment.seq)
    assertEquals(sid, increment.sid)
    assertTrue(increment.body.contains("PAUSED_PLAYBACK"))
    // 退订
    assertEquals(
      200,
      request("UNSUBSCRIBE", "/event/avtransport", mapOf("SID" to sid)).status,
    )
    assertEquals(
      412,
      request("UNSUBSCRIBE", "/event/avtransport", mapOf("SID" to sid)).status,
    )
  }

  @Test
  fun renewSubscriptionKeepsSid() {
    val first = request(
      "SUBSCRIBE", "/event/renderingcontrol",
      mapOf("CALLBACK" to "<http://127.0.0.1:9002/rc/cb>", "NT" to "upnp:event"),
    )
    assertEquals(200, first.status)
    val sid = first.headers["sid"]!!
    val renew = request("SUBSCRIBE", "/event/renderingcontrol", mapOf("SID" to sid))
    assertEquals(200, renew.status)
    assertEquals(sid, renew.headers["sid"])
    assertEquals(
      412,
      request("SUBSCRIBE", "/event/renderingcontrol", mapOf("SID" to "uuid:dead-beef")).status,
    )
    request("UNSUBSCRIBE", "/event/renderingcontrol", mapOf("SID" to sid))
  }

  @Test
  fun rendererControlListenerPushesRcsEvents() {
    val subscribe = request(
      "SUBSCRIBE", "/event/renderingcontrol",
      mapOf("CALLBACK" to "<http://127.0.0.1:9003/rc2/cb>", "NT" to "upnp:event"),
    )
    assertEquals(200, subscribe.status)
    val sid = subscribe.headers["sid"]!!
    volume.setVolume(80) // 状态变化 → RCS LastChange 事件
    val rcsEvent = sender.sent.last { it.sid == sid && it.seq == 1L }
    assertTrue(rcsEvent.body.contains("Volume"))
    assertTrue(rcsEvent.body.contains("80"))
    request("UNSUBSCRIBE", "/event/renderingcontrol", mapOf("SID" to sid))
  }
}
