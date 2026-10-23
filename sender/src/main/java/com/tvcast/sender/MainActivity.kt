package com.tvcast.sender

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tvcast.dlna.cp.DeviceDescriptionParser
import com.tvcast.dlna.cp.DlnaDevice
import com.tvcast.dlna.cp.SsdpSearch
import kotlin.concurrent.thread

/** 发现页：SSDP 自动发现 + 手动 IP 直连（组播受限网络的兜底） */
class MainActivity : AppCompatActivity() {

  companion object {
    /** 调试/自动化入口：adb am start --es debug_push_uri <content://…>，发现唯一设备后自动连接并推送 */
    var debugPushUri: Uri? = null
    private const val EXTRA_DEBUG_PUSH = "debug_push_uri"
  }

  private lateinit var listView: ListView
  private lateinit var status: TextView
  private lateinit var progress: ProgressBar
  private val devices = ArrayList<DlnaDevice>()
  private lateinit var adapter: ArrayAdapter<String>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    intent?.getStringExtra(EXTRA_DEBUG_PUSH)?.let { debugPushUri = Uri.parse(it) }
    status = findViewById(R.id.status)
    progress = findViewById(R.id.progress)
    listView = findViewById(R.id.device_list)
    adapter = ArrayAdapter(this, R.layout.item_device, devices.map { it.friendlyName })
    listView.adapter = adapter
    listView.setOnItemClickListener { _, _, position, _ ->
      devices.getOrNull(position)?.let { connectAndEnter(it) }
    }
    findViewById<Button>(R.id.btn_refresh).setOnClickListener { startDiscovery() }
    findViewById<Button>(R.id.btn_connect_ip).setOnClickListener {
      val text = findViewById<EditText>(R.id.manual_ip).text?.toString()?.trim().orEmpty()
      connectByAddress(text)
    }
  }

  override fun onResume() {
    super.onResume()
    if (!CastSession.connected) startDiscovery()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.getStringExtra(EXTRA_DEBUG_PUSH)?.let {
      debugPushUri = Uri.parse(it)
      startDiscovery()
    }
  }

  private fun startDiscovery() {
    progress.visibility = View.VISIBLE
    status.text = getString(R.string.searching)
    devices.clear()
    adapter.clear()
    thread(name = "ssdp-search") {
      val hits = try {
        SsdpSearch.search(waitMs = 3500)
      } catch (e: Exception) {
        emptyList()
      }
      val found = hits.mapNotNull { DeviceDescriptionParser.fetchAndParse(it.location ?: return@mapNotNull null) }
        .filter { it.castable }
      runOnUiThread {
        progress.visibility = View.GONE
        devices.addAll(found)
        adapter.addAll(found.map { it.friendlyName })
        adapter.notifyDataSetChanged()
        status.text =
          if (found.isEmpty()) getString(R.string.no_device) else getString(R.string.found_n, found.size)
        // 自动化：唯一设备 + 调试推送意图 → 直接连入
        if (found.size == 1 && debugPushUri != null) {
          connectAndEnter(found[0])
        }
      }
    }
  }

  /** 手动直连：支持 "192.168.1.5:49152" 或裸 IP（默认 80 端口） */
  private fun connectByAddress(text: String) {
    if (text.isEmpty()) return
    val hostPort = text.removePrefix("http://").removePrefix("https://").trimEnd('/')
    val parts = hostPort.split(":")
    val host = parts.getOrNull(0) ?: return
    val port = parts.getOrNull(1)?.toIntOrNull() ?: 80
    if (host.isEmpty()) return
    progress.visibility = View.VISIBLE
    status.text = getString(R.string.connecting)
    thread(name = "manual-connect") {
      val device = DeviceDescriptionParser.fetchAndParse("http://$host:$port/dd.xml")
      runOnUiThread {
        progress.visibility = View.GONE
        if (device != null && device.castable) {
          connectAndEnter(device)
        } else {
          status.text = getString(R.string.connect_failed)
          Toast.makeText(this, R.string.connect_failed, Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private fun connectAndEnter(device: DlnaDevice) {
    CastSession.connect(device)
    startActivity(Intent(this, RemoteActivity::class.java))
  }
}
