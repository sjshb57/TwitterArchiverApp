package io.github.twitterarchiver.util

/** 链接显示形式：去协议头，过长则截断加省略号（原链接不变，仅影响显示） */
object LinkUtil {

    private const val MAX = 25

    fun display(raw: String, max: Int = MAX): String {
        val bare = raw.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
            .removeSuffix("/")
        return if (bare.length <= max) bare else bare.take(max) + "…"
    }

    /** 打开用：补上缺失的协议头，否则系统无法识别 */
    fun openUrl(raw: String): String {
        val s = raw.trim()
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s
        else "https://$s"
    }
}
