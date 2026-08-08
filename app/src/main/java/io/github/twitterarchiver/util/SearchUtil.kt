package io.github.twitterarchiver.util

import java.util.Locale

/**
 * 搜索相关的工具：推文 ID 解析、快速文本匹配。
 */
object SearchUtil {

    /** 推特雪花 ID 的纪元：2010-11-04T01:42:54.657Z */
    private const val TWITTER_EPOCH_MS = 1_288_834_974_657L

    /** 完整推文 ID 至少 15 位；短于此的多半是定位短码或普通数字 */
    private const val MIN_FULL_ID_LEN = 15

    /**
     * 从完整推文 ID 反推发推月份（yyyy-MM，UTC）。
     *
     * 雪花 ID 的高 41 位就是毫秒时间戳，所以不下载任何数据就能知道这条推文属于
     * 哪个月份分片——搜一个完整 ID 只需要下载那一个分片，而不是整个索引。
     * 传入的不是合法完整 ID 时返回 null。
     */
    fun monthFromTweetId(id: String): String? {
        if (id.length < MIN_FULL_ID_LEN || !id.all { it.isDigit() }) return null
        val num = id.toLongOrNull() ?: return null
        val ms = (num shr 22) + TWITTER_EPOCH_MS
        // 早于推特上线或晚于当前时间太多的，视为不是雪花 ID
        if (ms < TWITTER_EPOCH_MS || ms > System.currentTimeMillis() + 86_400_000L) return null
        return DateUtil.utcMonthOf(ms)
    }

    /** 是不是一个完整推文 ID（能推出月份就算） */
    fun isFullTweetId(q: String): Boolean = monthFromTweetId(q) != null

    /** 定位短码：形如 ?t=xxxxxxxx 或裸的 8 位以内数字/字母 */
    private val T_CODE = Regex("^\\??t=(\\w+)$", RegexOption.IGNORE_CASE)

    fun extractTCode(q: String): String? = T_CODE.find(q.trim())?.groupValues?.get(1)

    /**
     * 大小写不敏感的包含判断，但只在必要时才付出代价。
     *
     * `text.lowercase().contains(q)` 每次调用都要为整段正文分配一个新字符串，
     * 60 万条推文上实测要 700 ms，正是搜索卡顿的主因。而中文没有大小写，
     * 查询里不含 ASCII 字母时直接走 indexOf 即可（实测 56 ms）。
     *
     * 注意不能用 `contains(ignoreCase = true)` 来偷懒：它走的是逐位置
     * regionMatches，实测反而比 lowercase 慢 1.7 倍。
     */
    fun matches(text: String, query: String, queryLower: String, queryHasAscii: Boolean): Boolean =
        if (queryHasAscii) text.lowercase(Locale.ROOT).contains(queryLower)
        else text.contains(query)

    /** 查询里是否含 ASCII 字母（决定要不要做大小写折叠） */
    fun hasAsciiLetter(q: String): Boolean = q.any { it in 'a'..'z' || it in 'A'..'Z' }
}
