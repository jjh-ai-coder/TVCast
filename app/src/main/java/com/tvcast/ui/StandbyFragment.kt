package com.tvcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tvcast.R
import com.tvcast.core.CastService

/** 待机页：logo、设备名、IP 信息卡（视觉主角）、等待呼吸点、设置入口 */
class StandbyFragment : Fragment() {

  private var service: CastService? = null
  private val uiListener = Runnable { refresh() }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_standby, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    view.findViewById<Button>(R.id.btn_settings).setOnClickListener {
      (activity as? MainActivity)?.openSettings()
    }
    view.findViewById<TextView>(R.id.version_text).text = "v" + requireContext().packageManager
      .getPackageInfo(requireContext().packageName, 0).versionName
    // 呼吸点：提示"等待中"（电视待机页长期挂着，动效要克制）
    view.findViewById<View>(R.id.waiting_dot).startAnimation(
      AlphaAnimation(0.25f, 1f).apply {
        duration = 900
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
      },
    )
    // D-pad 默认焦点落在设置按钮
    view.post { view.findViewById<View>(R.id.btn_settings)?.requestFocus() }
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
    service = null
  }

  fun refresh() {
    val view = view ?: return
    val svc = service ?: (activity as? MainActivity)?.castService
    view.findViewById<TextView>(R.id.device_name).text = svc?.deviceName ?: "TVCast"
    val ip = svc?.ipText()
    val endpoint = svc?.endpointText()
    view.findViewById<TextView>(R.id.ip_address).text = ip ?: getString(R.string.standby_address_none)
    view.findViewById<TextView>(R.id.endpoint_url).text = endpoint ?: ""
    val state = view.findViewById<TextView>(R.id.network_state)
    if (ip != null) {
      state.text = getString(R.string.standby_ready)
      state.setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
    } else {
      state.text = getString(R.string.standby_network_off)
      state.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
    }
  }
}
