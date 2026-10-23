package com.tvcast.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.system.exitProcess

/**
 * 调试/E2E 接收统计工具：绑定端口收镜像码流，重组并统计帧数/字节/码率/NACK 重传。
 * 缺包按 deframer 节奏回 NACK（批量编码，最多 64 条/包——M-C 生产端应同样批量）。
 * 用法：gradlew :transport:mirrorRecv -PrecvPort=9420 -PrecvSecs=20
 */
object MirrorStatsReceiver {

  @JvmStatic
  fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: MirrorLinkConst.DEFAULT_PORT
    val seconds = args.getOrNull(1)?.toLongOrNull() ?: 20L
    val socket = DatagramSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(port))
      receiveBufferSize = 1 shl 20
    }
    socket.soTimeout = 10 // 超时即驱动 tick，保证 receive/tick 同线程
    println("[recv] listening udp/$port for ${seconds}s ...")
    var sender: InetSocketAddress? = null
    var frames = 0L
    var keyframes = 0L
    var bytes = 0L
    var packets = 0L
    var nacksSent = 0L
    var framesLastSecond = 0L
    var secondStart = System.currentTimeMillis()
    val start = secondStart
    val pendingNacks = ArrayList<Pair<Long, Int>>()
    val deframer = FrameDeframer(
      onFrame = { frame ->
        frames++
        framesLastSecond++
        bytes += frame.bytes.size
        if (frame.keyframe) keyframes++
        if (frames == 1L) {
          val head = frame.bytes.take(4).joinToString(",") { "%02X".format(it) }
          println("[recv] first frame: seq=${frame.frameSeq} size=${frame.bytes.size} keyframe=${frame.keyframe} head=$head")
        }
      },
      onMissing = { frameSeq, index -> pendingNacks += frameSeq to index },
    )

    fun flushNacks() {
      val target = sender ?: run { pendingNacks.clear(); return }
      while (pendingNacks.isNotEmpty()) {
        val batch = pendingNacks.take(WireFormat.NACK_MAX_ENTRIES)
        val nack = WireFormat.encodeNack(batch)
        nacksSent += batch.size
        socket.send(DatagramPacket(nack, nack.size, target))
        repeat(batch.size) { pendingNacks.removeAt(0) }
      }
    }
    val buffer = ByteArray(WireFormat.MAX_DATAGRAM)
    val deadline = start + seconds * 1000
    loop@ while (true) {
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(packet)
      } catch (_: SocketTimeoutException) {
        // fallthrough → tick
      }
      if (packet.length > 0) {
        if (WireFormat.isBye(buffer, packet.length)) {
          println("[recv] BYE from ${packet.socketAddress}")
          break@loop
        }
        val decoded = WireFormat.decodeData(buffer, packet.length)
        if (decoded != null) {
          if (sender == null) sender = packet.socketAddress as? InetSocketAddress
          packets++
          deframer.receive(decoded)
        }
      }
      deframer.tick()
      flushNacks()
      val now = System.currentTimeMillis()
      if (now - secondStart >= 1000) {
        val elapsed = (now - start) / 1000.0
        val mbps = bytes * 8 / elapsed / 1_000_000
        println("[recv] t=%4.1fs frames=%d (last1s=%d) keyframes=%d MB=%.1f avg=%.1fMbps nacksSent=%d pending=%d"
          .format(elapsed, frames, framesLastSecond, keyframes, bytes / 1e6, mbps, nacksSent, deframer.pendingCount()))
        framesLastSecond = 0
        secondStart = now
      }
      if (now >= deadline) break@loop
    }
    val elapsed = (System.currentTimeMillis() - start) / 1000.0
    println("[recv] done: frames=$frames keyframes=$keyframes packets=$packets MB=%.2f avg=%.1fMbps nacksSent=$nacksSent"
      .format(bytes / 1e6, bytes * 8 / elapsed / 1_000_000))
    exitProcess(0)
  }
}
