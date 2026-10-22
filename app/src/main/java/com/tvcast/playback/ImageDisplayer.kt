package com.tvcast.playback

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

/**
 * 图片投屏显示：强制按屏幕分辨率降采样（spec §6：防老盒子 4K 图 OOM）。
 * 同一 URI 只加载一次，避免状态刷新重复触发。
 */
object ImageDisplayer {

  fun display(view: ImageView, uri: String) {
    val metrics = view.resources.displayMetrics
    Glide.with(view)
      .load(uri)
      .override(metrics.widthPixels, metrics.heightPixels)
      .fitCenter()
      .transition(DrawableTransitionOptions.withCrossFade())
      .into(view)
  }
}
