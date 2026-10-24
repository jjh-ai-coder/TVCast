package com.tvcast.sender

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
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
  private lateinit var emptyState: View
  private val devices = ArrayList<DlnaDevice>()
  private lateinit var adapter: DeviceAdapter

  /** 两行卡片行：设备名 + IP:端口（原来只有一行名字） */
  private inner class DeviceAdapter : BaseAdapter() {
    override fun getCount(): Int = devices.size
    override fun getItem(position: Int): DlnaDevice = devices[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val v = convertView ?: layoutInflater.inflate(R.layout.item_device, parent, false)
      val d = devices[position]
      v.findViewById<TextView>(R.id.device_name).text = d.friendlyName
      val uri = Uri.parse(d.locationUrl)
      v.findViewById<TextView>(R.id.device_addr).text =
        "${uri.host ?: "?"}:${if (uri.port > 0) uri.port else 80}"
      return v
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    intent?.getStringExtra(EXTRA_DEBUG_PUSH)?.let { debugPushUri = Uri.parse(it) }
    status = findViewById(R.id.status)
    progress = findViewById(R.id.progress)
    emptyState = findViewById(R.id.empty_state)
    findViewById<TextView>(R.id.version_text).text = "v" + packageManager
      .getPackageInfo(packageName, 0).versionName
    listView = findViewById(R.id.device_list)
    adapter = DeviceAdapter()
    listView.adapter = adapter
    listView.setOnItemClickListener { _, _, position, _ ->
      devices.getOrNull(position)?.let { connectAndEnter(it) }
    }
    findViewById<View>(R.id.btn_refresh).setOnClickListener { startDiscovery() }
    findViewById<View>(R.id.btn_empty_refresh).setOnClickListener { startDiscovery() }
    findViewById<View>(R.id.btn_mirror).setOnClickListener {
      startActivity(Intent(this, MirrorActivity::class.java))
    }
    // 图标统一代码设置（XML android:icon 在 MaterialButton 自动 inflation 下不稳定）
    (findViewById<View>(R.id.btn_refresh) as com.google.android.material.button.MaterialButton)
      .setIconResource(R.drawable.ic_refresh)
    (findViewById<View>(R.id.btn_empty_refresh) as com.google.android.material.button.MaterialButton)
      .setIconResource(R.drawable.ic_refresh)
    (findViewById<View>(R.id.btn_mirror) as com.google.android.material.button.MaterialButton)
      .setIconResource(R.drawable.ic_cast)
    (findViewById<View>(R.id.btn_connect_ip) as com.google.android.material.button.MaterialButton)
      .setIconResource(R.drawable.ic_link)
    findViewById<View>(R.id.btn_connect_ip).setOnClickListener {
      val text = findViewById<EditText>(R.id.manual_ip).text?.toString()?.trim().orEmpty()
      connectByAddress(text)
    }
    // 调试/自动化：已连接时跳过发现直接进遥控页推送
    forwardIfConnected()
  }

  override fun onResume() {
    super.onResume()
    if (!CastSession.connected) startDiscovery()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.getStringExtra(EXTRA_DEBUG_PUSH)?.let {
      debugPushUri = Uri.parse(it)
      if (!forwardIfConnected()) startDiscovery()
    }
  }

  /** 已连接且带调试推送意图 → 直接进遥控页；@return 是否已转发 */
  private fun forwardIfConnected(): Boolean {
    if (debugPushUri != null && CastSession.connected) {
      startActivity(Intent(this, RemoteActivity::class.java))
      return true
    }
    return false
  }

  private fun startDiscovery() {
    progress.visibility = View.VISIBLE
    emptyState.visibility = View.GONE
    status.text = getString(R.string.searching)
    devices.clear()
    adapter.notifyDataSetChanged()
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
        adapter.notifyDataSetChanged()
        renderListState()
        status.text =
          if (found.isEmpty()) getString(R.string.no_device) else getString(R.string.found_n, found.size)
        // 自动化：唯一设备 + 调试推送意图 → 直接连入
        if (found.size == 1 && debugPushUri != null) {
          connectAndEnter(found[0])
        }
      }
    }
  }

  private fun renderListState() {
    val searching = progress.visibility == View.VISIBLE
    emptyState.visibility = if (devices.isEmpty() && !searching) View.VISIBLE else View.GONE
    listView.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
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
    emptyState.visibility = View.GONE
    status.text = getString(R.string.connecting)
    thread(name = "manual-connect") {
      val device = DeviceDescriptionParser.fetchAndParse("http://$host:$port/dd.xml")
      runOnUiThread {
        progress.visibility = View.GONE
        if (device != null && device.castable) {
          connectAndEnter(device)
        } else {
          status.text = getString(R.string.connect_failed)
        }
      }
    }
  }

  private fun connectAndEnter(device: DlnaDevice) {
    CastSession.connect(device)
    startActivity(Intent(this, RemoteActivity::class.java))
  }
}
