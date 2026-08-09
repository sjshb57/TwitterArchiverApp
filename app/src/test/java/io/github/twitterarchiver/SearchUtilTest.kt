package io.github.twitterarchiver

import io.github.twitterarchiver.data.GitHubError
import io.github.twitterarchiver.util.SearchUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 雪花 ID 反推月份。搜完整推文 ID 时靠它决定下载哪个分片，
 * 算错的话表现是"搜不到"，不会报错，属于最难发现的那类。
 */
class SearchUtilTest {

    @Test
    fun `从推文 ID 反推月份`() {
        // 实际数据核对过：这条推文的时间是 2026-04-05T19:11:00Z
        assertEquals("2026-04", SearchUtil.monthFromTweetId("2040869831558447235"))
        assertEquals("2025-02", SearchUtil.monthFromTweetId("1892864053422542964"))
    }

    @Test
    fun `不是完整 ID 的一律返回 null`() {
        assertNull(SearchUtil.monthFromTweetId(""))
        assertNull(SearchUtil.monthFromTweetId("12345678"))      // 太短，是定位短码
        assertNull(SearchUtil.monthFromTweetId("abcdefghijklmnop"))
        assertNull(SearchUtil.monthFromTweetId("1234567890123456789012"))  // 超出合理时间范围
    }

    @Test
    fun `定位短码提取`() {
        assertEquals("abc12345", SearchUtil.extractTCode("?t=abc12345"))
        assertEquals("abc12345", SearchUtil.extractTCode("t=abc12345"))
        assertNull(SearchUtil.extractTCode("普通搜索词"))
        assertNull(SearchUtil.extractTCode("t="))
    }

    @Test
    fun `是否含 ASCII 字母决定要不要做大小写折叠`() {
        assertFalse(SearchUtil.hasAsciiLetter("中文关键词"))
        assertFalse(SearchUtil.hasAsciiLetter("12345"))
        assertTrue(SearchUtil.hasAsciiLetter("hello"))
        assertTrue(SearchUtil.hasAsciiLetter("中文mixed"))
    }

    @Test
    fun `匹配对中文走原样比较，对英文折叠大小写`() {
        assertTrue(SearchUtil.matches("今天天气不错", "天气", "天气", false))
        assertFalse(SearchUtil.matches("今天天气不错", "下雨", "下雨", false))
        // 含 ASCII 时应当忽略大小写
        assertTrue(SearchUtil.matches("Hello World", "hello", "hello", true))
        assertTrue(SearchUtil.matches("HELLO", "hello", "hello", true))
    }
}

/** 错误文案。用户看到的第一手信息，取错字段会误导排查方向 */
class GitHubErrorTest {

    @Test
    fun `取顶层 message 而不是 errors 数组里的`() {
        val body = """{"message":"Validation Failed","errors":[{"message":"内层不该被取到"}]}"""
        assertEquals("Validation Failed", GitHubError.reasonOf(body))
    }

    @Test
    fun `解析失败时返回空串而不是抛异常`() {
        assertEquals("", GitHubError.reasonOf("not json at all"))
        assertEquals("", GitHubError.reasonOf(""))
        assertEquals("", GitHubError.reasonOf("{}"))
    }

    @Test
    fun `主限流与二级限流要区分开`() {
        // Remaining 为 0 = 配额用尽
        val primary = GitHubError.describe(403, "", remaining = "0", resetEpoch = null)
        assertTrue(primary.contains("上限"))

        // 带 Retry-After = 二级限流，不该说"没有权限"
        val secondary = GitHubError.describe(
            403, "", remaining = "42", resetEpoch = null, retryAfter = "30")
        assertTrue(secondary.contains("频繁"))
        assertFalse(secondary.contains("权限"))

        // 响应体里写明 secondary rate limit，即便没有 Retry-After 也要认出来
        val noHeader = GitHubError.describe(
            403, """{"message":"You have exceeded a secondary rate limit"}""",
            remaining = "42", resetEpoch = null)
        assertTrue(noHeader.contains("频繁"))

        // 真正的权限问题
        val forbidden = GitHubError.describe(403, "", remaining = "42", resetEpoch = null)
        assertTrue(forbidden.contains("权限"))
    }

    @Test
    fun `429 也归到频繁`() {
        assertTrue(GitHubError.describe(429, "", null, null).contains("频繁"))
    }

    @Test
    fun `常见状态码都有人话`() {
        assertTrue(GitHubError.describe(401, "", null, null).contains("令牌"))
        assertTrue(GitHubError.describe(404, "", null, null).contains("不存在"))
        assertTrue(GitHubError.describe(500, "", null, null).contains("服务异常"))
        // 不该把原始 JSON 泄漏出去
        assertFalse(GitHubError.describe(404, """{"documentation_url":"x"}""", null, null)
            .contains("documentation_url"))
    }
}
