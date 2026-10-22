package com.tvcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.tvcast.R
import com.tvcast.core.CastService

/** 设置页：修改设备名（改名即重建描述重宣告）、开机自启开关 */
class SettingsFragment : Fragment() {

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_settings, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val service = (activity as? MainActivity)?.castService
    val nameEdit = view.findViewById<EditText>(R.id.device_name_edit)
    val autoStartSwitch = view.findViewById<Switch>(R.id.auto_start_switch)
    nameEdit.setText(service?.deviceName ?: "")
    autoStartSwitch.isChecked = service?.let { com.tvcast.core.Settings(requireContext()).autoStart } ?: true

    view.findViewById<Button>(R.id.btn_save).setOnClickListener {
      val name = nameEdit.text?.toString()?.trim().orEmpty()
      if (name.isNotEmpty()) {
        service?.renameDevice(name)
      }
      service?.setAutoStart(autoStartSwitch.isChecked)
      Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
      (activity as? MainActivity)?.closeSettings()
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
