package io.github.twitterarchiver.util

/** 链接显示形式：去协议头，过长则截断加省略号（原链接不变，仅影响显示） */
object LinkUtil {

    private const val MAX = 25

    /**
     * 正文里的链接。到 CJK 或全角标点为止——\S+ 会把紧跟其后的中文一起吞进来。
     * 简介与位置共用，改这一处即可。
     */
    val URL_IN_TEXT = Regex(
        "https?://[^\\s\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef]+",
        RegexOption.IGNORE_CASE
    )

    /** 只剥前缀不截断：简介、位置里出现的链接用这个 */
    fun stripPrefix(raw: String): String = raw.trim()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("www.")
        .removeSuffix("/")

    fun display(raw: String, max: Int = MAX): String {
        val bare = stripPrefix(raw)
        return if (bare.length <= max) bare else bare.take(max) + "…"
    }

    /** 把一段文字里的链接都剥掉前缀，其余原样 */
    fun stripInText(text: String): String =
        URL_IN_TEXT.replace(text) { stripPrefix(it.value) }

    /** 打开用：补上缺失的协议头，否则系统无法识别 */
    fun openUrl(raw: String): String {
        val s = raw.trim()
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s
        else "https://$s"
    }
}
