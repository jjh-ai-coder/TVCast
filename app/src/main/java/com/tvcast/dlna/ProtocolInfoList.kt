package com.tvcast.dlna

/**
 * GetProtocolInfo 的宽泛静态 sink 列表（spec §4.9 定论：不做运行时硬解能力收敛）。
 * 播放失败走错误 UI，V2 接 ffmpeg 扩展兜底。
 */
object ProtocolInfoList {

    private val ENTRIES = listOf(
        // ---- video ----
        "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF30_AAC_MULT5;DLNA.ORG_OP=01",
        "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC;DLNA.ORG_OP=01",
        "http-get:*:video/mp4:DLNA.ORG_PN=MPEG4_P2_SP_AAC;DLNA.ORG_OP=01",
        "http-get:*:video/mp4:*",
        "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_NTSC;DLNA.ORG_OP=01",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/mp2t:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=01",
        "http-get:*:video/mp2t:DLNA.ORG_PN=AVC_TS_MP_SD_AAC_MULT5;DLNA.ORG_OP=01",
        "http-get:*:video/mp2t:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:video/x-msvideo:*",
        "http-get:*:video/x-ms-wmv:*",
        "http-get:*:video/x-flv:*",
        "http-get:*:video/3gpp:*",
        "http-get:*:video/quicktime:*",
        "http-get:*:video/webm:*",
        "http-get:*:video/x-mpegurl:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/x-mpegurl:*",
        // ---- audio ----
        "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp3:*",
        "http-get:*:audio/flac:*",
        "http-get:*:audio/x-flac:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:audio/aac:*",
        "http-get:*:audio/vnd.dlna.adts:*",
        "http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMABASE;DLNA.ORG_OP=01",
        "http-get:*:audio/x-ms-wma:*",
        "http-get:*:audio/wav:*",
        "http-get:*:audio/x-wav:*",
        "http-get:*:audio/L16:*",
        "http-get:*:audio/ogg:*",
        // ---- image ----
        "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01",
        "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED;DLNA.ORG_OP=01",
        "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM;DLNA.ORG_OP=01",
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
        "http-get:*:image/gif:*",
        "http-get:*:image/bmp:*",
        "http-get:*:image/webp:*",
    )

    fun sinkProtocolInfo(): String = ENTRIES.joinToString(",")
}
