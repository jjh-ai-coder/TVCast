package com.tvcast

import com.tvcast.dlna.UpnpTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpnpTimeTest {

  @Test
  fun formatBasics() {
    assertEquals("0:00:00", UpnpTime.format(0))
    assertEquals("0:00:59", UpnpTime.format(59))
    assertEquals("0:01:00", UpnpTime.format(60))
    assertEquals("1:02:03", UpnpTime.format(3723))
    assertEquals("2:00:00", UpnpTime.format(7200))
  }

  @Test
  fun formatNegativeClampedToZero() {
    assertEquals("0:00:00", UpnpTime.format(-5))
    assertEquals("0:00:00", UpnpTime.formatMs(-1000))
  }

  @Test
  fun parseStandard() {
    assertEquals(3723L, UpnpTime.parse("1:02:03")!!)
    assertEquals(3723L, UpnpTime.parse("01:02:03")!!)
    assertEquals(0L, UpnpTime.parse("0:00:00")!!)
  }

  @Test
  fun parseTwoSegments() {
    assertEquals(611L, UpnpTime.parse("10:11"))
    assertEquals(62L, UpnpTime.parse("1:02"))
  }

  @Test
  fun parseInvalid() {
    assertNull(UpnpTime.parse("bad"))
    assertNull(UpnpTime.parse(""))
    assertNull(UpnpTime.parse("0:99:00"))
    assertNull(UpnpTime.parse("0:00:99"))
    assertNull(UpnpTime.parse("1:02:03:04"))
    assertNull(UpnpTime.parse("-1:00:00"))
  }

  @Test
  fun parseToMs() {
    assertEquals(3723_000L, UpnpTime.parseToMs("1:02:03"))
  }
}
