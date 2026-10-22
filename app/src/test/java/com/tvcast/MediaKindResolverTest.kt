package com.tvcast

import com.tvcast.dlna.MediaKind
import com.tvcast.dlna.MediaKindResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindResolverTest {

  @Test
  fun byUpnpClass() {
    assertEquals(MediaKind.VIDEO, MediaKindResolver.resolve("http://x/y", "object.item.videoItem"))
    assertEquals(MediaKind.AUDIO, MediaKindResolver.resolve("http://x/y", "object.item.audioItem.musicTrack"))
    assertEquals(MediaKind.IMAGE, MediaKindResolver.resolve("http://x/y", "object.item.imageItem.photo"))
  }

  @Test
  fun byExtension() {
    assertEquals(MediaKind.AUDIO, MediaKindResolver.resolve("http://x/y/a.MP3", null))
    assertEquals(MediaKind.AUDIO, MediaKindResolver.resolve("http://x/y/a.flac?token=1", null))
    assertEquals(MediaKind.IMAGE, MediaKindResolver.resolve("http://x/y/a.jpeg#frag", null))
    assertEquals(MediaKind.IMAGE, MediaKindResolver.resolve("http://x/y/a.png?w=100&h=50", null))
  }

  @Test
  fun unknownFallsBackToVideo() {
    assertEquals(MediaKind.VIDEO, MediaKindResolver.resolve("http://x/y/strange", null))
    assertEquals(MediaKind.VIDEO, MediaKindResolver.resolve("http://x/y/video.abc", "unknown-class"))
  }

  @Test
  fun fallbackTitleFromUri() {
    assertEquals("movie.mp4", MediaKindResolver.fallbackTitle("http://x:8080/path/movie.mp4?q=1"))
    assertEquals("photo.jpg", MediaKindResolver.fallbackTitle("photo.jpg"))
  }
}
