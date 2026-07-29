package io.github.twitterarchiver.util

/** 链接显示形式：去协议头，过长则截断加省略号（原链接不变，仅影响显示） */
object LinkUtil {

    private const val MAX = 20

    fun display(raw: String, max: Int = MAX): String {
        val bare = raw.trim()
            .removePrefix("https://").removePrefix("http://")
            .removeSuffix("/")
        return if (bare.length <= max) bare else bare.take(max) + "…"
    }
}
