package com.tvcast.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * UDP 镜像链路：发送侧分片入环缓存并发包；接收侧收包分发（数据 → deframer，
 * NACK → 环缓存查找重发，BYE → 回调）。lossSimulator 仅测试用（模拟丢包）。
 * 发送节流：每 [pacingBatchSize] 包暂停 [pacingPauseMs]（默认 8 包 1ms），
 * 避免大关键帧的百包突发打爆对端/中间设备的 UDP 缓冲。
 */
class UdpMirrorLink(
  private val socket: DatagramSocket,
  private var remote: SocketAddress?,
  val deframer: FrameDeframer,
  val sendRing: SendRing = SendRing(),
  private val onNack: (Int) -> Unit = {},
  private val onBye: () -> Unit = {},
  private val lossSimulator: ((ByteArray) -> Boolean)? = null,
  private val pacingBatchSize: Int = 8,
  private val pacingPauseMs: Long = 1,
  private val log: (String) -> Unit = {},
) {

  constructor(
    bindPort: Int = 0,
    remote: SocketAddress? = null,
    deframer: FrameDeframer,
    onNack: (Int) -> Unit = {},
    onBye: () -> Unit = {},
    lossSimulator: ((ByteArray) -> Boolean)? = null,
    log: (String) -> Unit = {},
  ) : this(
    DatagramSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(bindPort))
      receiveBufferSize = 1 shl 20
      sendBufferSize = 1 shl 20
    },
    remote, deframer, SendRing(), onNack, onBye, lossSimulator, 8, 1, log,
  )

  val localPort: Int get() = socket.localPort

  @Volatile private var remoteAddress: SocketAddress? = remote

  fun setRemote(address: SocketAddress) {
    remoteAddress = address
  }

  fun remoteAddress(): SocketAddress? = remoteAddress

  private var frameSeq = 0L
  private var nextPacketSeq = 0L

  /** 发送一帧；@return 发出的包数（发送循环线程调用）。按批节流防突发丢包 */
  fun sendFrame(timestampMs: Long, keyframe: Boolean, frame: ByteArray): Int {
    val address = remoteAddress ?: return 0
    if (frame.isEmpty()) return 0
    val seq = frameSeq
    frameSeq = (frameSeq + 1) and 0xFFFFFFFFL
    val firstPacketSeq = nextPacketSeq
    var sent = 0
    FramePacketizer().packetize(frameSeq = seq, timestampMs = timestampMs, keyframe = keyframe, frame = frame) { datagram ->
      sendRing.offer(datagram, nextPacketSeq)
      nextPacketSeq = (nextPacketSeq + 1) and 0xFFFFFFFFL
      if (lossSimulator?.invoke(datagram) != true) {
        socket.send(DatagramPacket(datagram, datagram.size, address))
      }
      sent++
      if (pacingBatchSize > 0 && sent % pacingBatchSize == 0 && pacingPauseMs > 0) {
        try { Thread.sleep(pacingPauseMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
      }
    }
    sendRing.indexFrame(seq, firstPacketSeq)
    return sent
  }

  fun sendBye() {
    val address = remoteAddress ?: return
    val bye = WireFormat.encodeBye()
    // 4 字节单发易丢，连发 3 次提高送达率；接收端仍需以"超时无数据"兜底判定会话结束
    repeat(3) {
      try {
        socket.send(DatagramPacket(bye, bye.size, address))
      } catch (_: Exception) {
      }
      if (it < 2) try { Thread.sleep(2) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
  }

  /** 开始接收（后台守护线程）。纯发送侧可省略 */
  fun startReceiveLoop() {
    daemon("tvcast-mirror-rx") {
      val buffer = ByteArray(WireFormat.MAX_DATAGRAM)
      while (!socket.isClosed) {
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(packet)
        } catch (_: Exception) {
          break
        }
        if (remoteAddress == null) remoteAddress = packet.socketAddress
        handleRaw(buffer, packet.length)
      }
    }
  }

  /** 单包处理（接收线程或测试直接调用） */
  fun handleRaw(data: ByteArray, length: Int) {
    if (WireFormat.isBye(data, length)) {
      onBye()
      return
    }
    val nacks = WireFormat.decodeNack(data, length)
    if (nacks.isNotEmpty()) {
      onNack(nacks.size)
      val address = remoteAddress ?: return
      for ((frameSeq, index) in nacks) {
        val first = sendRing.firstPacketSeqOf(frameSeq) ?: continue
        val datagram = sendRing.lookup((first + index) and 0xFFFFFFFFL)
        if (datagram != null) {
          // 重传同样过丢包模拟（仅测试；生产无 simulator 即直发）
          if (lossSimulator?.invoke(datagram) != true) {
            try {
              socket.send(DatagramPacket(datagram, datagram.size, address))
            } catch (_: Exception) {
            }
          }
        }
      }
      return
    }
    val decoded = WireFormat.decodeData(data, length)
    if (decoded != null) deframer.receive(decoded)
  }

  fun close() {
    try {
      socket.close()
    } catch (_: Exception) {
    }
  }
}

internal fun daemon(name: String, block: () -> Unit): Thread =
  Thread { block() }.apply {
    this.name = name
    isDaemon = true
    start()
  }
