package com.tvcast.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.tvcast.dlna.VolumeControl
import java.util.concurrent.CopyOnWriteArrayList

/** 系统媒体音量（STREAM_MUSIC）映射为 DLNA 0~100，供 RenderingControl 使用 */
class SystemVolumeControl(context: Context) : VolumeControl {

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val listeners = CopyOnWriteArrayList<Runnable>()

  // 系统音量档位通常不是 100 格（手机 15、电视 60/100…），设 66% 读回可能变 60%。
  // 记录最近下发的百分比，底层档位未被遥控器等外部改动时读回精确值。
  @Volatile private var lastSetPercent: Int? = null
  @Volatile private var lastSetStreamVolume: Int = -1

  override val volumePercent: Int
    get() {
      val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      if (max <= 0) return lastSetPercent ?: 0
      val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      if (current == lastSetStreamVolume && lastSetPercent != null) return lastSetPercent!!
      return Math.round(current * 100f / max).toInt()
    }

  override val muted: Boolean
    get() = if (Build.VERSION.SDK_INT >= 23) {
      audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
    } else {
      @Suppress("DEPRECATION")
      audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

  override fun setVolume(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (clamped > 0 && muted) setMuted(false)
    if (max > 0) {
      val target = Math.round(clamped * max / 100f).toInt().coerceIn(0, max)
      try {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        lastSetPercent = clamped
        lastSetStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      } catch (_: Exception) {
        // 部分静音设备（如 -no-audio 模拟器）禁止改音量：仍记住目标值供查询
        lastSetPercent = clamped
        lastSetStreamVolume = target
      }
    } else {
      lastSetPercent = clamped
    }
    fire()
  }

  override fun setMuted(mute: Boolean) {
    try {
      if (Build.VERSION.SDK_INT >= 23) {
        audioManager.adjustStreamVolume(
          AudioManager.STREAM_MUSIC,
          if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
          0,
        )
      } else {
        @Suppress("DEPRECATION")
        audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute)
      }
    } catch (_: Exception) {
    }
    fire()
  }

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
