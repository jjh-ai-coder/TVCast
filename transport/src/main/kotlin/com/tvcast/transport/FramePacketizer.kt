package com.tvcast.transport

/**
 * 发送端分片器：把一帧编码数据切成多个数据包（全局递增 packetSeq 供 NACK 定位）。
 * 线程约束：单线程调用（发送循环）。
 */
class FramePacketizer(private val maxPayload: Int = WireFormat.MAX_PAYLOAD) {

  private var packetSeq = 0L

  fun packetize(frameSeq: Long, timestampMs: Long, keyframe: Boolean, frame: ByteArray, out: (ByteArray) -> Unit) {
    if (frame.isEmpty()) return
    val packetCount = (frame.size + maxPayload - 1) / maxPayload
    var offset = 0
    for (index in 0 until packetCount) {
      val length = minOf(maxPayload, frame.size - offset)
      val datagram = WireFormat.encodeData(
        frameSeq, nextPacketSeq(), index, packetCount, timestampMs, keyframe, frame, offset, length,
      )
      out(datagram)
      offset += length
    }
  }

  private fun nextPacketSeq(): Long {
    val seq = packetSeq
    packetSeq = (packetSeq + 1) and 0xFFFFFFFFL
    return seq
  }
}
