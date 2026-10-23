package com.tvcast.sender.mirror

import android.util.Log
import com.tvcast.transport.FrameDeframer
import com.tvcast.transport.RateController
import com.tvcast.transport.UdpMirrorLink
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 镜像发送会话：UDP 链路（分片 + NACK 重传）+ 码率档位。
 * 每秒统计 NACK 条数交给 [RateController] 升降档，档位变化回调 [onBitrateMbps]
 * （上层热更新编码器码率）；TV 端 BYE 或秒统计经 [onSecond] 通知上层刷新 UI。
 */
class MirrorSession(
  remote: InetSocketAddress,
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

  fun start() {
    running = true
    link.startReceiveLoop()
    Thread({
      while (running) {
        Thread.sleep(1000)
        val n = nacks.getAndSet(0)
        rate.onSecondElapsed(n)
        if (rate.tier != lastTier) {
          lastTier = rate.tier
          Log.i(TAG, "bitrate tier -> ${rate.bitrateMbps}Mbps (nacks last sec=$n)")
          onBitrateMbps(rate.bitrateMbps)
        }
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
    link.sendBye()
    link.close()
  }
}
