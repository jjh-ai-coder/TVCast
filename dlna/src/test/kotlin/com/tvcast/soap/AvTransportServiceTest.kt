package com.tvcast.soap

import com.tvcast.FakeRenderer
import com.tvcast.dlna.MediaKind
import com.tvcast.dlna.TransportState
import com.tvcast.dlna.soap.AvTransportService
import com.tvcast.dlna.soap.SoapResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvTransportServiceTest {

  private val renderer = FakeRenderer()
  private val service = AvTransportService(renderer)

  private fun invoke(action: String, args: Map<String, String> = emptyMap()) = service.invoke(action, args)

  @Test
  fun setUriRequiresCurrentUri() {
    val result = invoke("SetAVTransportURI", mapOf("currenturi" to ""))
    assertTrue(result is SoapResult.Fault)
    assertEquals(402, (result as SoapResult.Fault).errorCode)
  }

  @Test
  fun setUriPlaysImplicitly() {
    val result = invoke(
      "SetAVTransportURI",
      mapOf("currenturi" to "http://example.com/video.mp4"),
    )
    assertTrue(result is SoapResult.Ok)
    assertEquals(TransportState.PLAYING, renderer.state)
    assertEquals("video.mp4", renderer.title)
    assertEquals(MediaKind.VIDEO, renderer.kind)
  }

  @Test
  fun setUriUsesDidlTitleAndClass() {
    val didl = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;我的歌&lt;/dc:title&gt;" +
      "&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
    invoke("SetAVTransportURI", mapOf("currenturi" to "http://example.com/x", "currenturimetadata" to didl))
    assertEquals("我的歌", renderer.title)
    assertEquals(MediaKind.AUDIO, renderer.kind)
  }

  @Test
  fun unknownActionReturns401() {
    val result = invoke("X_SetAVTransportURI", mapOf("currenturi" to "http://example.com/v.mp4"))
    assertTrue(result is SoapResult.Fault)
    assertEquals(401, (result as SoapResult.Fault).errorCode)
    // 且不得触发任何播放动作
    assertTrue(renderer.calls.isEmpty())
  }

  @Test
  fun seekRelTime() {
    invoke("Seek", mapOf("unit" to "REL_TIME", "target" to "0:01:30"))
    assertEquals(90_000L, renderer.positionMsImpl)
  }

  @Test
  fun seekBadUnitFails710() {
    val result = invoke("Seek", mapOf("unit" to "VOLUME", "target" to "10"))
    assertEquals(710, (result as SoapResult.Fault).errorCode)
  }

  @Test
  fun seekBadTargetFails402() {
    val result = invoke("Seek", mapOf("unit" to "REL_TIME", "target" to "abc"))
    assertEquals(402, (result as SoapResult.Fault).errorCode)
  }

  @Test
  fun seekTrackNrIsNoop() {
    val result = invoke("Seek", mapOf("unit" to "TRACK_NR", "target" to "1"))
    assertTrue(result is SoapResult.Ok)
  }

  @Test
  fun nextPreviousAreNoopSuccess() {
    assertTrue(invoke("Next") is SoapResult.Ok)
    assertTrue(invoke("Previous") is SoapResult.Ok)
  }

  @Test
  fun transportInfoReflectsError() {
    renderer.setError("设备不支持该媒体格式")
    val ok = invoke("GetTransportInfo") as SoapResult.Ok
    assertEquals("ERROR_OCCURRED", ok.args["CurrentTransportStatus"])
  }

  @Test
  fun positionInfoShape() {
    invoke("SetAVTransportURI", mapOf("currenturi" to "http://example.com/movie.mp4"))
    val ok = invoke("GetPositionInfo") as SoapResult.Ok
    assertEquals("0:01:40", ok.args["TrackDuration"]) // FakeRenderer 100s
    assertEquals("0:00:05", ok.args["RelTime"])
    assertEquals("http://example.com/movie.mp4", ok.args["TrackURI"])
    assertEquals("NOT_IMPLEMENTED", ok.args["TrackMetaData"])
    assertEquals("2147483647", ok.args["AbsCount"])
  }

  @Test
  fun mediaInfoShape() {
    invoke("SetAVTransportURI", mapOf("currenturi" to "http://example.com/a.mp3"))
    val ok = invoke("GetMediaInfo") as SoapResult.Ok
    assertEquals("1", ok.args["NrTracks"])
    assertEquals("http://example.com/a.mp3", ok.args["CurrentURI"])
  }
}
