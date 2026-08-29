package io.github.twitterarchiver

import io.github.twitterarchiver.util.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.TimeZone

/**
 * DateUtil 从 SimpleDateFormat 迁到 java.time 后的行为锁定测试。
 *
 * 迁移的动机是删掉那套「监听 ACTION_TIMEZONE_CHANGED → 代际计数 → 让每个
 * ThreadLocal 里的格式化器失效」的机制：java.time 在 format 时才取
 * ZoneId.systemDefault()，时区变了下一次自然就对。但解析这一侧必须和原来
 * 逐字等价，否则几万条推文的时间会整片偏移或直接归零，而且不报任何错。
 */
@Suppress("NonAsciiCharacters")
class DateUtilParseTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2020-09-09T13:44:34Z 的 epoch 毫秒 */
    private val sep09 = 1_599_659_074_000L

    @Test
    fun ISO_带小数秒和_Z_后缀按_UTC_解析() {
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34.000Z"))
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34Z"))
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34"))
    }

    @Test
    fun ISO_尾部多余的时区偏移被忽略而不是报错() {
        // 旧的 SimpleDateFormat 是宽松解析，匹配完 pattern 就停、忽略尾部；
        // 这里靠先截断 19 位保持同样行为
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34+08:00"))
    }

    @Test
    fun 推特原始格式按字符串里的偏移量解析() {
        assertEquals(sep09, DateUtil.epochMillis("Wed Sep 09 13:44:34 +0000 2020"))
        // 带非零偏移：+0800 的 21:44:34 就是 UTC 的 13:44:34
        assertEquals(sep09, DateUtil.epochMillis("Wed Sep 09 21:44:34 +0800 2020"))
    }

    @Test
    fun 解析不了的时间戳返回零而不是抛异常() {
        assertEquals(0L, DateUtil.epochMillis(""))
        assertEquals(0L, DateUtil.epochMillis("   "))
        assertEquals(0L, DateUtil.epochMillis("not a date"))
        assertEquals(0L, DateUtil.epochMillis("2020-13-45T99:99:99Z"))
    }

    @Test
    fun utcMonthOf_不受系统时区影响() {
        val original = TimeZone.getDefault()
        try {
            // UTC 的 2020-09-01T00:30 在 UTC-10 是 8 月，月份分片必须按 UTC 归属
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val ms = DateUtil.epochMillis("2020-09-01T00:30:00.000Z")
            assertEquals("2020-09", DateUtil.utcMonthOf(ms))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun 本地显示在系统时区变化后立即生效() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(utc)
            val asUtc = DateUtil.localDateTime("2020-09-09T13:44:34.000Z")
            assertEquals("2020-09-09 13:44:34", asUtc)

            // 关键点：不做任何失效通知，直接改系统默认时区
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val asTokyo = DateUtil.localDateTime("2020-09-09T13:44:34.000Z")
            assertEquals("2020-09-09 22:44:34", asTokyo)
            assertNotEquals(asUtc, asTokyo)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun localDateOf_毫秒为零时回退到解析原始串() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(utc)
            assertEquals("2020-09-09", DateUtil.localDateOf(sep09, ""))
            assertEquals("2020-09-09", DateUtil.localDateOf(0L, "2020-09-09T13:44:34.000Z"))
            assertEquals("", DateUtil.localDateOf(0L, "garbage"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun localTime_按本地时区输出() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("21:44:34", DateUtil.localTime("2020-09-09T13:44:34.000Z"))
            assertEquals("", DateUtil.localTime("garbage"))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
