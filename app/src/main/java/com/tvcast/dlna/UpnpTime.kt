package com.tvcast.dlna

/** UPnP 时间格式：输出统一 H:MM:SS，输入兼容 H:MM:SS / HH:MM:SS / MM:SS */
object UpnpTime {

    fun format(totalSeconds: Long): String {
        val s = if (totalSeconds > 0) totalSeconds else 0
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    fun formatMs(ms: Long): String = format(ms / 1000)

    /** 解析失败返回 null */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        val parts = trimmed.split(":")
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return when (numbers.size) {
            3 -> {
                val (h, m, s) = numbers
                if (m !in 0..59 || s !in 0..59 || h < 0) null else h * 3600L + m * 60L + s
            }
            2 -> {
                val (m, s) = numbers
                if (m !in 0..59 || s !in 0..59 || m < 0) null else m * 60L + s
            }
            else -> null
        }
    }

    fun parseToMs(text: String): Long? = parse(text)?.times(1000L)
}
