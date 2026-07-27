package io.github.twitterarchiver.util

/** 推文 ID 规范化：从各种粘贴形式里取出纯数字 ID */
object TweetIdUtil {

    /** 匹配 .../status/<数字>，兼容 statuses 与末尾的 /photo/1、?s=20 等 */
    private val STATUS_URL = Regex("""/status(?:es)?/(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * 支持的输入形式：
     *   1944633677478277495
     *   https://twitter.com/y2kstarrychan/status/1944633677478277495
     *   https://x.com/xxx/status/1944633677478277495?s=20
     *   https://x.com/xxx/status/1944633677478277495/photo/1
     * 统一输出：1944633677478277495
     *
     * 认不出来时原样返回（去空白），不做破坏性猜测——
     * 用户可能正在逐位输入，或粘的是别的格式，交给他自己判断。
     */
    fun normalize(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        STATUS_URL.find(s)?.let { return it.groupValues[1] }
        return s
    }
}
