package com.tvcast.didl

import com.tvcast.dlna.didl.DidlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DidlParserTest {

  @Test
  fun plainXml() {
    val didl = """
      <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
        <item id="1"><dc:title>庆余年 第二集</dc:title>
        <upnp:class>object.item.videoItem</upnp:class></item>
      </DIDL-Lite>
    """.trimIndent()
    val parsed = DidlParser.parse(didl)
    assertEquals("庆余年 第二集", parsed.title)
    assertEquals("object.item.videoItem", parsed.upnpClass)
  }

  @Test
  fun singleEscaped() {
    val escaped = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Title&lt;/dc:title&gt;" +
      "&lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
    val parsed = DidlParser.parse(escaped)
    assertEquals("Title", parsed.title)
    assertEquals("object.item.imageItem", parsed.upnpClass)
  }

  @Test
  fun doubleEscaped() {
    val double = "&amp;lt;DIDL-Lite&amp;gt;&amp;lt;item&amp;gt;&amp;lt;dc:title&amp;gt;D&amp;lt;/dc:title&amp;gt;" +
      "&amp;lt;/item&amp;gt;&amp;lt;/DIDL-Lite&amp;gt;"
    val parsed = DidlParser.parse(double)
    assertNotNull(parsed)
    assertEquals("D", parsed.title)
  }

  @Test
  fun garbageInputFailsSoft() {
    val parsed = DidlParser.parse("this is not xml at all")
    assertNull(parsed.title)
    assertNull(parsed.upnpClass)
  }

  @Test
  fun emptyAndNullInput() {
    assertNull(DidlParser.parse(null).title)
    assertNull(DidlParser.parse("").title)
    assertNull(DidlParser.parse("   ").title)
  }

  @Test
  fun emptyItem() {
    val parsed = DidlParser.parse("<DIDL-Lite><item/></DIDL-Lite>")
    assertNull(parsed.title)
    assertNull(parsed.upnpClass)
  }

  @Test
  fun missingItem() {
    val parsed = DidlParser.parse("<DIDL-Lite><container/><foo/></DIDL-Lite>")
    assertNull(parsed.title)
  }

  @Test
  fun malformedXmlFailsSoft() {
    val parsed = DidlParser.parse("<DIDL-Lite><item><dc:title>broken")
    assertNull(parsed.title)
  }

  @Test
  fun maliciousDoctypeIsRejected() {
    val evil = "<!DOCTYPE DIDL-Lite [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
      "<DIDL-Lite><item><dc:title>&xxe;</dc:title></item></DIDL-Lite>"
    val parsed = DidlParser.parse(evil)
    assertNull(parsed.title)
  }

  @Test
  fun truncatedEscapedInputFailsSoft() {
    val parsed = DidlParser.parse("&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;")
    assertNull(parsed.title)
  }

  @Test
  fun hugeInputFailsSoft() {
    val huge = "<DIDL-Lite><item><dc:title>" + "A".repeat(2_000_000) + "</dc:title></item></DIDL-Lite>"
    val parsed = DidlParser.parse(huge)
    assertEquals(2_000_000, parsed.title?.length)
  }
}
