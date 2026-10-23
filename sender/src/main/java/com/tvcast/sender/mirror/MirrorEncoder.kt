package com.tvcast.sender.mirror

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * H.264 硬件编码器（Surface 输入直通）：VirtualDisplay 直接渲染到 [inputSurface]，
 * 免去 ImageReader RGBA 拷贝。编码线程取出帧回调 [onFrame]（时间戳为发送时刻，
 * 用于链路统计而非解码同步）。
 *
 * 关键帧处理：SPS/PPS（CSD）缓存后前置到每个关键帧，接收端可随时加入码流；
 * 码率档位切换通过 [setBitrate] 热更新（无需重启编码器）。
 */
class MirrorEncoder(
  val width: Int,
  val height: Int,
  initialBitrateBps: Int,
  private val onFrame: (timestampMs: Long, keyframe: Boolean, bytes: ByteArray) -> Unit,
  private val onError: (Exception) -> Unit,
) {

  companion object {
    private const val TAG = "TVCast/MirrorEnc"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL_SEC = 2
  }

  private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
  @Volatile private var released = false
  private var csd = ByteArray(0)
  private var drainThread: Thread? = null

  /** 供 VirtualDisplay 渲染的输入 Surface（configure 后创建，编码器 release 时一并释放） */
  val inputSurface: Surface

  init {
    val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
      setInteger(MediaFormat.KEY_BIT_RATE, initialBitrateBps)
      setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
      setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = codec.createInputSurface()
  }

  fun start() {
    codec.start()
    drainThread = Thread(::drainLoop, "tvcast-mirror-enc").apply {
      isDaemon = true
      start()
    }
  }

  private fun drainLoop() {
    val info = MediaCodec.BufferInfo()
    while (!released) {
      try {
        val index = codec.dequeueOutputBuffer(info, 10_000L)
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          // Surface 输入的编码器多以 format-changed 形式交付 CSD
          csd = readCsdFromFormat(codec.outputFormat)
          continue
        }
        if (index >= 0) {
          val buffer = codec.getOutputBuffer(index)
          if (buffer != null && info.size > 0) {
            val chunk = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.get(chunk)
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            @Suppress("DEPRECATION")
            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
            when {
              config -> csd = chunk
              keyframe -> onFrame(SystemClock.elapsedRealtime(), true, csd + chunk)
              else -> onFrame(SystemClock.elapsedRealtime(), false, chunk)
            }
          }
          codec.releaseOutputBuffer(index, false)
        }
      } catch (e: Exception) {
        if (!released) {
          Log.e(TAG, "encode drain failed", e)
          onError(e)
        }
        break
      }
    }
  }

  private fun readCsdFromFormat(format: MediaFormat): ByteArray {
    val parts = ArrayList<ByteArray>()
    for (key in listOf("csd-0", "csd-1")) {
      val buf = format.getByteBuffer(key) ?: continue
      val copy = ByteArray(buf.remaining())
      buf.get(copy)
      parts += copy
    }
    return parts.reduce { acc, part -> acc + part }
  }

  /** 热更新码率（档位切换），失败不影响会话 */
  fun setBitrate(bps: Int) {
    if (released) return
    try {
      val params = Bundle()
      params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
      codec.setParameters(params)
    } catch (e: Exception) {
      Log.w(TAG, "setBitrate($bps) failed", e)
    }
  }

  /** 请求立即编出一个关键帧（接收端加入/恢复用） */
  fun requestKeyframe() {
    if (released) return
    try {
      val params = Bundle()
      params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
      codec.setParameters(params)
    } catch (e: Exception) {
      Log.w(TAG, "requestKeyframe failed", e)
    }
  }

  fun release() {
    if (released) return
    released = true
    try { codec.stop() } catch (_: Exception) {}
    try { inputSurface.release() } catch (_: Exception) {}
    try { codec.release() } catch (_: Exception) {}
    drainThread?.join(1000)
    drainThread = null
  }
}
