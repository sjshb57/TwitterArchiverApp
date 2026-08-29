package io.github.twitterarchiver.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日期时间工具：把 UTC timestamp 转本地显示 + 构建日期树。
 *
 * 用 java.time 而不是 SimpleDateFormat：
 * - DateTimeFormatter 不可变、线程安全，不需要 ThreadLocal 各持一份；
 * - 本地时区在 format 时才由 ZoneId.systemDefault() 现取，用户改时区
 *   （跨时区移动、夏令时切换）下一次格式化自动生效，不需要监听
 *   ACTION_TIMEZONE_CHANGED 再手工让缓存失效。
 */
object DateUtil {

    private val ISO_HEAD = Regex("^\\d{4}-")

    /** ISO 主体，形如 2020-09-09T13:44:34。小数秒与时区后缀在 parse 里先截掉 */
    private val ISO_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** 推特原始格式，形如 Wed Sep 09 13:44:34 +0000 2020 */
    private val LEGACY_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)

    private val MONTH_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)
    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 解析时间戳为 epoch 毫秒，失败返回 null。
     *
     * ISO 分支按 UTC 解读：截掉小数秒和时区后缀后只取 yyyy-MM-ddTHH:mm:ss 这一段，
     * 与旧的 SimpleDateFormat(UTC) + 宽松解析（忽略尾部多余字符）行为一致。
     */
    private fun parseMillis(timestamp: String): Long? {
        if (timestamp.isBlank()) return null
        return try {
            if (ISO_HEAD.containsMatchIn(timestamp)) {
                val head = timestamp.substringBefore('.').substringBefore('Z').take(19)
                LocalDateTime.parse(head, ISO_FMT).toInstant(ZoneOffset.UTC).toEpochMilli()
            } else {
                OffsetDateTime.parse(timestamp, LEGACY_FMT).toInstant().toEpochMilli()
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

    /** 本地时区日期，用于日期树与分组 */
    fun localDate(timestamp: String): String {
        val ms = parseMillis(timestamp) ?: return ""
        return formatLocal(ms, DATE_FMT)
    }

    fun localDateOf(ms: Long, fallback: String): String =
        if (ms > 0) formatLocal(ms, DATE_FMT) else localDate(fallback)

    fun localTime(timestamp: String): String {
        val ms = parseMillis(timestamp) ?: return ""
        return formatLocal(ms, TIME_FMT)
    }

    fun localDateTime(timestamp: String): String {
        val ms = parseMillis(timestamp) ?: return ""
        return formatLocal(ms, DATE_TIME_FMT)
    }

    /** 时区在这里现取：系统时区变了下一次调用自然就是新的 */
    private fun formatLocal(ms: Long, fmt: DateTimeFormatter): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
}
