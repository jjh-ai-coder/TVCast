package com.tvcast

import com.tvcast.dlna.MediaKind
import com.tvcast.dlna.RendererPlayer
import com.tvcast.dlna.TransportState
import com.tvcast.dlna.VolumeControl
import com.tvcast.dlna.gena.NotifySender
import java.util.concurrent.CopyOnWriteArrayList

/** 内存版 RendererPlayer：setMedia 即进入 PLAYING（模拟隐式播放成功） */
class FakeRenderer : RendererPlayer {

  val calls = CopyOnWriteArrayList<String>()

  @Volatile private var _uri: String? = null
  @Volatile private var _title: String? = null
  @Volatile private var _kind: MediaKind? = null
  @Volatile private var _meta: String? = null
  @Volatile private var _error: String? = null
  @Volatile var stateImpl: TransportState = TransportState.NO_MEDIA_PRESENT
  @Volatile var durationMsImpl: Long = 0L
  @Volatile var positionMsImpl: Long = 0L

  override val uri: String? get() = _uri
  override val title: String? get() = _title
  override val kind: MediaKind? get() = _kind
  override val state: TransportState get() = stateImpl
  override val positionMs: Long get() = positionMsImpl
  override val durationMs: Long get() = durationMsImpl
  override val lastError: String? get() = _error
  override val currentMetaData: String? get() = _meta

  fun setError(message: String?) {
    _error = message
    fire()
  }

  override fun setMedia(uri: String, title: String, kind: MediaKind, metaData: String?) {
    calls += "setMedia:$uri"
    _uri = uri
    _title = title
    _kind = kind
    _meta = metaData
    _error = null
    durationMsImpl = 100_000L
    positionMsImpl = 5_000L
    stateImpl = TransportState.PLAYING
    fire()
  }

  override fun play() {
    calls += "play"
    stateImpl = TransportState.PLAYING
    fire()
  }

  override fun pause() {
    calls += "pause"
    stateImpl = TransportState.PAUSED_PLAYBACK
    fire()
  }

  override fun stop() {
    calls += "stop"
    stateImpl = TransportState.STOPPED
    positionMsImpl = 0L
    fire()
  }

  override fun seekTo(positionMs: Long) {
    calls += "seekTo:$positionMs"
    positionMsImpl = positionMs
    fire()
  }

  private val listeners = CopyOnWriteArrayList<Runnable>()

  override fun addListener(listener: Runnable) {
    listeners += listener
  }

  override fun removeListener(listener: Runnable) {
    listeners -= listener
  }

  fun fire() {
    for (l in listeners) l.run()
  }
}

class FakeVolume : VolumeControl {

  @Volatile var volumeValue: Int = 30
  @Volatile var muteValue: Boolean = false

  override val volumePercent: Int get() = volumeValue
  override val muted: Boolean get() = muteValue

  override fun setVolume(percent: Int) {
    volumeValue = percent
    fire()
  }

  override fun setMuted(muted: Boolean) {
    muteValue = muted
    fire()
  }

  private val listeners = CopyOnWriteArrayList<Runnable>()

  override fun addListener(listener: Runnable) {
    listeners += listener
  }

  override fun removeListener(listener: Runnable) {
    listeners -= listener
  }

  private fun fire() {
    for (l in listeners) l.run()
  }
}

class RecordingSender : NotifySender {

  data class Sent(val sid: String, val seq: Long, val body: String)

  val sent = CopyOnWriteArrayList<Sent>()

  override fun send(callbackUrl: String, sid: String, seq: Long, body: String): Boolean {
    sent += Sent(sid, seq, body)
    return true
  }
}
