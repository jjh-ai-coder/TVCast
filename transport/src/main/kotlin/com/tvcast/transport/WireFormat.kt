package com.tvcast.transport

import java.nio.ByteBuffer

/** 线上数据包（已解码头 + 载荷引用） */
data class DataPacket(
  val frameSeq: Long,
  val packetSeq: Long,
  val packetIndex: Int,
  val packetCount: Int,
  val timestampMs: Long,
  val keyframe: Boolean,
  val payload: ByteArray,
  val offset: Int,
  val length: Int,
) {
  fun payloadCopy(): ByteArray = payload.copyOfRange(offset, offset + length)
}

/** 重组完成的完整视频帧 */
class CompletedFrame(
  val frameSeq: Long,
  val timestampMs: Long,
  val keyframe: Boolean,
  val bytes: ByteArray,
  val packetCount: Int,
)

/**
 * 数据包线格式（大端）：
 * ```
 * magic "TVCS"(4) ver(1) flags(1) frameSeq(4) packetSeq(4) packetIndex(2) packetCount(2) tsMs(8) payload(...)
 * ```
 * flags: bit0 帧首, bit1 帧尾, bit2 关键帧。头部 26 字节。
 */
object WireFormat {
  const val MAGIC_DATA = 0x54564353 // "TVCS"
  const val MAGIC_NACK = 0x54564E41 // "TVNA"
  const val MAGIC_BYE = 0x54564259 // "TVBY"
  const val VERSION = 1
  const val HEADER_SIZE = 26
  const val MAX_PAYLOAD = 1200
  const val MAX_DATAGRAM = HEADER_SIZE + MAX_PAYLOAD

  private const val FLAG_FRAME_START = 1
  private const val FLAG_FRAME_END = 2
  private const val FLAG_KEYFRAME = 4

  fun encodeData(
    frameSeq: Long,
    packetSeq: Long,
    packetIndex: Int,
    packetCount: Int,
    timestampMs: Long,
    keyframe: Boolean,
    payload: ByteArray,
    offset: Int,
    length: Int,
  ): ByteArray {
    require(length in 1..MAX_PAYLOAD) { "payload length $length out of range" }
    val buf = ByteBuffer.allocate(HEADER_SIZE + length)
    buf.putInt(MAGIC_DATA)
    buf.put(VERSION.toByte())
    buf.put(flags(packetIndex == 0, packetIndex == packetCount - 1, keyframe).toByte())
    buf.putInt(frameSeq.toInt())
    buf.putInt(packetSeq.toInt())
    buf.putShort(packetIndex.toShort())
    buf.putShort(packetCount.toShort())
    buf.putLong(timestampMs)
    buf.put(payload, offset, length)
    return buf.array()
  }

  fun decodeData(data: ByteArray, length: Int): DataPacket? {
    if (length < HEADER_SIZE) return null
    val buf = ByteBuffer.wrap(data, 0, length)
    if (buf.int != MAGIC_DATA) return null
    val version = buf.get().toInt()
    if (version != VERSION) return null
    val flags = buf.get().toInt()
    val frameSeq = buf.int.toLong() and 0xFFFFFFFFL
    val packetSeq = buf.int.toLong() and 0xFFFFFFFFL
    val packetIndex = buf.short.toInt() and 0xFFFF
    val packetCount = buf.short.toInt() and 0xFFFF
    val timestampMs = buf.long
    if (packetCount <= 0 || packetIndex >= packetCount) return null
    val payloadLength = length - HEADER_SIZE
    val payload = data
    val offset = HEADER_SIZE
    val expectedEnd = if (packetIndex == packetCount - 1) 1 else 0
    // 尾包允许任意非零长度；非尾包必须满载（分片规则），否则视为损坏
    if (expectedEnd == 0 && payloadLength != MAX_PAYLOAD) return null
    if (payloadLength <= 0) return null
    return DataPacket(
      frameSeq, packetSeq, packetIndex, packetCount, timestampMs,
      keyframe = (flags and FLAG_KEYFRAME) != 0,
      payload = payload, offset = offset, length = payloadLength,
    )
  }

  private fun flags(start: Boolean, end: Boolean, keyframe: Boolean): Int {
    var f = 0
    if (start) f = f or FLAG_FRAME_START
    if (end) f = f or FLAG_FRAME_END
    if (keyframe) f = f or FLAG_KEYFRAME
    return f
  }

  /** NACK 包：magic(4) count(2) + count × (frameSeq u32, packetIndex u16) */
  fun encodeNack(entries: List<Pair<Long, Int>>): ByteArray {
    val capped = entries.take(NACK_MAX_ENTRIES)
    val buf = ByteBuffer.allocate(4 + 2 + capped.size * 6)
    buf.putInt(MAGIC_NACK)
    buf.putShort(capped.size.toShort())
    for ((frameSeq, index) in capped) {
      buf.putInt(frameSeq.toInt())
      buf.putShort(index.toShort())
    }
    return buf.array()
  }

  fun decodeNack(data: ByteArray, length: Int): List<Pair<Long, Int>> {
    if (length < 6) return emptyList()
    val buf = ByteBuffer.wrap(data, 0, length)
    if (buf.int != MAGIC_NACK) return emptyList()
    val count = buf.short.toInt() and 0xFFFF
    if (count > NACK_MAX_ENTRIES) return emptyList()
    val result = ArrayList<Pair<Long, Int>>(count)
    repeat(count) {
      if (buf.remaining() < 6) return result
      val frameSeq = buf.int.toLong() and 0xFFFFFFFFL
      val index = buf.short.toInt() and 0xFFFF
      result += frameSeq to index
    }
    return result
  }

  fun encodeBye(): ByteArray = ByteBuffer.allocate(4).putInt(MAGIC_BYE).array()

  fun isBye(data: ByteArray, length: Int): Boolean =
    length == 4 && ByteBuffer.wrap(data).int == MAGIC_BYE

  const val NACK_MAX_ENTRIES = 64
}
