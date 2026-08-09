package io.github.twitterarchiver

import io.github.twitterarchiver.util.AccountUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class AccountUtilTest {

    @Test
    fun `剥掉 at 与域名前缀`() {
        assertEquals("sjshb57", AccountUtil.normalize("@sjshb57"))
        assertEquals("sjshb57", AccountUtil.normalize("https://twitter.com/sjshb57"))
        assertEquals("sjshb57", AccountUtil.normalize("https://x.com/sjshb57/status/123"))
        assertEquals("sjshb57", AccountUtil.normalize("  @@sjshb57  "))
    }

    @Test
    fun 合法用户名() {
        assertTrue(AccountUtil.isValidHandle("sjshb57"))
        assertTrue(AccountUtil.isValidHandle("@sjshb57"))
        assertTrue(AccountUtil.isValidHandle("a"))
        assertTrue(AccountUtil.isValidHandle("_NekoKage"))
        assertTrue(AccountUtil.isValidHandle("https://x.com/sjshb57"))
    }

    @Test
    fun 不合法用户名() {
        assertFalse(AccountUtil.isValidHandle(""))
        assertFalse(AccountUtil.isValidHandle("   "))
        assertFalse(AccountUtil.isValidHandle("abcdefghijklmnop"))
        assertFalse(AccountUtil.isValidHandle("a-b"))
        assertFalse(AccountUtil.isValidHandle("中文名"))
        assertFalse(AccountUtil.isValidHandle("a b"))
    }
}
