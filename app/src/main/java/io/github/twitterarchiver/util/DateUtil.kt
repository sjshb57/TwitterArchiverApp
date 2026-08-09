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

    /**
     * SimpleDateFormat 不是线程安全的，不能做成共享单例；但每次调用都新建也不行——
     * 排序、按日筛选、日计数都要对几十万条逐条解析，实测每次新建比复用慢 2.7 倍。
     * 用 ThreadLocal 各线程各持一份，兼顾安全与开销。
     */
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

    /**
     * 时区代际号。TimeZone.getDefault() 每次调用都会返回一份克隆，
     * 放在格式化热路径上等于每条推文一次分配 + 比较，把复用省下的开销又还回去。
     * 这里只在时区真的变了时自增，格式化时比一个 int 即可。
     */
    @Volatile
    private var tzGeneration = 0

    /**
     * 跟随系统时区的格式化器。每个实例各自记录已应用的代际号——
     * 共用一个计数的话，一个格式化器更新后其余就再也不会更新了。
     */
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

    /** 系统时区变化时调用 */
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

    /** 排序用的毫秒时间戳。解析不出来返回 0，排到最后 */
    fun epochMillis(timestamp: String): Long = parse(timestamp)?.time ?: 0L

    /** 毫秒时间戳 → yyyy-MM（UTC）。分片是按 UTC 月份切的，这里必须用 UTC。 */
    fun utcMonthOf(ms: Long): String = monthFmt.get()!!.format(java.util.Date(ms))

    /** timestamp → 设备本地时区的 yyyy-MM-dd */
    fun localDate(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return dateFmt.get().format(d)
    }

    /**
     * 日期字符串去重池。48 万条推文各持一个 displayDate，但不同取值只有天数那么多
     * （十年约 3650 个），不共享的话多出约 20 MB 的重复字符串。
     * 上限用于兜底，正常情况远达不到。
     */
    private const val DATE_POOL_MAX = 20_000
    private val datePool = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 已经有毫秒数时直接格式化，省掉重复解析。
     * ms 为 0 说明原始串没解析出来，退回按字符串解析以保持与 localDate 一致的行为。
     */
    fun localDateOf(ms: Long, fallback: String): String {
        val formatted = if (ms > 0) dateFmt.get().format(java.util.Date(ms))
        else localDate(fallback)
        if (formatted.isEmpty()) return formatted
        datePool[formatted]?.let { return it }
        if (datePool.size < DATE_POOL_MAX) datePool[formatted] = formatted
        return formatted
    }

    /** timestamp → 设备本地时区的 HH:mm:ss */
    fun localTime(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return timeFmt.get().format(d)
    }

    /** timestamp → 设备本地时区的 yyyy-MM-dd HH:mm:ss（推文精确时间） */
    fun localDateTime(timestamp: String): String {
        val d = parse(timestamp) ?: return ""
        return dateTimeFmt.get().format(d)
    }
}
