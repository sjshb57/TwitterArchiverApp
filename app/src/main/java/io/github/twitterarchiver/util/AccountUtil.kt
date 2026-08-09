package io.github.twitterarchiver.util

/** 账号名规范化：容错用户的各种输入写法，统一成裸账号名 */
object AccountUtil {

    private val URL_PREFIX = Regex("""^(https?://)?(www\.)?(twitter|x)\.com/""", RegexOption.IGNORE_CASE)

    /**
     * 支持的输入形式：
     *   xiaoyu598 / @xiaoyu598 / " @xiaoyu598 " / 　@xiaoyu598（全角空格）
     *   x.com/xiaoyu598 / https://twitter.com/xiaoyu598?s=20
     * 统一输出：xiaoyu598
     *
     * 注意顺序必须「先 trim 再去 @」——反过来时前导空格会让 removePrefix 失效，
     * 结果带着 @ 去建仓库会直接 422。
     */
    fun normalize(raw: String): String {
        var s = raw.trim()                                  // trim() 已覆盖全角空格 U+3000
        s = URL_PREFIX.replace(s, "")                       // 去掉推特域名前缀
        s = s.substringBefore('?').substringBefore('/')     // 去掉 query 和多余路径
        s = s.trim()
        while (s.startsWith("@")) s = s.removePrefix("@").trim()
        return s
    }

    /** 推特用户名：1–15 位字母数字下划线 */
    private val HANDLE = Regex("^[A-Za-z0-9_]{1,15}$")

    /**
     * 规范化后是否是合法用户名。
     * 既拦住脚本刷接口，也防呆——用户把整条推文链接粘进来时，
     * normalize 剥不干净的部分会在这里被挡下，而不是提交一堆垃圾。
     */
    fun isValidHandle(raw: String): Boolean = HANDLE.matches(normalize(raw))
}
