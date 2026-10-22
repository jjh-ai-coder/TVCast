package com.tvcast.dlna

/** 由 upnp:class 优先、URI 扩展名兜底推断媒体类型（DIDL 解析失败时的分流依据） */
object MediaKindResolver {

    private val AUDIO_EXT = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "wma", "ape", "opus", "mid", "amr", "adts", "m3u8_audio",
    )
    private val IMAGE_EXT = setOf("jpg", "jpeg", "jpe", "png", "gif", "bmp", "webp", "heif", "heic", "tif", "tiff")

    fun resolve(uri: String, upnpClass: String?): MediaKind {
        val klass = upnpClass?.lowercase()
        if (!klass.isNullOrEmpty()) {
            val byClass = when {
                klass.contains("videoitem") || klass.contains("videoBroadcast") -> MediaKind.VIDEO
                klass.contains("audioitem") || klass.contains("musictrack") || klass.contains("audiobroadcast") -> MediaKind.AUDIO
                klass.contains("imageitem") || klass.contains("photo") -> MediaKind.IMAGE
                else -> null
            }
            if (byClass != null) return byClass
        }
        val path = uri.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return when (ext) {
            in AUDIO_EXT -> MediaKind.AUDIO
            in IMAGE_EXT -> MediaKind.IMAGE
            else -> MediaKind.VIDEO // 投屏以视频为主，未知类型按视频处理（失败走错误 UI）
        }
    }

    /** 解析失败时的展示标题回退：URI 最后一段文件名 */
    fun fallbackTitle(uri: String): String {
        val path = uri.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        return name.ifBlank { uri }
    }
}
