package com.tvcast.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 有损回环集成测试：真实 UDP socket + 5% 模拟丢包 + NACK 重传。
 * 验收：100 帧全部按序交付、无重复、延迟 P95 < 500ms（M-A 门槛的 JVM 侧等价验证）。
 */
class LossyLoopbackTest {

  @Test
  fun `lossy loopback delivers all frames in order`() {
    val seed = 42L
    val lossRate = 0.05
    val frameCount = 100
    val delivered = Collections.synchronizedList(ArrayList<CompletedFrame>())
    val latencies = Collections.synchronizedList(ArrayList<Long>())
    val allDone = CountDownLatch(1)

    val senderSocket = DatagramSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }
    val receiverSocket = DatagramSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }
    val receiverAddress = InetSocketAddress(InetAddress.getByName("127.0.0.1"), receiverSocket.localPort)
    val senderAddress = InetSocketAddress(InetAddress.getByName("127.0.0.1"), senderSocket.localPort)

    val rng = Random(seed)

    // ---- 发送端 ----
    val senderDeframer = FrameDeframer(onFrame = { /* 发送端不收数据帧 */ })
    val senderLink = UdpMirrorLink(
      socket = senderSocket,
      remote = receiverAddress,
      deframer = senderDeframer,
      lossSimulator = { rng.nextDouble() < lossRate }, // 数据/NACK 重传统一模拟丢包
    )
    senderLink.startReceiveLoop() // 处理 NACK → 环缓存重发

    // ---- 接收端 ----
    val receiverDeframer = FrameDeframer(
      onFrame = { frame ->
        delivered.add(frame)
        synchronized(latencies) { latencies.add(System.currentTimeMillis() - frame.timestampMs) }
        if (delivered.size >= frameCount) allDone.countDown()
      },
      onMissing = { frameSeq, index ->
        val nack = WireFormat.encodeNack(listOf(frameSeq to index))
        receiverSocket.send(DatagramPacket(nack, nack.size, senderAddress))
      },
      nackDelayMs = 4,
      giveUpMs = 500,
    )
    // 单线程约束：接收与 tick 都走接收引擎线程
    val engine = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
      Thread(r, "rx-engine").apply { isDaemon = true }
    }
    val receiverLink = UdpMirrorLink(socket = receiverSocket, remote = senderAddress, deframer = receiverDeframer)
    receiverLink.startReceiveLoop()
    engine.execute {
      val deadline = System.currentTimeMillis() + 60_000
      while (System.currentTimeMillis() < deadline && delivered.size < frameCount) {
        receiverDeframer.tick()
        Thread.sleep(5)
      }
    }

    // ---- 发送 100 帧，每帧 ~30KB（模拟 1080p 码率下的帧大小）----
    val senderThread = Thread {
      var timestamp = System.currentTimeMillis()
      repeat(frameCount) { i ->
        val size = if (i % 10 == 0) 60_000 else 30_000 // 关键帧更大
        val frame = ByteArray(size) { ((it + i) % 253).toByte() }
        senderLink.sendFrame(timestampMs = timestamp, keyframe = i % 10 == 0, frame = frame)
        timestamp += 33
        Thread.sleep(8) // ~30fps 节奏
      }
    }
    senderThread.start()
    senderThread.join()

    assertTrue("30s 内未收齐全部帧（收到 ${delivered.size}）", allDone.await(30, TimeUnit.SECONDS))
    engine.shutdownNow()

    // ---- 断言：全部、按序、无重复 ----
    assertEquals(frameCount, delivered.size)
    val seqs = delivered.map { it.frameSeq }
    assertEquals(seqs.sorted(), seqs) // 严格按序
    assertEquals((0L until frameCount.toLong()).toList(), seqs)
    for (frame in delivered) {
      val expectedSize = if (frame.frameSeq % 10 == 0L) 60_000 else 30_000
      assertEquals(expectedSize, frame.bytes.size)
    }
    // 延迟：P95 < 500ms（回环 + NACK 往返，量级应为个位数 ms）
    val sorted = synchronized(latencies) { latencies.sorted() }
    val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
    assertTrue("P95 延迟 ${p95}ms 超标", p95 < 500)
    println("lossy loopback: ${delivered.size} frames, P95 latency ${p95}ms, max ${sorted.last()}ms")
  }
}
