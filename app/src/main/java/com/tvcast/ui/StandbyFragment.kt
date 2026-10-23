package com.tvcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tvcast.R
import com.tvcast.core.CastService

/** 待机页：设备名、等待投屏、网络状态、保活引导、设置入口 */
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
    val endpoint = svc?.endpointText()
    view.findViewById<TextView>(R.id.network_state).text =
      if (endpoint != null) getString(R.string.standby_network_ok, endpoint)
      else getString(R.string.standby_network_off)
  }
}
