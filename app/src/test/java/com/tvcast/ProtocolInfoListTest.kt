package com.tvcast

import com.tvcast.dlna.ProtocolInfoList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolInfoListTest {

  @Test
  fun coversCommonFormats() {
    val sink = ProtocolInfoList.sinkProtocolInfo()
    assertTrue(sink.contains("video/mp4"))
    assertTrue(sink.contains("video/x-matroska"))
    assertTrue(sink.contains("video/x-msvideo"))
    assertTrue(sink.contains("video/x-flv"))
    assertTrue(sink.contains("video/mp2t"))
    assertTrue(sink.contains("audio/mpeg"))
    assertTrue(sink.contains("audio/flac"))
    assertTrue(sink.contains("audio/x-ms-wma"))
    assertTrue(sink.contains("image/jpeg"))
    assertTrue(sink.contains("image/png"))
  }

  @Test
  fun entriesWellFormed() {
    val entries = ProtocolInfoList.sinkProtocolInfo().split(",")
    assertTrue(entries.size > 30)
    for (entry in entries) {
      assertTrue("entry must be http-get:*:mime:* → $entry", entry.startsWith("http-get:*:"))
      assertFalse("no stray spaces: $entry", entry.contains(" "))
    }
    assertFalse(ProtocolInfoList.sinkProtocolInfo().contains(",,"))

    val mimes = entries.map { it.substringAfter(":*:", "").substringBefore(':') }
    assertTrue(mimes.contains("video/mp4"))
    assertTrue(mimes.contains("audio/mpeg"))
    assertTrue(mimes.contains("image/jpeg"))
  }

  @Test
  fun hasDlnaPnParams() {
    val sink = ProtocolInfoList.sinkProtocolInfo()
    assertTrue(sink.contains("DLNA.ORG_PN=AVC_MP4"))
    assertTrue(sink.contains("DLNA.ORG_PN=JPEG_LRG"))
  }
}
