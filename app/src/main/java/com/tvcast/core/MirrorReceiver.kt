package com.tvcast.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tvcast.playback.MirrorDecoder
import com.tvcast.transport.MirrorInfo
import com.tvcast.transport.MirrorLinkConst
import com.tvcast.transport.MirrorReceiverLink

/**
 * 屏幕镜像接收端（常驻）：监听 UDP 9420，INFO 即会话开始；
 * 超时无帧（3s）或收到 BYE 结束会话，但链路保持监听等待下一次镜像。
 * 状态变化经 [onChanged] 通知上层（CastService → UI）。
 */
class MirrorReceiver(
  context: Context,
  private val onChanged: () -> Unit,
) {

  companion object {
    private const val TAG = "TVCast/MirrorRx"
    private const val TIMEOUT_MS = 3000L
  }

  val decoder = MirrorDecoder(onError = { Log.e(TAG, "decoder: $it") })

  @Volatile var active = false
    private set
  @Volatile var statsText = ""
    private set

  private val main = Handler(Looper.getMainLooper())
  private var link: MirrorReceiverLink? = null
  private var lastDecodeCount = 0L
  private var lastDecodeFps = 0

  @Volatile private var running = false

  fun start() {
    if (running) return
    running = true
    link = MirrorReceiverLink(
      bindPort = MirrorLinkConst.DEFAULT_PORT,
      onFrame = { frame -> decoder.onFrame(frame) },
      onInfo = { info -> onInfo(info) },
      onBye = { main.post { endSession() } },
      log = { Log.d(TAG, it) },
    ).also { it.start() }
    main.post(watchdog)
    Log.i(TAG, "listening on udp/${MirrorLinkConst.DEFAULT_PORT}")
  }

  private fun onInfo(info: MirrorInfo) {
    val wasActive = active
    if (!wasActive || decoder.currentInfo()?.sessionId != info.sessionId) {
      link?.resetSession() // 发送端新会话：装配/游标全部重置
    }
    if (!wasActive) {
      active = true
      Log.i(TAG, "mirror session ${info.sessionId} started: ${info.width}x${info.height}@${info.fps}")
      main.post { onChanged() } // 唤起镜像 UI
    }
    decoder.onInfo(info)
  }

  private val watchdog = object : Runnable {
    override fun run() {
      if (!running) return
      // 自愈（M-D）：未在会话中但 UDP 端口意外丢失（ROM 冻结恢复后 socket 死亡）→ 重建监听
      if (!active) {
        val current = link
        if (current != null && current.localPort != MirrorLinkConst.DEFAULT_PORT) {
          Log.w(TAG, "mirror port lost (bound ${current.localPort}), rebinding")
          try { current.stop() } catch (_: Exception) {}
          link = null
        }
        if (link == null) {
          try {
            link = MirrorReceiverLink(
              bindPort = MirrorLinkConst.DEFAULT_PORT,
              onFrame = { frame -> decoder.onFrame(frame) },
              onInfo = { info -> onInfo(info) },
              onBye = { main.post { endSession() } },
              log = { Log.d(TAG, it) },
            ).also { it.start() }
            Log.i(TAG, "mirror listener rebound on udp/${MirrorLinkConst.DEFAULT_PORT}")
          } catch (e: Exception) {
            Log.e(TAG, "rebind failed", e)
          }
        }
      }
      val link = link ?: run { main.postDelayed(this, 1000); return }
      if (active && System.currentTimeMillis() - link.lastActivityAt > TIMEOUT_MS) {
        Log.i(TAG, "mirror session timed out (no data)")
        endSession()
      }
      // 秒级统计
      val decoded = decoder.decodedFrames.get()
      lastDecodeFps = (decoded - lastDecodeCount).toInt()
      lastDecodeCount = decoded
      val info = decoder.currentInfo()
      statsText = if (active && info != null) {
        "${info.width}×${info.height} · 解码 $lastDecodeFps fps · 累计 $decoded 帧"
      } else ""
      if (active) onChanged()
      main.postDelayed(this, 1000)
    }
  }

  /** TV 端主动退出（返回键）：反发 BYE 让手机停止采集（主线程禁网络，须后台发送） */
  fun stopByUser() {
    Log.i(TAG, "user stop: sending BYE to ${link?.link?.remoteAddress()}")
    val currentLink = link
    if (currentLink != null) {
      Thread({ currentLink.sendByeToSender() }, "tvcast-bye").apply {
        isDaemon = true
        start()
      }
    }
    endSession()
  }

  private fun endSession() {
    if (!active) return
    active = false
    statsText = ""
    decoder.release()
    Log.i(TAG, "mirror session ended")
    onChanged()
  }

  fun release() {
    running = false
    main.removeCallbacks(watchdog)
    link?.stop()
    link = null
    decoder.release()
    active = false
  }
}
