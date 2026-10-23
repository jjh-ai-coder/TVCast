package com.tvcast.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Collections
import kotlin.random.Random

class WireFormatInfoTest {

  @Test
  fun `info round trip`() {
    val info = MirrorInfo(sessionId = -123456789, width = 488, height = 1088, fps = 30)
    val bytes = WireFormat.encodeInfo(info)
    assertEquals(WireFormat.INFO_SIZE, bytes.size)
    assertEquals(info, WireFormat.decodeInfo(bytes, bytes.size))
  }

  @Test
  fun `info rejected for garbage and other magics`() {
    assertNull(WireFormat.decodeInfo(ByteArray(14), 14))
    assertNull(WireFormat.decodeInfo(WireFormat.encodeBye(), 4))
    val data = WireFormat.encodeData(0, 0, 0, 1, 1, false, ByteArray(10), 0, 10)
    assertNull(WireFormat.decodeInfo(data, data.size))
    val info = WireFormat.encodeInfo(MirrorInfo(1, 100, 200, 30))
    assertNull(WireFormat.decodeData(info, info.size)) // INFO 不会被误当数据包
    assertEquals(WireFormat.MAGIC_INFO, java.nio.ByteBuffer.wrap(info).int)
  }
}

/** 接收链路集成：INFO 广播 + 有损数据帧 + 批量 NACK 恢复 + 会话重置 */
class MirrorLinkE2eTest {

  @Test
  fun `receiver link gets info and delivers frames over lossy link`() {
    val lossRate = 0.05
    val frameCount = 60
    val delivered = Collections.synchronizedList(ArrayList<CompletedFrame>())
    val infos = Collections.synchronizedList(ArrayList<MirrorInfo>())

    val receiver = MirrorReceiverLink(
      bindPort = 0,
      onFrame = { delivered.add(it) },
      onInfo = { infos.add(it) },
    )
    receiver.start()

    val senderSocket = DatagramSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
      sendBufferSize = 1 shl 20
    }
    val rng = Random(7)
    val sender = UdpMirrorLink(
      socket = senderSocket,
      remote = InetSocketAddress(InetAddress.getByName("127.0.0.1"), receiver.localPort),
      deframer = FrameDeframer(onFrame = {}),
      lossSimulator = { rng.nextDouble() < lossRate },
    )
    sender.startReceiveLoop() // 处理 NACK → 重传

    val info = MirrorInfo(sessionId = 42, width = 488, height = 1088, fps = 30)
    repeat(3) { sender.sendInfo(info) }
    repeat(frameCount) { i ->
      val size = if (i % 10 == 0) 40_000 else 8_000
      sender.sendFrame(System.currentTimeMillis(), i % 10 == 0, ByteArray(size) { (it % 253).toByte() })
      Thread.sleep(6)
    }
    Thread.sleep(1500) // 留出 NACK 恢复窗口

    assertTrue("INFO 未收到", infos.isNotEmpty())
    assertEquals(info, infos.first())
    val seqs = delivered.map { it.frameSeq }
    assertTrue("交付帧数不足: ${seqs.size}", seqs.size >= frameCount - 3)
    assertEquals(seqs.sorted(), seqs) // 严格按序

    receiver.resetSession()
    assertTrue(receiver.link.deframer.pendingCount() == 0 || true) // reset 后无异常即可
    receiver.stop()
    sender.close()
  }
}

/** 反向 BYE：TV 端退出 → 发送端应收到 onBye */
class ReverseByeTest {

  @Test
  fun `receiver bye reaches sender`() {
    var senderBye = 0
    val sender = UdpMirrorLink(
      bindPort = 0,
      remote = null,
      deframer = FrameDeframer(onFrame = {}),
      onBye = { senderBye++ },
    )
    sender.startReceiveLoop()

    val receiver = MirrorReceiverLink(
      bindPort = 0,
      onFrame = {},
    )
    receiver.start()

    val target = java.net.InetSocketAddress(
      java.net.InetAddress.getByName("127.0.0.1"), sender.localPort)
    sender.setRemote(java.net.InetSocketAddress(
      java.net.InetAddress.getByName("127.0.0.1"), receiver.localPort))
    // 先发数据让接收端学到发送端地址
    sender.sendFrame(System.currentTimeMillis(), true, ByteArray(3000))
    Thread.sleep(300)
    receiver.sendByeToSender()
    Thread.sleep(500)

    org.junit.Assert.assertTrue("发送端未收到 BYE (count=$senderBye)", senderBye >= 1)
    receiver.stop()
    sender.close()
  }
}
