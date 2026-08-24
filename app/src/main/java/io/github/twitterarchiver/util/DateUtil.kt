package io.github.twitterarchiver.util

/** 日期时间工具：把 UTC timestamp 转本地显示 + 构建日期树 */
object DateUtil {

    private val ISO_HEAD = Regex("^\\d{4}-")

    private val isoFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    private val legacyFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", java.util.Locale.US)
    }
    private val monthFmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    private val dateFmt = LocalFmt("yyyy-MM-dd")
    private val timeFmt = LocalFmt("HH:mm:ss")
    private val dateTimeFmt = LocalFmt("yyyy-MM-dd HH:mm:ss")

    @Volatile
    private var tzGeneration = 0

    private class LocalFmt(pattern: String) {
        private val tl = ThreadLocal.withInitial {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US) to intArrayOf(-1)
        }

        fun get(): java.text.SimpleDateFormat {
            val (fmt, applied) = tl.get()!!
            val cur = tzGeneration
            if (applied[0] != cur) {
                fmt.timeZone = java.util.TimeZone.getDefault()
                applied[0] = cur
            }
            return fmt
        }
    }

    fun onTimeZoneChanged() {
        tzGeneration++
        datePool.clear()
    }

    private fun parse(timestamp: String): java.util.Date? {
        if (timestamp.isBlank()) return null
        return try {
            if (ISO_HEAD.containsMatchIn(timestamp)) {
                isoFmt.get()!!.parse(timestamp.substringBefore(".").substringBefore("Z"))
            } else {
                legacyFmt.get()!!.parse(timestamp)
            }
        } catch (e: Exception) { null }
    }

    fun epochMillis(timestamp: String): Long = parse(timestamp)?.time ?: 0L

    fun <T> timeComparator(ascending: Boolean, key: (T) -> Long): Comparator<T> =
        Comparator { a, b ->
            val x = key(a)
            val y = key(b)
            when {
                x == y -> 0
                x == 0L -> 1
                y == 0L -> -1
                ascending -> x.compareTo(y)
                else -> y.compareTo(x)
            }
        }

    fun utcMonthOf(ms: Long): String = monthFmt.get()!!.format(java.util.Date(ms))

    fun localDate(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return dateFmt.get().format(d)
    }

    private const val DATE_POOL_MAX = 20_000
    private val datePool = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun localDateOf(ms: Long, fallback: String): String {
        val formatted = if (ms > 0) dateFmt.get().format(java.util.Date(ms))
        else localDate(fallback)
        if (formatted.isEmpty()) return formatted
        datePool[formatted]?.let { return it }
        if (datePool.size < DATE_POOL_MAX) datePool[formatted] = formatted
        return formatted
    }

    fun localTime(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return timeFmt.get().format(d)
    }

    fun localDateTime(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return dateTimeFmt.get().format(d)
    }

}
