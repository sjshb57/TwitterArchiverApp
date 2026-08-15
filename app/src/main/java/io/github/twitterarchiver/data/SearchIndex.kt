package io.github.twitterarchiver.data

import kotlinx.serialization.Serializable

/** search-index.json 的账号条目 */
@Serializable
data class IndexAccount(
    val r: String = "",   // repo
    val a: String = "",   // account
    val u: String = "",   // @username
    val n: String = "",   // name
    val av: String = ""   // avatar 文件名
)

/** 视频扩展名，用于把 media 列表分流到 image/ 与 video/ 两个目录 */
private val VIDEO_EXTS = setOf("mp4", "m4v", "mov", "webm")

/**
 * 全站一条推文（从 search-index.json 的 posts 解析）。
 * posts 格式：[账号索引, 正文, tweet_id, 时间, [媒体], 回复数, 引用标记]
 */
data class GlobalPost(
    val acctIndex: Int,
    val text: String,
    val tweetId: String,
    val time: String,
    val media: List<String>,
    val replyCount: Int,
    val hasQuote: Boolean,
    val account: IndexAccount
) {
    val avatarUrl: String
        get() = if (account.av.isNotBlank())
            "${Config.snapshotsBase(account.r, account.a)}/avatar/${account.av}"
        else ""

    /** 媒体按扩展名分流：视频存在 video/ 目录，图片在 image/ 目录 */
    private fun isVideoName(n: String): Boolean =
        n.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

    /** 图片直链（不含视频） */
    val mediaUrls: List<String>
        get() = media.filterNot(::isVideoName)
            .map { "${Config.snapshotsBase(account.r, account.a)}/image/$it" }

    /** 视频直链 */
    val videoUrls: List<String>
        get() = media.filter(::isVideoName)
            .map { "${Config.snapshotsBase(account.r, account.a)}/video/$it" }

    /**
     * 排序用的毫秒时间戳。放进比较器里现算的话是 O(n log n) 次日期解析，
     * 48 万条的量级下开销比 displayDate 那几次读取加起来还大。
     */
    val epochMs: Long = io.github.twitterarchiver.util.DateUtil.epochMillis(time)

    /**
     * 本地时区日期。不能直接截 T 前面——那既是 UTC 日期，又会把老格式的 Tue/Thu 截断。
     *
     * 日计数、按日筛选、列表渲染都要读它，而它要跑一次日期解析。
     * 用 get() 的话同一条会被解析三次以上；用 by lazy 又要为 48 万个实例
     * 各分配一个 Lazy 对象。这里直接在构造时算好，零包装、只算一次。
     */
    val displayDate: String = io.github.twitterarchiver.util.DateUtil.localDateOf(epochMs, time)

    /** 本地时区时间（HH:mm:ss），搜索结果条用来定位 */
    val displayTime: String
        get() = io.github.twitterarchiver.util.DateUtil.localTime(time)

    /** 是否转推（RT @xxx: ...）。RT 的引用原推在 html 里，需按需解析 */
    val isRetweet: Boolean get() = text.startsWith("RT @")

    /** 是否应显示"查看引用"（quote 引用 或 RT 转推） */
    val hasQuoteOrRt: Boolean get() = hasQuote || isRetweet
}

/** 跨账号回复（cross-replies.json 的一条） */
data class CrossReply(
    val acctIndex: Int,
    val tweetId: String,
    val text: String,
    val time: String,
    val replyToId: String
)

/** 回复链里的一项（统一主人回复、跨账号回复、被引用对象） */
data class ThreadItem(
    val tweetId: String,
    val authorName: String,
    val authorUsername: String,
    val authorAvatarUrl: String,
    val text: String,
    val images: List<String>,
    val time: String,
    val isOwner: Boolean,
    val isQuoted: Boolean
) {
    /** 同 Tweet.epochMs：回复链里也可能混入老格式时间戳 */
    val epochMs: Long get() = io.github.twitterarchiver.util.DateUtil.epochMillis(time)
}

/** 主推文的引用原推（转推/引用显示用） */
data class QuotedTweet(
    val authorName: String,
    val authorUsername: String,
    val authorAvatarUrl: String,
    val text: String,
    val images: List<String>
)
