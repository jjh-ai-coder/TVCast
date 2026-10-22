package com.tvcast.dlna

/** 媒体类型：由 upnp:class / URI 扩展名推断 */
enum class MediaKind { VIDEO, AUDIO, IMAGE }

/** UPnP AVTransport TransportState（枚举名与规范字符串一致） */
enum class TransportState { NO_MEDIA_PRESENT, TRANSITIONING, PLAYING, PAUSED_PLAYBACK, STOPPED }

/**
 * 播放内核接口：dlna 协议栈只依赖此抽象，playback 层用 ExoPlayer 实现，
 * 测试用内存 fake 实现。属性可从任意线程读取（实现方需保证可见性），
 * 监听器回调不保证线程，状态查询一律拉取式（回调只表示"有变化"）。
 */
interface RendererPlayer {
    val uri: String?
    val title: String?
    val kind: MediaKind?
    val state: TransportState
    val positionMs: Long
    val durationMs: Long
    val lastError: String?
    val currentMetaData: String?

    /** 隐式播放：设置媒体即准备并自动起播 */
    fun setMedia(uri: String, title: String, kind: MediaKind, metaData: String?)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)

    fun addListener(listener: Runnable)
    fun removeListener(listener: Runnable)
}

/** 系统音量抽象（RenderingControl 依赖） */
interface VolumeControl {
    val volumePercent: Int
    val muted: Boolean
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)
    fun addListener(listener: Runnable)
    fun removeListener(listener: Runnable)
}
