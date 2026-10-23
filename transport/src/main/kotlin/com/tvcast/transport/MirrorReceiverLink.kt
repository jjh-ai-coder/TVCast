package com.tvcast.transport

/**
 * 镜像接收链路（TV 端）：常驻监听 UDP 端口，内部驱动 deframer tick + 批量 NACK
 * （每 tick 把缺失索引合并为 ≤64 条/包回给发送端）。
 * 会话语义：INFO 包 sessionId 变化 = 发送端重启，上层调 [resetSession]；
 * 超时无数据帧判定会话结束由上层基于 [lastActivityAt] 实现（BYE 仅尽力而为）。
 */
class MirrorReceiverLink(
  bindPort: Int = MirrorLinkConst.DEFAULT_PORT,
  private val onFrame: (CompletedFrame) -> Unit,
  private val onInfo: (MirrorInfo) -> Unit = {},
  private val onBye: () -> Unit = {},
  private val log: (String) -> Unit = {},
) {

  private val pendingNacks = ArrayList<Pair<Long, Int>>()
  @Volatile private var running = false
  @Volatile var lastActivityAt: Long = System.currentTimeMillis()
    private set

  val link = UdpMirrorLink(
    bindPort = bindPort,
    remote = null,
    deframer = FrameDeframer(
      onFrame = {
        lastActivityAt = System.currentTimeMillis()
        onFrame(it)
      },
      onMissing = { frameSeq, index -> pendingNacks += frameSeq to index },
    ),
    onBye = onBye,
    onInfo = { info ->
      lastActivityAt = System.currentTimeMillis()
      onInfo(info)
    },
    log = log,
  )

  val localPort: Int get() = link.localPort

  fun start() {
    running = true
    link.startReceiveLoop()
    daemon("tvcast-mirror-rx-pump") {
      while (running) {
        link.deframer.tick()
        flushNacks()
        Thread.sleep(5)
      }
    }
  }

  private fun flushNacks() {
    if (pendingNacks.isEmpty()) return
    val address = link.remoteAddress() ?: run { pendingNacks.clear(); return }
    while (pendingNacks.isNotEmpty()) {
      val batch = pendingNacks.take(WireFormat.NACK_MAX_ENTRIES)
      val nack = WireFormat.encodeNack(batch)
      try {
        link.socket.send(java.net.DatagramPacket(nack, nack.size, address))
      } catch (_: Exception) {
      }
      repeat(batch.size) { pendingNacks.removeAt(0) }
    }
  }

  /** 发送端重启（新 sessionId）：清装配与交付游标，重新锚定 */
  fun resetSession() {
    pendingNacks.clear()
    link.deframer.reset()
  }

  /** 反向 BYE：TV 端主动退出，通知手机停止镜像（尽力而为） */
  fun sendByeToSender() = link.sendBye()

  fun stop() {
    running = false
    link.close()
  }
}
