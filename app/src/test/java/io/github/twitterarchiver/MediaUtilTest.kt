package io.github.twitterarchiver

import io.github.twitterarchiver.util.MediaUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 路径清洗。这是安全相关的纯函数：它挡的是 profile.json / index.json 里
 * 可能出现的目录穿越，出错不会有任何可见症状。
 */
class MediaUtilTest {

    @Test
    fun `剥掉开头的上级目录`() {
        assertEquals("image/a.jpg", MediaUtil.sanitizeRelPath("../image/a.jpg"))
        assertEquals("image/a.jpg", MediaUtil.sanitizeRelPath("./image/a.jpg"))
    }

    @Test
    fun `多层上级目录也要挡住`() {
        assertEquals("x", MediaUtil.sanitizeRelPath("../../x"))
        assertEquals("etc/passwd", MediaUtil.sanitizeRelPath("../../../etc/passwd"))
    }

    @Test
    fun `夹在中间的上级目录也要挡住`() {
        assertEquals("a/b", MediaUtil.sanitizeRelPath("a/../../b"))
        assertEquals("a/b", MediaUtil.sanitizeRelPath("a/./b"))
    }

    @Test
    fun `正常路径不受影响`() {
        assertEquals("image/x.jpg", MediaUtil.sanitizeRelPath("image/x.jpg"))
        assertEquals("avatar/avatar.jpg", MediaUtil.sanitizeRelPath("avatar/avatar.jpg"))
    }

    @Test
    fun `空段与空串`() {
        assertEquals("", MediaUtil.sanitizeRelPath(""))
        assertEquals("", MediaUtil.sanitizeRelPath("../.."))
        assertEquals("a/b", MediaUtil.sanitizeRelPath("a//b"))
    }
}
