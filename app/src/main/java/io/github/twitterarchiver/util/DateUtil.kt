package io.github.twitterarchiver.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 日期时间工具：把 UTC timestamp 转本地显示 + 构建日期树 */
object DateUtil {

    private val ISO_HEAD = Regex("^\\d{4}-")

    private val LEGACY_WEEKDAY_HEAD = Regex("^[A-Za-z]{3,9}[ ,]+(?=[A-Za-z]{3,9}[ ])")

    /** ISO 主体，形如 2020-09-09T13:44:34。小数秒与时区后缀在 parse 里先截掉 */
    private val ISO_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** 推特原始格式，形如 Wed Sep 09 13:44:34 +0000 2020 */
    private val LEGACY_FMT: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("MMM d HH:mm:ss Z yyyy")
        .toFormatter(Locale.US)

    private val MONTH_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun parseMillis(timestamp: String): Long? {
        if (timestamp.isBlank()) return null
        return try {
            if (ISO_HEAD.containsMatchIn(timestamp)) {
                val head = timestamp.substringBefore('.').substringBefore('Z').take(19)
                LocalDateTime.parse(head, ISO_FMT).toInstant(ZoneOffset.UTC).toEpochMilli()
            } else {
                val body = timestamp.trim().replaceFirst(LEGACY_WEEKDAY_HEAD, "")
                OffsetDateTime.parse(body, LEGACY_FMT).toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun epochMillis(timestamp: String): Long = parseMillis(timestamp) ?: 0L

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

    fun utcMonthOf(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).format(MONTH_FMT)

    private const val DATE_POOL_MAX = 20_000
    private val datePool = ConcurrentHashMap<String, String>()

    private fun pooled(formatted: String): String {
        if (formatted.isEmpty()) return formatted
        datePool[formatted]?.let { return it }
        if (datePool.size < DATE_POOL_MAX) datePool[formatted] = formatted
        return formatted
    }

    fun localDate(timestamp: String): String {
        val ms = parseMillis(timestamp) ?: return ""
        return pooled(formatLocal(ms, DATE_FMT))
    }

    fun localDateOf(ms: Long, fallback: String): String =
        if (ms > 0) pooled(formatLocal(ms, DATE_FMT)) else localDate(fallback)

    fun localTime(timestamp: String): String {
        val ms = parseMillis(timestamp) ?: return ""
        return formatLocal(ms, TIME_FMT)
    }

    fun localTimeOf(ms: Long, fallback: String): String =
        if (ms > 0) formatLocal(ms, TIME_FMT) else localTime(fallback)

    fun localDateTime(timestamp: String): String {
        val ms = parseMillis(timestamp) ?: return ""
        return formatLocal(ms, DATE_TIME_FMT)
    }

    private fun formatLocal(ms: Long, fmt: DateTimeFormatter): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
}
