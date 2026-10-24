package com.tvcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tvcast.R
import com.tvcast.core.CastService

/** 屏幕镜像页：全屏 SurfaceView 渲染解码输出 + 状态角标；返回键退出镜像（反发 BYE） */
class MirrorFragment : Fragment() {

  private var service: CastService? = null
  private val uiListener = Runnable { refresh() }

  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_mirror, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    service = (activity as? MainActivity)?.castService
    view.findViewById<SurfaceView>(R.id.mirror_surface).holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        android.util.Log.i("TVCast/MirrorFrag", "surface created ${holder.surface}")
        service?.mirrorReceiver?.decoder?.setSurface(holder.surface)
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        service?.mirrorReceiver?.decoder?.setSurface(null)
      }
    })
  }

  override fun onStart() {
    super.onStart()
    service = (activity as? MainActivity)?.castService
    service?.addUiListener(uiListener)
    refresh()
  }

  override fun onStop() {
    super.onStop()
    service?.removeUiListener(uiListener)
  }

  fun refresh() {
    val view = view ?: return
    val receiver = (activity as? MainActivity)?.castService?.mirrorReceiver ?: return
    view.findViewById<TextView>(R.id.mirror_stats).text = receiver.statsText
    // 按镜像内容宽高比调整 Surface（竖屏手机投横屏电视 → 居中信箱）
    val info = receiver.decoder.currentInfo() ?: return
    val surface = view.findViewById<SurfaceView>(R.id.mirror_surface)
    val parentW = view.width.takeIf { it > 0 } ?: return
    val parentH = view.height.takeIf { it > 0 } ?: return
    val scale = minOf(parentW.toFloat() / info.width, parentH.toFloat() / info.height)
    val lp = surface.layoutParams
    val targetW = (info.width * scale).toInt()
    val targetH = (info.height * scale).toInt()
    if (lp.width != targetW || lp.height != targetH) {
      lp.width = targetW
      lp.height = targetH
      surface.layoutParams = lp
    }
  }
}
