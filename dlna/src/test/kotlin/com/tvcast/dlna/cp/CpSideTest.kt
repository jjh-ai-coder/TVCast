package com.tvcast.dlna.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpSideTest {

  // ---- SsdpSearch ----

  @Test
  fun mSearchPacketShape() {
    val packet = SsdpSearch.mSearchPacket(SsdpSearch.ST_MEDIA_RENDERER, mx = 3)
    assertTrue(packet.startsWith("M-SEARCH * HTTP/1.1"))
    assertTrue(packet.contains("ST: ${SsdpSearch.ST_MEDIA_RENDERER}"))
    assertTrue(packet.contains("MX: 3"))
    assertTrue(packet.contains("MAN: \"ssdp:discover\""))
  }

  @Test
  fun parseSearchResponse() {
    val response = "HTTP/1.1 200 OK\r\n" +
      "CACHE-CONTROL: max-age=1800\r\n" +
      "EXT:\r\n" +
      "LOCATION: http://192.168.1.5:49152/dd.xml\r\n" +
      "SERVER: Android/13 UPnP/1.0 TVCast/1.0\r\n" +
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
      "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
    val hit = SsdpSearch.parseResponse(response)
    assertNotNull(hit)
    assertEquals("http://192.168.1.5:49152/dd.xml", hit!!.location)
    assertEquals("uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1", hit.usn)
  }

  @Test
  fun parseNotifyIsNotASearchResponse() {
    val notify = SsdpSearch.parseResponse(
      "NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\nLOCATION: http://x/dd.xml\r\n\r\n",
    )
    assertNull(notify) // 首行无 200，拒绝
  }

  // ---- DeviceDescriptionParser ----

  private val ddd = """
    <?xml version="1.0"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>TVCast-899F</friendlyName>
        <UDN>uuid:1111-2222</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/control/avtransport</controlURL>
            <eventSubURL>/event/avtransport</eventSubURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>http://192.168.1.5:49152/control/renderingcontrol</controlURL>
            <eventSubURL>/event/renderingcontrol</eventSubURL>
          </service>
        </serviceList>
      </device>
    </root>
  """.trimIndent()

  @Test
  fun parseDddWithRelativeAndAbsoluteUrls() {
    val device = DeviceDescriptionParser.parse(
      "http://192.168.1.5:49152/dd.xml",
      ddd,
    )
    assertNotNull(device)
    assertEquals("TVCast-899F", device!!.friendlyName)
    assertEquals("uuid:1111-2222", device.udn)
    // 相对路径以 LOCATION 为 base 解析
    assertEquals("http://192.168.1.5:49152/control/avtransport", device.avTransportControlUrl)
    assertEquals("http://192.168.1.5:49152/event/avtransport", device.avTransportEventUrl)
    // 绝对路径保持不变
    assertEquals("http://192.168.1.5:49152/control/renderingcontrol", device.renderingControlUrl)
    assertTrue(device.castable)
  }

  @Test
  fun parseGarbageDddReturnsNull() {
    assertNull(DeviceDescriptionParser.parse("http://x/dd.xml", "not xml"))
    assertNull(DeviceDescriptionParser.parse("http://x/dd.xml", "<root><other/></root>"))
  }

  // ---- SoapControlPoint ----

  @Test
  fun envelopeFormat() {
    val soap = SoapControlPoint()
    val body = soap.envelope(SoapControlPoint.AVT, "Play", mapOf("InstanceID" to "0", "Speed" to "1"))
    assertTrue(body.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
    assertTrue(body.contains("<InstanceID>0</InstanceID><Speed>1</Speed>"))
    assertTrue(body.contains("s:encodingStyle"))
  }

  @Test
  fun escapeDidlProducesEscapedXmlArgument() {
    val escaped = SoapControlPoint.escapeDidl(
      SoapControlPoint.didlLite("http://a/b.mp4?x=1&y=2", "影片<一>"),
    )
    // 外层转义：内嵌 XML 的 < 与 URI 的 & 都是双重转义（& → &amp;amp;）
    assertTrue(escaped.startsWith("&lt;DIDL-Lite"))
    assertTrue(escaped.contains("x=1&amp;amp;y=2"))
    assertTrue(escaped.contains("影片&amp;lt;一&amp;gt;"))
    // CP 侧自产自销：接收端 DidlParser 反解两轮可还原标题
    val once = LastChangeParser.unescape(escaped)
    val twice = LastChangeParser.unescape(once)
    assertTrue(twice.contains("<dc:title>影片<一></dc:title>"))
  }

  @Test
  fun timeFormatRoundTrip() {
    assertEquals("0:01:05", SoapControlPoint.formatTime(65))
    assertEquals("1:00:00", SoapControlPoint.formatTime(3600))
    assertEquals(65L, SoapControlPoint.parseTime("0:01:05"))
    assertEquals(3600L, SoapControlPoint.parseTime("1:00:00"))
    assertEquals(0L, SoapControlPoint.parseTime("NOT_IMPLEMENTED"))
    assertEquals(0L, SoapControlPoint.parseTime(null))
  }

  // ---- LastChangeParser ----

  @Test
  fun parsePropertysetWithEscapedLastChange() {
    // 模拟接收端发出的报文：Event XML 整体转义后包在 LastChange 里
    val eventXml = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
      "<TransportState val=\"PAUSED_PLAYBACK\"/>" +
      "<AVTransportURI val=\"http://a/b.mp4?a=1&amp;b=2\"/>" +
      "</InstanceID></Event>"
    val escaped = eventXml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;")
    val propertyset = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
      "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
      "<e:property><LastChange>$escaped</LastChange></e:property></e:propertyset>"

    val state = LastChangeParser.parsePropertyset(propertyset)
    assertEquals("PAUSED_PLAYBACK", state.transportState)
    // AVTransportURI 的值本身含转义（& 在内层又转义一次），解两轮回到原始
    assertEquals("http://a/b.mp4?a=1&b=2", state.avtUri)
  }

  @Test
  fun parseRcsVolume() {
    val eventXml = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
      "<Volume channel=\"Master\" val=\"66\"/>" +
      "</InstanceID></Event>"
    val escaped = eventXml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;")
    val propertyset = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
      "<e:property><LastChange>$escaped</LastChange></e:property></e:propertyset>"
    val state = LastChangeParser.parsePropertyset(propertyset)
    assertEquals(66, state.volume)
  }
}
