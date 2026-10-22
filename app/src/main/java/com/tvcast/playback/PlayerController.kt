package com.tvcast.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tvcast.dlna.MediaKind
import com.tvcast.dlna.RendererPlayer
import com.tvcast.dlna.TransportState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ExoPlayer 封装，实现 dlna 层的 RendererPlayer。
 * - SOAP 控制来自 HTTP 线程：所有播放器操作 hop 到主线程；进度由主线程 500ms 周期
 *   缓存到 volatile 字段供任意线程读取
 * - 隐式播放：setMedia 即 prepare + playWhenReady=true
 * - 图片不经播放器，直接进入 PLAYING 由 UI 层 Glide 展示
 * - 音频焦点：失去/暂时失去→暂停，can-duck→降到 0.2 音量
 */
class PlayerController(context: Context) : RendererPlayer {

  private val main = Handler(Looper.getMainLooper())

  // OptIn 限定在此属性：不向使用方扩散 UnstableApi 要求
  @OptIn(UnstableApi::class)
  private val player: ExoPlayer = ExoPlayer.Builder(context)
    .setRenderersFactory(
      DefaultRenderersFactory(context)
        // ffmpeg 扩展（app/libs AAR）在类路径时：硬解优先，不支持的格式软解兜底；
        // 兜底失败/无 AAR 时行为与默认完全一致
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    )
    .build()
  private val listeners = CopyOnWriteArrayList<Runnable>()

  @Volatile private var _uri: String? = null
  @Volatile private var _title: String? = null
  @Volatile private var _kind: MediaKind? = null
  @Volatile private var _meta: String? = null
  @Volatile private var _state: TransportState = TransportState.NO_MEDIA_PRESENT
  @Volatile private var _positionMs: Long = 0L
  @Volatile private var _durationMs: Long = 0L
  @Volatile private var _error: String? = null

  override val uri: String? get() = _uri
  override val title: String? get() = _title
  override val kind: MediaKind? get() = _kind
  override val state: TransportState get() = _state
  override val positionMs: Long get() = _positionMs
  override val durationMs: Long get() = _durationMs
  override val lastError: String? get() = _error
  override val currentMetaData: String? get() = _meta

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  @Volatile private var ducked = false
  private var focusRequest: AudioFocusRequest? = null

  private val ticker = object : Runnable {
    override fun run() {
      syncPosition()
      main.postDelayed(this, 500L)
    }
  }

  init {
    player.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
          setState(TransportState.PLAYING)
        } else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
          setState(TransportState.PAUSED_PLAYBACK)
        }
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
          Player.STATE_ENDED -> setState(TransportState.STOPPED)
          Player.STATE_IDLE -> if (player.playerError != null) setState(TransportState.STOPPED)
        }
        syncPosition()
      }

      override fun onPlayerError(error: PlaybackException) {
        _error = humanError(error)
        setState(TransportState.STOPPED)
      }
    })
    main.post(ticker)
  }

  override fun setMedia(mediaUri: String, mediaTitle: String, mediaKind: MediaKind, metaData: String?) {
    main.post {
      _uri = mediaUri
      _title = mediaTitle
      _kind = mediaKind
      _meta = metaData
      _error = null
      _positionMs = 0L
      _durationMs = 0L
      player.stop()
      player.clearMediaItems()
      if (mediaKind == MediaKind.IMAGE) {
        setState(TransportState.PLAYING)
        return@post
      }
      setState(TransportState.TRANSITIONING)
      requestAudioFocus()
      player.setMediaItem(MediaItem.fromUri(mediaUri))
      player.prepare()
      player.playWhenReady = true
    }
  }

  override fun play() {
    main.post {
      if (_uri == null) return@post
      if (_kind == MediaKind.IMAGE) {
        setState(TransportState.PLAYING)
        return@post
      }
      requestAudioFocus()
      when (player.playbackState) {
        Player.STATE_IDLE -> {
          player.setMediaItem(MediaItem.fromUri(_uri!!))
          player.prepare()
        }
        Player.STATE_ENDED -> player.seekTo(0L)
      }
      player.playWhenReady = true
      if (player.playbackState == Player.STATE_READY) setState(TransportState.PLAYING)
    }
  }

  override fun pause() {
    main.post {
      if (_uri == null) return@post
      if (_kind == MediaKind.IMAGE) {
        setState(TransportState.PAUSED_PLAYBACK)
        return@post
      }
      player.playWhenReady = false
      if (player.playbackState == Player.STATE_READY) setState(TransportState.PAUSED_PLAYBACK)
    }
  }

  override fun stop() {
    main.post {
      player.stop()
      player.clearMediaItems()
      abandonAudioFocus()
      _positionMs = 0L
      setState(TransportState.STOPPED)
    }
  }

  override fun seekTo(positionMs: Long) {
    main.post {
      if (_kind == MediaKind.IMAGE) return@post
      player.seekTo(positionMs)
      _positionMs = positionMs
    }
  }

  override fun addListener(listener: Runnable) {
    listeners += listener
  }

  override fun removeListener(listener: Runnable) {
    listeners -= listener
  }

  fun attachView(view: PlayerView) {
    main.post { view.player = player }
  }

  fun detachView(view: PlayerView) {
    main.post { if (view.player === player) view.player = null }
  }

  fun release() {
    main.post {
      abandonAudioFocus()
      player.release()
    }
  }

  private fun setState(next: TransportState) {
    if (_state != next) {
      _state = next
      for (l in listeners) l.run()
    }
  }

  private fun syncPosition() {
    if (_uri == null || _kind == MediaKind.IMAGE) return
    try {
      _positionMs = player.currentPosition.coerceAtLeast(0L)
      val d = player.duration
      _durationMs = if (d > 0) d else 0L
    } catch (_: Exception) {
    }
  }

  // ---- 音频焦点 ----

  private val focusListener = OnAudioFocusChangeListener { change ->
    when (change) {
      AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
        main.post { player.playWhenReady = false }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        ducked = true
        main.post { player.volume = 0.2f }
      }
      AudioManager.AUDIOFOCUS_GAIN -> if (ducked) {
        ducked = false
        main.post { player.volume = 1f }
      }
    }
  }

  private fun requestAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        if (focusRequest == null) focusRequest = buildFocusRequest()
        focusRequest?.let { audioManager.requestAudioFocus(it) }
      } else {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      }
    } catch (_: Exception) {
    }
  }

  private fun abandonAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      } else {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(focusListener)
      }
    } catch (_: Exception) {
    }
  }

  @RequiresApi(26)
  private fun buildFocusRequest(): AudioFocusRequest =
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
          .build(),
      )
      .setOnAudioFocusChangeListener(focusListener)
      .build()

  private fun humanError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "无法访问媒体地址，请检查网络或链接"

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> "设备不支持该媒体格式"

    else -> "播放失败（${error.errorCode}）"
  }
}
