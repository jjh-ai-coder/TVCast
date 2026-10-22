package com.tvcast.gena

import com.tvcast.FakeRenderer
import com.tvcast.FakeVolume
import com.tvcast.dlna.MediaKind
import com.tvcast.dlna.gena.LastChangeXml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastChangeXmlTest {

  @Test
  fun avtSnapshotIsEscapedPropertyset() {
    val renderer = FakeRenderer()
    renderer.setMedia(
      "http://example.com/v.mp4?a=1&b=2",
      "标题",
      MediaKind.VIDEO,
      "<DIDL-Lite><item><dc:title>标题</dc:title></item></DIDL-Lite>",
    )
    val xml = LastChangeXml.avtSnapshot(renderer)
    assertTrue(xml.startsWith("<?xml"))
    assertTrue(xml.contains("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"))
    assertTrue(xml.contains("<LastChange>"))
    // 嵌套 Event 整体被转义
    assertTrue(xml.contains("&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;"))
    assertTrue(xml.contains("TransportState"))
    assertTrue(xml.contains("PLAYING"))
    // URI 中的 & 经属性转义 + LastChange 文本转义后为双重转义（CP 反解两轮得到原始 &）
    assertTrue(xml.contains("a=1&amp;amp;b=2"))
    assertTrue(xml.contains("AVTransportURI"))
  }

  @Test
  fun avtSnapshotErrorState() {
    val renderer = FakeRenderer()
    renderer.setError("fail")
    val xml = LastChangeXml.avtSnapshot(renderer)
    assertTrue(xml.contains("ERROR_OCCURRED"))
  }

  @Test
  fun rcsSnapshotMasterChannel() {
    val volume = FakeVolume()
    volume.setVolume(55)
    volume.setMuted(true)
    val xml = LastChangeXml.rcsSnapshot(volume)
    assertTrue(xml.contains("urn:schemas-upnp-org:metadata-1-0/RCS/"))
    // LastChange 文本转义后的 Master 声道 Volume 字段
    assertTrue(xml.contains("&lt;Volume channel=&quot;Master&quot; val=&quot;55&quot;/&gt;"))
    assertTrue(xml.contains("Mute"))
    // RCS 与 AVT 一样经 LastChange 包裹
    assertTrue(xml.contains("<LastChange>"))
  }

  @Test
  fun cmSnapshotDirectProperties() {
    val xml = LastChangeXml.cmSnapshot()
    assertTrue(xml.contains("<SinkProtocolInfo>"))
    assertTrue(xml.contains("video/mp4"))
    assertFalse(xml.contains("LastChange")) // CM 事件为直属属性，非 LastChange
  }
}
