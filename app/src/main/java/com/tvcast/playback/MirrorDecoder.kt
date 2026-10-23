package com.tvcast.playback

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.tvcast.transport.CompletedFrame
import com.tvcast.transport.MirrorInfo
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕镜像 H.264 解码器：等关键帧起步（从帧内提取 SPS/PPS 配置解码器），
 * 到帧即渲（不做 PTS 缓冲，镜像要低延迟）。
 * - INFO 变化（新 sessionId / 分辨率变化）或 Surface 重建 → 重置，重新等关键帧
 * - 帧序不连续（传输层跳帧）→ 丢弃后续 P 帧直到下一个关键帧，避免花屏
 * 线程：onInfo/onFrame 来自接收线程，setSurface 来自主线程，内部加锁。
 */
class MirrorDecoder(
  private val onError: (String) -> Unit = {},
) {

  companion object {
    private const val TAG = "TVCast/MirrorDec"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
  }

  private val lock = Any()
  private var info: MirrorInfo? = null
  private var surface: Surface? = null
  private var codec: MediaCodec? = null
  private var drainThread: Thread? = null
  private var lastSeq = -1L
  private var awaitKeyframe = true
  val decodedFrames = AtomicLong()
  val fedFrames = AtomicLong()

  /** 当前会话信息（UI 显示用） */
  fun currentInfo(): MirrorInfo? = synchronized(lock) { info }

  fun onInfo(newInfo: MirrorInfo) {
    synchronized(lock) {
      val old = info
      val changed = old == null ||
        old.sessionId != newInfo.sessionId ||
        old.width != newInfo.width || old.height != newInfo.height
      info = newInfo
      if (changed) {
        Log.i(TAG, "session info ${old ?: "none"} -> $newInfo, reset decoder")
        releaseLocked()
      }
    }
  }

  fun setSurface(newSurface: Surface?) {
    synchronized(lock) {
      surface = newSurface
      releaseLocked() // Surface 重建后需重新 configure
    }
  }

  /** 接收线程调用：投喂一帧 */
  fun onFrame(frame: CompletedFrame) {
    synchronized(lock) {
      val target = surface ?: return // UI 未就绪前静默丢弃，等下个关键帧起步
      val dims = info ?: return
      if (lastSeq != -1L && frame.frameSeq != lastSeq + 1) awaitKeyframe = true
      lastSeq = frame.frameSeq
      if (awaitKeyframe && !frame.keyframe) return
      if (codec == null && !configureLocked(frame, dims, target)) {
        val head = frame.bytes.take(12).joinToString("") { "%02X".format(it) }
        Log.w(TAG, "configure failed on keyframe seq=${frame.frameSeq} size=${frame.bytes.size} head=$head")
        return
      }
      awaitKeyframe = false
      feedLocked(frame)
    }
  }

  /** 从关键帧提取 SPS/PPS 配置并启动解码器；@return 是否成功 */
  private fun configureLocked(frame: CompletedFrame, dims: MirrorInfo, target: Surface): Boolean {
    val sps = findNal(frame.bytes, 7) ?: return false
    val pps = findNal(frame.bytes, 8) ?: return false
    // 部分 ROM 的解码器不接受 low-latency 键（configure 直接抛 CodecException），失败即回退普通模式
    var useLowLatency = true
    repeat(2) {
      val c = try {
        MediaCodec.createDecoderByType(MIME)
      } catch (e: Exception) {
        Log.w(TAG, "create decoder failed: ${e.message}")
        return false
      }
      try {
        val format = MediaFormat.createVideoFormat(MIME, dims.width, dims.height).apply {
          setByteBuffer("csd-0", ByteBuffer.wrap(sps))
          setByteBuffer("csd-1", ByteBuffer.wrap(pps))
          if (useLowLatency) setInteger("low-latency", 1)
        }
        c.configure(format, target, null, 0)
        c.start()
        codec = c
        val t = Thread({ drainLoop(c) }, "tvcast-mirror-dec")
        t.isDaemon = true
        t.start()
        drainThread = t
        Log.i(TAG, "decoder configured: ${dims.width}x${dims.height} lowLatency=$useLowLatency from keyframe seq=${frame.frameSeq}")
        return true
      } catch (e: Exception) {
        try { c.release() } catch (_: Exception) {}
        Log.w(TAG, "decoder configure failed (lowLatency=$useLowLatency): ${e.message}")
        useLowLatency = false // 回退重试一次
      }
    }
    return false
  }

  private fun feedLocked(frame: CompletedFrame) {
    val c = codec ?: return
    try {
      val index = c.dequeueInputBuffer(10_000)
      if (index < 0) return // 输入拥塞直接丢帧（镜像宁可丢不可积压）
      val buf = c.getInputBuffer(index) ?: return
      buf.clear()
      if (frame.bytes.size > buf.capacity()) {
        c.queueInputBuffer(index, 0, 0, 0, 0)
        return
      }
      buf.put(frame.bytes)
      c.queueInputBuffer(index, 0, frame.bytes.size, frame.timestampMs * 1000, 0)
      fedFrames.incrementAndGet()
    } catch (e: Exception) {
      Log.w(TAG, "feed failed: ${e.message}")
    }
  }

  private fun drainLoop(c: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    while (true) {
      try {
        val index = c.dequeueOutputBuffer(info, 10_000)
        if (index >= 0) {
          decodedFrames.incrementAndGet()
          c.releaseOutputBuffer(index, true)
        }
      } catch (_: Exception) {
        return // codec 已释放
      }
    }
  }

  /** 在 Annex-B 码流中找指定类型的 NAL 单元（含 00 00 01 起始码返回；末尾多余零字节无害） */
  private fun findNal(bytes: ByteArray, nalType: Int): ByteArray? {
    var i = 0
    while (i < bytes.size - 4) {
      if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
        val type = bytes[i + 3].toInt() and 0x1F
        var end = bytes.size
        var j = i + 3
        while (j < bytes.size - 3) {
          if (bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 1.toByte()) {
            end = j
            break
          }
          j++
        }
        if (type == nalType) return bytes.copyOfRange(i, end)
        i = end.coerceAtLeast(i + 4)
      } else {
        i++
      }
    }
    return null
  }

  fun release() = synchronized(lock) { releaseLocked() }

  private fun releaseLocked() {
    awaitKeyframe = true
    lastSeq = -1L
    codec?.let {
      try { it.stop() } catch (_: Exception) {}
      try { it.release() } catch (_: Exception) {}
    }
    codec = null
    drainThread = null
  }
}
