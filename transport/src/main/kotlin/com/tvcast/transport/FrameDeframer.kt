package com.tvcast.transport

import java.util.ArrayDeque

/**
 * 接收端重组器：乱序/重复/丢包容错。
 * - 完整且按帧序到达的帧经 [onFrame] 顺序交付（解码器要求按序）
 * - 缺包先缓冲；[tick] 周期驱动：缺包超过 [nackDelayMs] 触发 [onMissing]（同一缺包最多 nackTimes 次，
 *   同一装配体的重发请求间隔 [nackRepeatMs]），等待超过 [giveUpMs] 放弃该帧（交由上层跳到下一个关键帧）
 * - 整帧全丢（一个包都没到，无装配体）也会在 giveUpMs 后跳过该序号，游标不卡死
 * - 交付游标已过的帧：迟到/重传包直接丢弃，不重建装配体（否则与 NACK 重传形成风暴）
 * - 只保留窗口内（最近 [windowFrames] 帧）的组装状态，过期帧直接丢弃
 * 线程约束：[receive]/[tick] 内部加锁，可由不同线程驱动（UDP 收包线程 + 定时器线程）。
 */
class FrameDeframer(
  private val onFrame: (CompletedFrame) -> Unit,
  private val onMissing: (frameSeq: Long, packetIndex: Int) -> Unit = { _, _ -> },
  private val nackDelayMs: Long = 4,
  private val giveUpMs: Long = 400,
  private val nackTimes: Int = 12,
  private val nackRepeatMs: Long = 30,
  private val windowFrames: Int = 256,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private class Assembly(
    val frameSeq: Long,
    val packetCount: Int,
    val timestampMs: Long,
    val keyframe: Boolean,
    val arrivedAt: Long,
  ) {
    val parts = arrayOfNulls<ByteArray>(packetCount)
    var received = 0
    var lastNackedAt = 0L
    val nackCounts = HashMap<Int, Int>()
  }

  private val assemblies = HashMap<Long, Assembly>()
  /** 已组完待交付的帧，按 frameSeq 索引（帧可能乱序完成，交付必须按序） */
  private val readyQueue = HashMap<Long, CompletedFrame>()
  private var nextDeliverSeq = -1L
  private var highestSeq = -1L
  private val lock = Any()
  /** 交付游标停在无装配体序号上的起始时刻（整帧全丢检测） */
  private var cursorMissingSince = 0L

  fun receive(packet: DataPacket) = synchronized(lock) { receiveLocked(packet) }

  private fun receiveLocked(packet: DataPacket) {
    if (packet.frameSeq + windowFrames < highestSeq) return // 窗口外的迟到包
    // 交付游标已过：迟到/重复重传包，直接丢弃（不重建装配体，避免 NACK 反馈风暴）
    if (nextDeliverSeq != -1L && packet.frameSeq < nextDeliverSeq) return
    if (packet.frameSeq > highestSeq) highestSeq = packet.frameSeq
    val assembly = assemblies.getOrPut(packet.frameSeq) {
      Assembly(packet.frameSeq, packet.packetCount, packet.timestampMs, packet.keyframe, clock())
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
      readyQueue[assembly.frameSeq] =
        CompletedFrame(assembly.frameSeq, assembly.timestampMs, assembly.keyframe, bytes, assembly.packetCount)
      // 起步阶段（游标未定）以首个组完的帧为锚点：真实接收端可随时加入码流
      if (nextDeliverSeq == -1L) nextDeliverSeq = assembly.frameSeq
      drainReady()
    }
  }

  private fun drainReady() {
    while (true) {
      val frame = readyQueue.remove(nextDeliverSeq) ?: break
      nextDeliverSeq++
      cursorMissingSince = 0
      onFrame(frame)
    }
  }

  /** 周期调用（建议 5~10ms）：驱动 NACK 请求与超时放弃 */
  fun tick() = synchronized(lock) { tickLocked() }

  private fun tickLocked() {
    val now = clock()
    // NACK：不完整帧在 nackDelayMs 后开始请求缺失包（同一装配体每 nackRepeatMs 一轮）
    for ((_, assembly) in assemblies) {
      val age = now - assembly.arrivedAt
      if (age < nackDelayMs) continue
      if (age > giveUpMs) continue
      if (now - assembly.lastNackedAt < nackRepeatMs) continue
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
        cursorMissingSince = 0
        drainReady()
      }
    }
    // 整帧全丢：游标停在无装配体的序号上（后面的帧已到），超过 giveUpMs 跳过
    if (nextDeliverSeq >= 0 && highestSeq > nextDeliverSeq && !assemblies.containsKey(nextDeliverSeq)) {
      if (cursorMissingSince == 0L) {
        cursorMissingSince = now
      } else if (now - cursorMissingSince > giveUpMs) {
        nextDeliverSeq++
        cursorMissingSince = 0
        drainReady()
      }
    } else {
      cursorMissingSince = 0
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
