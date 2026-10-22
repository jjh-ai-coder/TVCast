package com.tvcast.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.media3.ui.PlayerView
import com.tvcast.R
import com.tvcast.core.CastService
import com.tvcast.dlna.MediaKind
import com.tvcast.dlna.TransportState
import com.tvcast.playback.ImageDisplayer

/** 播放页：视频 PlayerView / 图片 Glide / 音频黑底+标题；错误覆盖层；播放期常亮 */
class PlayingFragment : Fragment() {

  private var service: CastService? = null
  private var lastLoadedImageUri: String? = null
  private val uiListener = Runnable { refresh() }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_playing, container, false)

  override fun onResume() {
    super.onResume()
    val view = playerView()
    (activity as? MainActivity)?.castService?.playerController?.let { controller ->
      view?.let { controller.attachView(it) }
    }
    refresh()
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
    val view = playerView()
    (activity as? MainActivity)?.castService?.playerController?.let { controller ->
      view?.let { controller.detachView(it) }
    }
  }

  private fun playerView(): PlayerView? = view?.findViewById(R.id.player_view)

  fun refresh() {
    val root = view ?: return
    val player = service?.playerController
      ?: (activity as? MainActivity)?.castService?.playerController
      ?: return
    root.findViewById<TextView>(R.id.media_title).text = player.title ?: player.uri ?: ""

    val error = player.lastError
    val errorView = root.findViewById<View>(R.id.error_overlay)
    errorView.visibility = if (error != null) View.VISIBLE else View.GONE
    if (error != null) {
      root.findViewById<TextView>(R.id.error_text).text = error
    }

    val playerView = root.findViewById<PlayerView>(R.id.player_view)
    val imageView = root.findViewById<ImageView>(R.id.image_view)
    if (player.kind == MediaKind.IMAGE) {
      playerView.visibility = View.GONE
      imageView.visibility = View.VISIBLE
      val uri = player.uri
      if (uri != null && uri != lastLoadedImageUri) {
        lastLoadedImageUri = uri
        ImageDisplayer.display(imageView, uri)
      }
    } else {
      playerView.visibility = View.VISIBLE
      imageView.visibility = View.GONE
      lastLoadedImageUri = null
      playerView.useController = true
    }

    root.keepScreenOn = player.state == TransportState.PLAYING || player.kind == MediaKind.IMAGE
  }
}
