package io.github.twitterarchiver

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
@Suppress("NonAsciiCharacters")
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
    fun 定位短码提取() {
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
        assertTrue(SearchUtil.matches("Hello World", "hello", "hello", true))
        assertTrue(SearchUtil.matches("HELLO", "hello", "hello", true))
    }
}
