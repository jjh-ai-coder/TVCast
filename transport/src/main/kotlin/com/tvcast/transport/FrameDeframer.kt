package com.tvcast.transport

import java.util.ArrayDeque

/**
 * 接收端重组器：乱序/重复/丢包容错。
 * - 完整且按帧序到达的帧经 [onFrame] 顺序交付（解码器要求按序）
 * - 缺包先缓冲；[tick] 周期驱动：缺包超过 [nackDelayMs] 触发 [onMissing]（同一缺包最多 nackTimes 次），
 *   等待超过 [giveUpMs] 放弃该帧（交由上层跳到下一个关键帧）
 * - 只保留窗口内（最近 [windowFrames] 帧）的组装状态，过期帧直接丢弃
 * 线程约束：单线程调用 receive/tick。
 */
class FrameDeframer(
  private val onFrame: (CompletedFrame) -> Unit,
  private val onMissing: (frameSeq: Long, packetIndex: Int) -> Unit = { _, _ -> },
  private val nackDelayMs: Long = 4,
  private val giveUpMs: Long = 200,
  private val nackTimes: Int = 8,
  private val windowFrames: Int = 256,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private class Assembly(
    val frameSeq: Long,
    val packetCount: Int,
    val timestampMs: Long,
    val keyframe: Boolean,
  ) {
    val parts = arrayOfNulls<ByteArray>(packetCount)
    var received = 0
    var lastNackedAt = 0L
    val nackCounts = HashMap<Int, Int>()
    val arrivedAt = System.currentTimeMillis()
  }

  private val assemblies = HashMap<Long, Assembly>()
  private val readyQueue = ArrayDeque<CompletedFrame>()
  private var nextDeliverSeq = -1L
  private var highestSeq = -1L

  fun receive(packet: DataPacket) {
    if (packet.frameSeq + windowFrames < highestSeq) return // 窗口外的迟到包
    if (packet.frameSeq > highestSeq) highestSeq = packet.frameSeq
    val assembly = assemblies.getOrPut(packet.frameSeq) {
      Assembly(packet.frameSeq, packet.packetCount, packet.timestampMs, packet.keyframe)
    }
    if (assembly.parts[packet.packetIndex] != null) return // 重复包
    assembly.parts[packet.packetIndex] = packet.payloadCopy()
    assembly.received++
    if (assembly.received == assembly.packetCount) {
      assemblies.remove(packet.frameSeq)
      val size = assembly.parts.sumOf { it!!.size }
      val bytes = ByteArray(size)
      var offset = 0
      for (part in assembly.parts) {
        val bytes2 = part!!
        System.arraycopy(bytes2, 0, bytes, offset, bytes2.size)
        offset += bytes2.size
      }
      readyQueue.addLast(
        CompletedFrame(assembly.frameSeq, assembly.timestampMs, assembly.keyframe, bytes, assembly.packetCount),
      )
      drainReady()
    }
  }

  private fun drainReady() {
    while (readyQueue.isNotEmpty()) {
      val head = readyQueue.peekFirst()
      // 顺序交付：起步阶段（nextDeliverSeq 未定）直接交付；之后严格按序
      if (nextDeliverSeq != -1L && head.frameSeq < nextDeliverSeq) {
        readyQueue.removeFirst() // 迟到的重复帧，丢弃
        continue
      }
      if (nextDeliverSeq != -1L && head.frameSeq != nextDeliverSeq) return // 前面的帧还没齐，等待
      readyQueue.removeFirst()
      nextDeliverSeq = head.frameSeq + 1
      onFrame(head)
    }
  }

  /** 周期调用（建议 5~10ms）：驱动 NACK 请求与超时放弃 */
  fun tick() {
    val now = clock()
    // NACK：不完整帧在 nackDelayMs 后开始请求缺失包
    for ((_, assembly) in assemblies) {
      val age = now - assembly.arrivedAt
      if (age < nackDelayMs) continue
      if (age > giveUpMs) continue
      if (now - assembly.lastNackedAt < nackDelayMs) continue
      assembly.lastNackedAt = now
      for (index in 0 until assembly.packetCount) {
        if (assembly.parts[index] == null) {
          val count = assembly.nackCounts.getOrDefault(index, 0)
          if (count < nackTimes) {
            assembly.nackCounts[index] = count + 1
            onMissing(assembly.frameSeq, index)
          }
        }
      }
    }
    // 放弃：等太久的帧清掉，推进交付游标让后续帧不被卡死
    val expired = assemblies.filterValues { now - it.arrivedAt > giveUpMs }
    for ((seq, _) in expired) {
      assemblies.remove(seq)
      if (seq < nextDeliverSeq || nextDeliverSeq == -1L) continue
      if (seq == nextDeliverSeq) {
        // 正在等的帧放弃了，游标后移尝试交付后续已完成的帧
        nextDeliverSeq = seq + 1
        drainReady()
      }
    }
    // 清理窗口外的残留
    if (highestSeq > windowFrames) {
      val cutoff = highestSeq - windowFrames
      assemblies.keys.removeAll { it < cutoff }
    }
  }

  /** 已交付/等待中的诊断信息 */
  fun pendingCount(): Int = assemblies.size + readyQueue.size
}
