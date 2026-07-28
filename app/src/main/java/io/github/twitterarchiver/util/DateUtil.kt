package io.github.twitterarchiver.util

/** 日期时间工具：把 UTC timestamp 转本地显示 + 构建日期树 */
object DateUtil {

    /** UTC timestamp(2026-05-18T09:36:48.000Z) → 设备本地时区的 yyyy-MM-dd（和 reader utcToLocal 一致） */
    fun localDate(timestamp: String): String {
        if (timestamp.isBlank()) return ""
        return try {
            val iso = timestamp.substringBefore(".").substringBefore("Z")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val d = fmt.parse(iso) ?: return ""
            val out = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            // 用设备默认时区输出
            out.format(d)
        } catch (e: Exception) { "" }
    }

    /** UTC timestamp → 设备本地时区的 yyyy-MM-dd HH:mm:ss（推文精确时间） */
    fun localDateTime(timestamp: String): String {
        if (timestamp.isBlank()) return ""
        return try {
            val iso = timestamp.substringBefore(".").substringBefore("Z")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val d = fmt.parse(iso) ?: return ""
            val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            out.format(d)
        } catch (e: Exception) { "" }
    }

}
