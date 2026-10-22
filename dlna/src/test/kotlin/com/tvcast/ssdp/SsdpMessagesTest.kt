package com.tvcast.ssdp

import com.tvcast.dlna.DmrDevice
import com.tvcast.dlna.ssdp.SsdpMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpMessagesTest {

  private val udn = "uuid:6c1a-2b2b-3c3c-4d4d"
  private val location = "http://192.168.1.50:49152/dd.xml"
  private val server = "Android/13 UPnP/1.0 TVCast/1.0"
  private val messages = SsdpMessages(udn, location, server)

  @Test
  fun aliveCoversAllSixTargets() {
    val alive = messages.aliveMessages()
    assertEquals(6, alive.size)
    val nts = alive.map { Regex("NT: (.+)\r").find(it)!!.groupValues[1] }
    assertTrue(nts.contains("upnp:rootdevice"))
    assertTrue(nts.contains(udn))
    assertTrue(nts.contains(DmrDevice.DEVICE_TYPE))
    assertTrue(nts.contains("urn:schemas-upnp-org:service:AVTransport:1"))
    assertTrue(nts.contains("urn:schemas-upnp-org:service:RenderingControl:1"))
    assertTrue(nts.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
    for (message in alive) {
      assertTrue(message.contains("NTS: ssdp:alive"))
      assertTrue(message.contains("LOCATION: $location"))
      assertTrue(message.contains("CACHE-CONTROL: max-age=1800"))
      assertTrue(message.contains("HOST: 239.255.255.250:1900"))
    }
  }

  @Test
  fun usnComposition() {
    val alive = messages.aliveMessages()
    val root = alive.first { it.contains("NT: upnp:rootdevice") }
    assertTrue(root.contains("USN: $udn::upnp:rootdevice"))
    val uuidMsg = alive.first { Regex("NT: $udn\r").containsMatchIn(it) }
    assertTrue(uuidMsg.contains("USN: $udn\r"))
    val device = alive.first { it.contains("NT: ${DmrDevice.DEVICE_TYPE}") }
    assertTrue(device.contains("USN: $udn::${DmrDevice.DEVICE_TYPE}"))
  }

  @Test
  fun byebyeHasNoLocation() {
    val bye = messages.byebyeMessages()
    assertEquals(6, bye.size)
    for (message in bye) {
      assertTrue(message.contains("NTS: ssdp:byebye"))
      assertFalse(message.contains("LOCATION"))
    }
  }

  @Test
  fun searchAllReturnsSixResponses() {
    assertEquals(6, messages.searchResponses("ssdp:all").size)
  }

  @Test
  fun searchExactMatches() {
    val root = messages.searchResponses("upnp:rootdevice")
    assertEquals(1, root.size)
    assertTrue(root[0].contains("HTTP/1.1 200 OK"))
    assertTrue(root[0].contains("ST: upnp:rootdevice"))
    assertTrue(root[0].contains("EXT:"))

    val uuid = messages.searchResponses(udn)
    assertEquals(1, uuid.size)
    assertTrue(uuid[0].contains("ST: $udn"))

    val service = messages.searchResponses("urn:schemas-upnp-org:service:AVTransport:1")
    assertEquals(1, service.size)
    assertTrue(service[0].contains("USN: $udn::urn:schemas-upnp-org:service:AVTransport:1"))
  }

  @Test
  fun searchUnrelatedStIsIgnored() {
    assertEquals(0, messages.searchResponses("urn:schemas-upnp-org:service:ContentDirectory:1").size)
    assertEquals(0, messages.searchResponses("uuid:some-other-device").size)
  }

  @Test
  fun parseMSearch() {
    val packet = "M-SEARCH * HTTP/1.1\r\n" +
      "HOST: 239.255.255.250:1900\r\n" +
      "MAN: \"ssdp:discover\"\r\n" +
      "MX: 3\r\n" +
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
    val parsed = SsdpMessages.parse(packet)
    assertNotNull(parsed)
    assertTrue(parsed!!.isMSearch)
    assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", parsed.st)
    assertEquals(3, parsed.mx)
  }

  @Test
  fun parseNotifyAlive() {
    val packet = messages.aliveMessages().first()
    val parsed = SsdpMessages.parse(packet)
    assertNotNull(parsed)
    assertTrue(parsed!!.startLine.startsWith("NOTIFY"))
    assertFalse(parsed.isMSearch)
    assertNotNull(parsed.headers["usn"])
    assertEquals("ssdp:alive", parsed.headers["nts"])
  }

  @Test
  fun parseCaseInsensitiveHeaderKeys() {
    val packet = "M-SEARCH * HTTP/1.1\r\nst: upnp:rootdevice\r\nmx: 2\r\n\r\n"
    val parsed = SsdpMessages.parse(packet)
    assertEquals("upnp:rootdevice", parsed!!.st)
    assertEquals(2, parsed.mx)
  }
}
