package com.tvcast.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketizerDeframerTest {

  @Test
  fun `fully lost frame is skipped so delivery never stalls`() {
    val delivered = ArrayList<CompletedFrame>()
    var now = 0L
    val deframer = FrameDeframer(
      onFrame = { delivered.add(it) },
      onMissing = { _, _ -> },
      nackDelayMs = 4,
      giveUpMs = 200,
      clock = { now },
    )

    fun send(seq: Long, size: Int = 3000) {
      FramePacketizer().packetize(seq, timestampMs = now, keyframe = seq % 2 == 0L, frame = ByteArray(size)) { d ->
        deframer.receive(WireFormat.decodeData(d, d.size)!!)
      }
    }

    send(0)            // 正常交付，游标停在 1
    send(2); send(3)   // 帧 1 整帧全丢（一个包都没到）
    var t = 5L
    while (t <= 700) {
      now = t
      deframer.tick()  // 游标经 giveUpMs 后跳过 1，2/3 缓存于就绪队列随后按序交付，不卡死
      t += 5
    }
    assertEquals(listOf(0L, 2L, 3L), delivered.map { it.frameSeq })

    now = 720
    send(4)            // 后续新帧仍能按序交付
    deframer.tick()
    assertEquals(listOf(0L, 2L, 3L, 4L), delivered.map { it.frameSeq })
  }

  /** 组装：帧 → 分片 → （可选乱序/丢包）→ 重组，返回交付的帧 */
  private fun runFrames(
    frames: List<Pair<Boolean, ByteArray>>,
    dropIndexes: Set<Int> = emptySet(),
    shuffle: Boolean = false,
    duplicates: Boolean = false,
    nackHandler: ((frameSeq: Long, index: Int) -> Unit)? = null,
  ): List<CompletedFrame> {
    val delivered = ArrayList<CompletedFrame>()
    val deframer = FrameDeframer(
      onFrame = { delivered.add(it) },
      onMissing = { frameSeq, index -> nackHandler?.invoke(frameSeq, index) },
    )
    var globalPacketSeq = 0L
    val ring = SendRing()
    frames.forEachIndexed { frameIndex, (keyframe, bytes) ->
      val frameSeq = frameIndex.toLong()
      val packets = ArrayList<Pair<Long, ByteArray>>()
      FramePacketizer().packetize(frameSeq, 1000L + frameIndex, keyframe, bytes) { datagram ->
        packets += globalPacketSeq to datagram
        globalPacketSeq++
      }
      if (packets.isNotEmpty()) ring.indexFrame(frameSeq, packets.first().first)
      var order: List<Pair<Long, ByteArray>> = packets
      if (shuffle) order = order.shuffled()
      for ((_, datagram) in order) {
        val decoded = WireFormat.decodeData(datagram, datagram.size)!!
        if (decoded.packetIndex in dropIndexes) continue
        if (duplicates) deframer.receive(decoded)
        deframer.receive(decoded)
      }
      ring.let { /* ring 已建索引 */ }
    }
    // 驱动 NACK：不完整帧到达超过 nackDelayMs 后 tick 才会请求
    if (nackHandler != null) {
      Thread.sleep(15)
      deframer.tick()
    }
    return delivered
  }

  @Test
  fun roundTripVariousSizes() {
    val sizes = listOf(1, 100, 1200, 1201, 3600, 1200 * 10 + 7)
    val frames = sizes.map { false to ByteArray(it) { i -> (i % 251).toByte() } }
    val delivered = runFrames(frames)
    assertEquals(sizes.size, delivered.size)
    frames.forEachIndexed { index, (_, bytes) ->
      assertArrayEquals("frame $index", bytes, delivered[index].bytes)
      assertEquals(1000L + index, delivered[index].timestampMs)
    }
  }

  @Test
  fun emptyFrameIsSkipped() {
    val delivered = runFrames(listOf(false to ByteArray(0), true to ByteArray(50)))
    assertEquals(1, delivered.size)
    assertEquals(50, delivered[0].bytes.size)
  }

  @Test
  fun `outOfOrderAndDuplicates tolerated`() {
    val payload = ByteArray(3600) { (it * 7).toByte() }
    val delivered = runFrames(listOf(true to payload), shuffle = true, duplicates = true)
    assertEquals(1, delivered.size)
    assertArrayEquals(payload, delivered[0].bytes)
  }

  @Test
  fun keyframeFlagSurvives() {
    val delivered = runFrames(listOf(true to ByteArray(10), false to ByteArray(10)))
    assertTrue(delivered[0].keyframe)
    assertTrue(!delivered[1].keyframe)
  }

  @Test
  fun nackFiresForMissingPackets() {
    val payload = ByteArray(1200 * 4) { 0x55 }
    val nacks = ArrayList<Pair<Long, Int>>()
    // 丢 1、3 号包（0 基）：交付应失败，NACK 应覆盖缺失包
    val delivered = runFrames(listOf(true to payload), dropIndexes = setOf(1, 3), nackHandler = { f, i -> nacks += f to i })
    assertEquals(0, delivered.size)
    assertTrue(nacks.contains(0L to 1))
    assertTrue(nacks.contains(0L to 3))
  }

  // ---- 线格式边界 ----

  @Test
  fun decodeRejectsGarbage() {
    assertNull(WireFormat.decodeData(ByteArray(10), 10))
    val bad = ByteArray(WireFormat.HEADER_SIZE)
    assertNull(WireFormat.decodeData(bad, bad.size)) // magic 不符
    assertTrue(WireFormat.decodeNack(ByteArray(3), 3).isEmpty()) // 过短
  }

  @Test
  fun nackWireRoundTrip() {
    val entries = listOf(7L to 1, 9L to 3, 0xFFFFFFFFL to 65535)
    val decoded = WireFormat.decodeNack(WireFormat.encodeNack(entries), WireFormat.encodeNack(entries).size)
    assertEquals(entries, decoded)
  }

  @Test
  fun byeDetection() {
    val bye = WireFormat.encodeBye()
    assertTrue(WireFormat.isBye(bye, bye.size))
    assertTrue(!WireFormat.isBye(ByteArray(4), 4))
  }

  // ---- SendRing 与帧索引 ----

  @Test
  fun sendRingLookupAndIndex() {
    val ring = SendRing()
    val p0 = ByteArray(WireFormat.HEADER_SIZE + 2)
    val p1 = ByteArray(WireFormat.HEADER_SIZE + 2)
    ring.offer(p0, 100)
    ring.offer(p1, 101)
    ring.indexFrame(5L, 100L)
    assertEquals(100L, ring.firstPacketSeqOf(5L))
    assertArrayEquals(p0, ring.lookup(100L))
    assertArrayEquals(p1, ring.lookup(101L))
    assertNull(ring.lookup(999L))
  }

  @Test
  fun sendRingEvictsOldest() {
    val ring = SendRing(capacity = 4)
    repeat(10) { ring.offer(ByteArray(4), it.toLong()) }
    assertNull(ring.lookup(3L))
    assertNotNull(ring.lookup(9L))
    assertEquals(4, ring.size())
  }
}
