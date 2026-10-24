package com.tvcast.sender.mirror

import android.util.Log
import com.tvcast.transport.FrameDeframer
import com.tvcast.transport.MirrorInfo
import com.tvcast.transport.RateController
import com.tvcast.transport.UdpMirrorLink
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 镜像发送会话：UDP 链路（分片 + NACK 重传）+ 码率档位。
 * 每秒统计 NACK 条数交给 [RateController] 升降档，档位变化回调 [onBitrateMbps]
 * （上层热更新编码器码率）；TV 端 BYE 或秒统计经 [onSecond] 通知上层刷新 UI。
 * 视频信息（分辨率/fps）经 [updateVideoInfo] 设置后随秒循环节期广播（INFO 包），
 * 供接收端配置解码器并识别会话重启。
 */
class MirrorSession(
  remote: InetSocketAddress,
  private val sessionId: Int,
  private val onBitrateMbps: (mbps: Int) -> Unit = {},
  private val onSecond: (nacks: Int) -> Unit = {},
  private val onBye: () -> Unit = {},
) {

  companion object {
    private const val TAG = "TVCast/MirrorSession"
  }

  private val nacks = AtomicInteger()
  private val rate = RateController(tiersMbps = intArrayOf(8, 4, 2))
  @Volatile private var running = false
  @Volatile private var videoInfo: MirrorInfo? = null
  @Volatile var lastTier = -1
    private set

  /** 发送侧不做接收重组，deframer 仅为满足链路构造（回调留空） */
  val link = UdpMirrorLink(
    bindPort = 0,
    remote = remote,
    deframer = FrameDeframer(onFrame = {}, onMissing = { _, _ -> }),
    onNack = { nacks.addAndGet(it) },
    onBye = onBye,
    log = { Log.d(TAG, it) },
  )

  val currentBitrateMbps: Int get() = rate.bitrateMbps

  /** 编码器就绪/重建时调用：连发 3 次保证接收端尽快拿到 */
  fun updateVideoInfo(width: Int, height: Int, fps: Int) {
    val info = MirrorInfo(sessionId, width, height, fps)
    videoInfo = info
    repeat(3) {
      link.sendInfo(info)
      if (it < 2) try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
  }

  fun start() {
    running = true
    link.startReceiveLoop()
    Thread({
      var ticks = 0
      while (running) {
        Thread.sleep(1000)
        val n = nacks.getAndSet(0)
        rate.onSecondElapsed(n)
        if (rate.tier != lastTier) {
          lastTier = rate.tier
          Log.i(TAG, "bitrate tier -> ${rate.bitrateMbps}Mbps (nacks last sec=$n)")
          onBitrateMbps(rate.bitrateMbps)
        }
        // INFO 周期广播：新加入/丢光 INFO 的接收端在 ≤2s 内恢复
        if (ticks % 2 == 0) videoInfo?.let { link.sendInfo(it) }
        ticks++
        onSecond(n)
      }
    }, "tvcast-mirror-stats").apply {
      isDaemon = true
      start()
    }
  }

  fun send(timestampMs: Long, keyframe: Boolean, bytes: ByteArray): Int =
    link.sendFrame(timestampMs, keyframe, bytes)

  fun stop() {
    running = false
    // stop() 常在主线程被调（服务 onDestroy 等），而 socket.send 主线程会抛
    // NetworkOnMainThreadException 被吞掉——BYE 必须后台发完再关链路
    val sender = Thread({ link.sendBye() }, "tvcast-bye").apply {
      isDaemon = true
      start()
    }
    try { sender.join(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    link.close()
  }
}
