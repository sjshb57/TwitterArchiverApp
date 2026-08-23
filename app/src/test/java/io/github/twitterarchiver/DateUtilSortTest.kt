package io.github.twitterarchiver

import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.util.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间排序。存档里 ISO 与推特原始格式混存，直接按字符串比会把老格式整片排错位置；
 * 这类错误不报异常、只是顺序不对，靠肉眼在几万条里发现基本不可能，必须有测试兜住。
 */
@Suppress("NonAsciiCharacters")
class DateUtilSortTest {

    private fun tweet(id: String, ts: String) = Tweet(file = "$id.html", timestamp = ts, tweetId = id)

    @Test
    fun 两种时间格式混排时按真实时间排序() {
        val list = listOf(
            tweet("a", "2020-09-10T00:00:00.000Z"),
            tweet("b", "Wed Sep 09 13:44:34 +0000 2020"),   // 比 a 早一天
            tweet("c", "2026-05-18T09:36:48.000Z")
        )
        val asc = list.sortedWith(DateUtil.timeComparator(ascending = true) { it.epochMs })
        assertEquals(listOf("b", "a", "c"), asc.map { it.tweetId })

        val desc = list.sortedWith(DateUtil.timeComparator(ascending = false) { it.epochMs })
        assertEquals(listOf("c", "a", "b"), desc.map { it.tweetId })
    }

    @Test
    fun `字符串比较会排错——这正是要避免的`() {
        val byString = listOf(
            tweet("a", "2020-09-10T00:00:00.000Z"),
            tweet("b", "Wed Sep 09 13:44:34 +0000 2020")
        ).sortedBy { it.timestamp }
        assertEquals(listOf("a", "b"), byString.map { it.tweetId })
    }

    @Test
    fun `解析不出时间的一律排最后，升降序都是`() {
        val list = listOf(
            tweet("bad", "不是时间"),
            tweet("old", "2020-01-01T00:00:00.000Z"),
            tweet("new", "2026-01-01T00:00:00.000Z"),
            tweet("blank", "")
        )
        val asc = list.sortedWith(DateUtil.timeComparator(ascending = true) { it.epochMs })
        assertEquals(listOf("old", "new"), asc.take(2).map { it.tweetId })
        assertTrue(asc.drop(2).map { it.tweetId }.toSet() == setOf("bad", "blank"))

        val desc = list.sortedWith(DateUtil.timeComparator(ascending = false) { it.epochMs })
        assertEquals(listOf("new", "old"), desc.take(2).map { it.tweetId })
        assertTrue(desc.drop(2).map { it.tweetId }.toSet() == setOf("bad", "blank"))
    }

    @Test
    fun `epochMs 在构造时算好，不随每次读取重算`() {
        val t = tweet("x", "2026-05-18T09:36:48.000Z")
        assertTrue(t.epochMs > 0)
        assertEquals(t.epochMs, t.epochMs)
        assertEquals(DateUtil.epochMillis("2026-05-18T09:36:48.000Z"), t.epochMs)
    }

    @Test
    fun `空时间戳的 epochMs 是 0`() {
        assertEquals(0L, tweet("y", "").epochMs)
    }
}
