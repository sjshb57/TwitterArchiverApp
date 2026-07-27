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

    /** 从 tweets 构建 年->月->日 树 */
    fun buildDateTree(dates: List<String>): Map<String, Map<String, List<String>>> {
        val tree = sortedMapOf<String, MutableMap<String, MutableList<String>>>(compareByDescending { it })
        for (d in dates) {
            val parts = d.split("-")
            if (parts.size != 3) continue
            val (y, m, day) = parts
            val yearMap = tree.getOrPut(y) { sortedMapOf(compareByDescending { it }) }
            val monthList = yearMap.getOrPut(m) { mutableListOf() }
            if (day !in monthList) monthList.add(day)
        }
        // 日按降序
        tree.values.forEach { it.values.forEach { days -> days.sortDescending() } }
        return tree
    }
}
