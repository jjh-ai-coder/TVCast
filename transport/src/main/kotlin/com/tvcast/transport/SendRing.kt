package com.tvcast.transport

import java.util.ArrayDeque

/**
 * 发送端重传缓存：按全局 packetSeq 保留最近的已发包，收到 NACK 时取出发送。
 * 附带帧索引（frameSeq → 首包 packetSeq），用于把 NACK 的 (frameSeq, packetIndex)
 * 换算成全局 packetSeq。
 * 线程约束：单线程（发送循环）调用 offer/lookup/resendFor；多线程时外部同步。
 */
class SendRing(private val capacity: Int = 2048) {

  private class Entry(val packetSeq: Long, val datagram: ByteArray)

  private class FrameEntry(val frameSeq: Long, val firstPacketSeq: Long)

  private val ring = ArrayDeque<Entry>(capacity)
  private val frameIndex = ArrayDeque<FrameEntry>(capacity)
  private var lowestSeq = 0L
  private var highestSeq = -1L

  fun offer(datagram: ByteArray, packetSeq: Long) {
    ring.addLast(Entry(packetSeq, datagram))
    if (ring.size > capacity) {
      ring.removeFirst()
      lowestSeq = ring.peekFirst().packetSeq
    }
    highestSeq = packetSeq
  }

  /** 每帧第一个包发送时登记（frameSeq → 首包 seq） */
  fun indexFrame(frameSeq: Long, firstPacketSeq: Long) {
    frameIndex.addLast(FrameEntry(frameSeq, firstPacketSeq))
    while (frameIndex.size > capacity / 4) frameIndex.removeFirst()
  }

  fun firstPacketSeqOf(frameSeq: Long): Long? =
    frameIndex.firstOrNull { it.frameSeq == frameSeq }?.firstPacketSeq

  /** @return 命中的数据包（可能为 null：已滚出窗口或从未见过） */
  fun lookup(packetSeq: Long): ByteArray? {
    if (packetSeq < lowestSeq || packetSeq > highestSeq) return null
    for (entry in ring) {
      if (entry.packetSeq == packetSeq) return entry.datagram
    }
    return null
  }

  fun clear() {
    ring.clear()
    frameIndex.clear()
    lowestSeq = 0L
    highestSeq = -1L
  }

  fun size(): Int = ring.size
}
