package com.tvcast.sender

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tvcast.dlna.http.HttpRequest
import com.tvcast.dlna.http.HttpResponse
import com.tvcast.dlna.http.MiniHttpServer
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地媒体 HTTP 服务：把 content:// URI 以 HTTP 暴露给电视拉流。
 * 支持 Range（电视端 ExoPlayer 拖进度必需）；单连接逐请求服务。
 */
object ContentServer {

  private data class Item(val context: Context, val uri: Uri, val mimeType: String, val size: Long)

  private var server: MiniHttpServer? = null
  private val item = AtomicReference<Item?>(null)

  /** 启动（或替换内容）并返回端口 */
  @Synchronized
  fun start(context: Context, uri: Uri): Int {
    val appContext = context.applicationContext
    val mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
    val size = querySize(appContext, uri)
    item.set(Item(appContext, uri, mimeType, size))
    val existing = server
    if (existing != null && existing.isRunning) return existing.port
    val newServer = MiniHttpServer(object : com.tvcast.dlna.http.HttpHandler {
      override fun handle(request: HttpRequest): HttpResponse = serve(request)
    })
    newServer.start(preferredPort = 0)
    server = newServer
    return newServer.port
  }

  @Synchronized
  fun stop() {
    server?.stop()
    server = null
    item.set(null)
  }

  fun currentUrl(): String? {
    val s = server ?: return null
    if (!s.isRunning) return null
    val ip = com.tvcast.dlna.NetInfo.pickLocalIp() ?: return null
    return "http://$ip:${s.port}/content"
  }

  private fun serve(request: HttpRequest): HttpResponse {
    val current = item.get() ?: return HttpResponse.text(404, "No content")
    if (request.method != "GET" && request.method != "HEAD") {
      return HttpResponse.text(405, "Method Not Allowed")
    }
    val resolver = current.context.contentResolver
    val stream = try {
      resolver.openInputStream(current.uri) ?: return HttpResponse.text(404, "Gone")
    } catch (e: Exception) {
      return HttpResponse.text(500, "Open failed: ${e.message}")
    }

    val total = current.size
    var start = 0L
    var end = if (total > 0) total - 1 else -1L
    var partial = false
    val range = request.header("Range")
    if (range != null) {
      val match = Regex("bytes=(\\d*)-(\\d*)").find(range.trim())
      if (match != null) {
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        if (startText.isNotEmpty()) {
          start = startText.toLong()
          end = if (endText.isNotEmpty()) endText.toLong() else if (total > 0) total - 1 else -1L
        } else if (endText.isNotEmpty()) {
          // suffix range: bytes=-N
          val suffix = endText.toLong()
          start = if (total > 0) (total - suffix).coerceAtLeast(0) else 0
          end = if (total > 0) total - 1 else -1L
        }
        partial = true
      }
    }

    if (total > 0 && start > 0) {
      var skipped = 0L
      while (skipped < start) {
        val n = stream.skip(start - skipped)
        if (n <= 0) break
        skipped += n
      }
    }

    val length = if (total > 0) {
      (end - start + 1).coerceAtLeast(0)
    } else {
      -1L // 未知大小：按 chunked 不可行（mini server 定长），直接读到底
    }

    val headers = mutableListOf<Pair<String, String>>(
      "Accept-Ranges" to "bytes",
    )
    if (partial && total > 0) {
      headers += "Content-Range" to "bytes $start-$end/$total"
    }

    val body: ByteArray = if (length >= 0 && length <= MAX_IN_MEMORY) {
      ByteArray(length.toInt()).also { buf ->
        var offset = 0
        while (offset < buf.size) {
          val n = stream.read(buf, offset, buf.size - offset)
          if (n <= 0) break
          offset += n
        }
        stream.close()
      }
    } else {
      // 未知大小：读全部（本地文件大小已知，理论上不会走到）
      stream.use { it.readBytes() }
    }

    return HttpResponse(
      if (partial) 206 else 200,
      if (partial) "Partial Content" else "OK",
      current.mimeType,
      headers,
      body,
    )
  }

  private fun querySize(context: Context, uri: Uri): Long = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
      if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
    } ?: -1L
  } catch (_: Exception) {
    -1L
  }

  private const val MAX_IN_MEMORY = 64L * 1024 * 1024
}
