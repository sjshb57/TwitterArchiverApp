package io.github.twitterarchiver.util

/** 日期时间工具：把 UTC timestamp 转本地显示 + 构建日期树 */
object DateUtil {

    /**
     * 存档里同时存在两种时间格式，与 archive.py、build_search_index.yml、search.html 保持一致：
     *   ISO：     2026-05-18T09:36:48.000Z
     *   老推特：   Wed Sep 09 13:44:34 +0000 2020
     *
     * 不能用「含不含大写 T」来区分——老格式的 Tue / Thu 里也有 T。以四位年份开头的才是 ISO。
     */
    private val ISO_HEAD = Regex("^\\d{4}-")

    private fun parse(timestamp: String): java.util.Date? {
        if (timestamp.isBlank()) return null
        return try {
            if (ISO_HEAD.containsMatchIn(timestamp)) {
                val iso = timestamp.substringBefore(".").substringBefore("Z")
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(iso)
            } else {
                java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", java.util.Locale.US)
                    .parse(timestamp)
            }
        } catch (e: Exception) { null }
    }

    /** 排序用的毫秒时间戳。解析不出来返回 0，排到最后 */
    fun epochMillis(timestamp: String): Long = parse(timestamp)?.time ?: 0L

    /** 毫秒时间戳 → yyyy-MM（UTC）。分片是按 UTC 月份切的，这里必须用 UTC。 */
    fun utcMonthOf(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(ms))

    /** timestamp → 设备本地时区的 yyyy-MM-dd */
    fun localDate(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d)
    }

    /** timestamp → 设备本地时区的 HH:mm:ss */
    fun localTime(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(d)
    }

    /** timestamp → 设备本地时区的 yyyy-MM-dd HH:mm:ss（推文精确时间） */
    fun localDateTime(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(d)
    }
}
