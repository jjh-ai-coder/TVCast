package com.tvcast.dlna.gena

import com.tvcast.dlna.http.HttpRequest
import com.tvcast.dlna.http.HttpResponse
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class GenaService { AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER }

/** NOTIFY 投递抽象：真机走 HttpURLConnection，测试用记录型 fake */
interface NotifySender {
    fun send(callbackUrl: String, sid: String, seq: Long, body: String): Boolean
}

class HttpNotifySender : NotifySender {
  /**
   * GENA NOTIFY 必须用非标准动词 NOTIFY——Android 的 HttpURLConnection
   * setRequestMethod 只认标准集合会抛 ProtocolException，故走原生 Socket 手写报文。
   */
  override fun send(callbackUrl: String, sid: String, seq: Long, body: String): Boolean {
    try {
      val url = URL(callbackUrl)
      if (url.protocol != "http") return false
      val host = url.host
      val port = if (url.port > 0) url.port else 80
      val path = url.path.ifEmpty { "/" } + (url.query?.let { "?$it" } ?: "")
      val bytes = body.toByteArray(Charsets.UTF_8)
      return Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = 5000
        val request = buildString {
          append("NOTIFY $path HTTP/1.1\r\n")
          append("HOST: $host:$port\r\n")
          append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
          append("NT: upnp:event\r\n")
          append("NTS: upnp:propchange\r\n")
          append("SID: $sid\r\n")
          append("SEQ: $seq\r\n")
          append("CONTENT-LENGTH: ${bytes.size}\r\n")
          append("CONNECTION: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
          write(request.toByteArray(Charsets.UTF_8))
          write(bytes)
          flush()
        }
        val statusLine = readLine(socket.getInputStream()) ?: return false
        Regex("HTTP/1\\.[01] (\\d{3})").find(statusLine)?.groupValues?.get(1)?.toInt() in 200..299
      }
    } catch (_: Exception) {
      return false
    }
  }

  private fun readLine(input: InputStream): String? {
    val sb = StringBuilder(64)
    while (sb.length < 1024) {
      val b = input.read()
      if (b == -1) break
      if (b == '\n'.code) break
      if (b != '\r'.code) sb.append(b.toChar())
    }
    return if (sb.isEmpty()) null else sb.toString()
  }
}

/**
 * GENA 订阅管理 + LastChange NOTIFY（spec §4.5）：
 * - 新订阅（CALLBACK+NT）回 200 带 SID/TIMEOUT，并立即投递 SEQ:0 初始全量快照
 * - 续订仅带 SID；退订 UNSUBSCRIBE；过期清理
 * - 单线程 executor 投递，保证同一 SID 的 SEQ 严格递增有序
 */
