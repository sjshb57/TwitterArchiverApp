package io.github.twitterarchiver

import io.github.twitterarchiver.data.GitHubError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 错误文案。用户看到的第一手信息，取错字段会误导排查方向 */
@Suppress("NonAsciiCharacters")
class GitHubErrorTest {

    @Test
    fun `取顶层 message 而不是 errors 数组里的`() {
        val body = """{"message":"Validation Failed","errors":[{"message":"内层不该被取到"}]}"""
        assertEquals("Validation Failed", GitHubError.reasonOf(body))
    }

    @Test
    fun 解析失败时返回空串而不是抛异常() {
        assertEquals("", GitHubError.reasonOf("not json at all"))
        assertEquals("", GitHubError.reasonOf(""))
        assertEquals("", GitHubError.reasonOf("{}"))
    }

    @Test
    fun 主限流与二级限流要区分开() {
        // 测 classify 而不是 describe：文案要经过 Android 资源，单测里拿不到 Context，
        // 而这里真正要验的是「判断成哪一类」——那才是会出错的地方
        assertTrue(
            GitHubError.classify(403, "", remaining = "0", resetEpoch = null)
                is GitHubError.Kind.QuotaExhausted
        )
        // 带 Retry-After = 二级限流，不该判成权限问题
        assertTrue(
            GitHubError.classify(403, "", remaining = "42", resetEpoch = null, retryAfter = "30")
                is GitHubError.Kind.RateLimited
        )
        // 响应体写明 secondary rate limit，即便没有 Retry-After 也要认出来
        assertTrue(
            GitHubError.classify(
                403, """{"message":"You have exceeded a secondary rate limit"}""",
                remaining = "42", resetEpoch = null
            ) is GitHubError.Kind.RateLimited
        )
        assertEquals(
            GitHubError.Kind.Forbidden,
            GitHubError.classify(403, "", remaining = "42", resetEpoch = null)
        )
    }

    @Test
    fun `429 也归到频繁`() {
        assertTrue(GitHubError.classify(429, "", null, null) is GitHubError.Kind.RateLimited)
    }

    @Test
    fun 常见状态码各归各类() {
        assertEquals(GitHubError.Kind.TokenInvalid, GitHubError.classify(401, "", null, null))
        assertEquals(GitHubError.Kind.NotFound, GitHubError.classify(404, "", null, null))
        assertTrue(GitHubError.classify(500, "", null, null) is GitHubError.Kind.ServerError)
        // 原始 JSON 不该整个漏进结果，只取 message
        val other = GitHubError.classify(418, """{"documentation_url":"x"}""", null, null)
        assertTrue(other is GitHubError.Kind.Other && other.reason.isBlank())
    }
}
