package com.tvcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.tvcast.R
import com.tvcast.core.CastService

/** 设置页：修改设备名（改名即重建描述重宣告）、开机自启开关；卡片式表单，TV 可聚焦 */
class SettingsFragment : Fragment() {

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_settings, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val service = (activity as? MainActivity)?.castService
    val nameEdit = view.findViewById<EditText>(R.id.device_name_edit)
    val autoStartSwitch = view.findViewById<SwitchCompat>(R.id.auto_start_switch)
    nameEdit.setText(service?.deviceName ?: "")
    autoStartSwitch.isChecked = service?.let { com.tvcast.core.Settings(requireContext()).autoStart } ?: true

    // 整行点击切换（D-pad 选中卡片按 OK 即可，无需精确点中开关）
    view.findViewById<View>(R.id.auto_start_row).setOnClickListener {
      autoStartSwitch.isChecked = !autoStartSwitch.isChecked
    }
    // D-pad 进页默认焦点落在输入框（bg_input 聚焦态高亮可见；TV 上聚焦不自动弹输入法）
    view.post { nameEdit.requestFocus() }

    view.findViewById<Button>(R.id.btn_save).setOnClickListener {
      val name = nameEdit.text?.toString()?.trim().orEmpty()
      if (name.isNotEmpty()) {
        service?.renameDevice(name)
      }
      service?.setAutoStart(autoStartSwitch.isChecked)
      // inline 反馈替代 Toast（TV 上 Toast 位置随意且不跟焦点），短暂展示后回待机
      view.findViewById<TextView>(R.id.saved_feedback).visibility = View.VISIBLE
      view.postDelayed({ (activity as? MainActivity)?.closeSettings() }, 600)
    }
    view.findViewById<Button>(R.id.btn_back).setOnClickListener {
      (activity as? MainActivity)?.closeSettings()
    }
    view.findViewById<TextView>(R.id.oss_licenses).setOnClickListener {
      AlertDialog.Builder(requireContext())
        .setTitle(R.string.settings_oss_licenses)
        .setMessage(R.string.oss_licenses_text)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    }
  }
}