class EventBus(
    private val sender: NotifySender = HttpNotifySender(),
    private val executorOverride: Executor? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    class Subscription internal constructor(
        val sid: String,
        val service: GenaService,
        @Volatile var callbackUrl: String,
        @Volatile var expiresAtMs: Long,
        val seq: AtomicLong = AtomicLong(0L),
    )

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val snapshotProviders = ConcurrentHashMap<GenaService, () -> String>()

    @Volatile private var executor: Executor? = null
    @Volatile private var ownedExecutor: ExecutorService? = null
    @Volatile private var sweeper: ScheduledExecutorService? = null

    fun start() {
        if (executor == null) {
            val own = Executors.newSingleThreadExecutor { r ->
                Thread(r, "tvcast-gena").apply { isDaemon = true }
            }
            ownedExecutor = own
            executor = executorOverride ?: own
        }
        if (executorOverride == null && sweeper == null) {
            sweeper = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "tvcast-gena-sweep").apply { isDaemon = true }
            }.also {
                try {
                    it.scheduleWithFixedDelay({ purgeExpired() }, 60, 60, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        sweeper?.shutdownNow()
        sweeper = null
        ownedExecutor?.shutdownNow()
        ownedExecutor = null
        executor = null
        subscriptions.clear()
    }

    fun setSnapshotProvider(service: GenaService, provider: () -> String) {
        snapshotProviders[service] = provider
    }

    fun subscriptionCount(service: GenaService? = null): Int =
        subscriptions.values.count { service == null || it.service == service }

    fun handleSubscribe(service: GenaService, request: HttpRequest): HttpResponse {
        val sid = request.header("sid")
        val nt = request.header("nt")
        val callback = request.header("callback")
        if (sid != null) {
            // 续订：仅允许 SID，携带 NT/CALLBACK 属于不兼容请求
            if (nt != null || callback != null) return plain(400, "Incompatible headers")
            val sub = subscriptions[sid] ?: return plain(412, "Precondition Failed")
            if (sub.service != service) return plain(412, "Precondition Failed")
            val timeout = parseTimeout(request.header("timeout"))
            sub.expiresAtMs = clock() + timeout * 1000L
            return subscribeResponse(sub.sid, timeout)
        }
        if (callback == null || nt == null) return plain(412, "Precondition Failed")
        if (!nt.equals("upnp:event", ignoreCase = true)) return plain(400, "Incompatible headers")
        val callbackUrl = CALLBACK_URL.find(callback)?.groupValues?.get(1)
            ?: return plain(400, "Invalid CALLBACK")
        val timeout = parseTimeout(request.header("timeout"))
        val sub = Subscription("uuid:" + UUID.randomUUID(), service, callbackUrl, clock() + timeout * 1000L)
        subscriptions[sub.sid] = sub
        postInitialSnapshot(sub)
        return subscribeResponse(sub.sid, timeout)
    }

    fun handleUnsubscribe(service: GenaService, request: HttpRequest): HttpResponse {
        val sid = request.header("sid") ?: return plain(400, "Bad Request")
        val removed = subscriptions.remove(sid) ?: return plain(412, "Precondition Failed")
        return if (removed.service == service) plain(200, "OK") else plain(412, "Precondition Failed")
    }

    /** 状态变化时调用；bodyProvider 在投递线程上求值 */
    fun notify(service: GenaService, bodyProvider: () -> String) {
        val targets = subscriptions.values
            .filter { it.service == service && it.expiresAtMs > clock() }
        if (targets.isEmpty()) return
        submit {
            val body = try {
                bodyProvider()
            } catch (e: Exception) {
                ""
            }
            if (body.isEmpty()) return@submit
            for (sub in targets) {
                val seq = sub.seq.getAndIncrement()
                if (!sender.send(sub.callbackUrl, sub.sid, seq, body)) {
                    log("GENA NOTIFY failed sid=${sub.sid} seq=$seq -> ${sub.callbackUrl}")
                }
            }
        }
    }

    fun purgeExpired() {
        val now = clock()
        // Collection.removeIf 需 API 24；minSdk 21 用迭代器删除
        val iterator = subscriptions.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMs <= now) iterator.remove()
        }
    }

    private fun postInitialSnapshot(sub: Subscription) {
        val provider = snapshotProviders[sub.service] ?: return
        submit {
            val body = try {
                provider()
            } catch (_: Exception) {
                return@submit
            }
            if (body.isEmpty()) return@submit
            val seq = sub.seq.getAndIncrement()
            if (!sender.send(sub.callbackUrl, sub.sid, seq, body)) {
                log("GENA initial NOTIFY failed sid=${sub.sid} -> ${sub.callbackUrl}")
            }
        }
    }

    private fun submit(task: Runnable) {
        val ex: Executor = executor ?: return
        try {
            ex.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun subscribeResponse(sid: String, timeoutSec: Int) = HttpResponse(
        200, "OK",
        headers = listOf(
            "SID" to sid,
            "TIMEOUT" to "Second-$timeoutSec",
            "DATE" to httpDate(clock()),
        ),
    )

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 1800
        const val MAX_TIMEOUT_SEC = 1800
        private val CALLBACK_URL = Regex("<(http[^<>]+)>")

        fun parseTimeout(header: String?): Int {
            if (header == null) return DEFAULT_TIMEOUT_SEC
            val value = header.trim().removePrefix("Second-")
            val seconds = value.toIntOrNull() ?: return DEFAULT_TIMEOUT_SEC
            return seconds.coerceIn(1, MAX_TIMEOUT_SEC)
        }

        fun httpDate(nowMs: Long): String {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            return format.format(Date(nowMs))
        }
    }
}

private fun plain(status: Int, reason: String): HttpResponse = HttpResponse.text(status, reason)
