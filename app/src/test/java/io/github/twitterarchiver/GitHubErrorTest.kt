package io.github.twitterarchiver

import io.github.twitterarchiver.data.GitHubError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val primary = GitHubError.describe(403, "", remaining = "0", resetEpoch = null)
        assertTrue(primary.contains("上限"))

        val secondary = GitHubError.describe(
            403, "", remaining = "42", resetEpoch = null, retryAfter = "30")
        assertTrue(secondary.contains("频繁"))
        assertFalse(secondary.contains("权限"))

        val noHeader = GitHubError.describe(
            403, """{"message":"You have exceeded a secondary rate limit"}""",
            remaining = "42", resetEpoch = null)
        assertTrue(noHeader.contains("频繁"))

        val forbidden = GitHubError.describe(403, "", remaining = "42", resetEpoch = null)
        assertTrue(forbidden.contains("权限"))
    }

    @Test
    fun `429 也归到频繁`() {
        assertTrue(GitHubError.describe(429, "", null, null).contains("频繁"))
    }

    @Test
    fun 常见状态码都有人话() {
        assertTrue(GitHubError.describe(401, "", null, null).contains("令牌"))
        assertTrue(GitHubError.describe(404, "", null, null).contains("不存在"))
        assertTrue(GitHubError.describe(500, "", null, null).contains("服务异常"))
        assertFalse(GitHubError.describe(404, """{"documentation_url":"x"}""", null, null)
            .contains("documentation_url"))
    }
}
