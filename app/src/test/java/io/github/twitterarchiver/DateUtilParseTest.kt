package io.github.twitterarchiver

import io.github.twitterarchiver.util.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.TimeZone

/** DateUtil 从 SimpleDateFormat 迁到 java.time 后的行为锁定测试 */
@Suppress("NonAsciiCharacters")
class DateUtilParseTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private val sep09 = 1_599_659_074_000L

    @Test
    fun ISO_带小数秒和_Z_后缀按_UTC_解析() {
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34.000Z"))
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34Z"))
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34"))
    }

    @Test
    fun ISO_尾部多余的时区偏移被忽略而不是报错() {
        assertEquals(sep09, DateUtil.epochMillis("2020-09-09T13:44:34+08:00"))
    }

    @Test
    fun 推特原始格式按字符串里的偏移量解析() {
        assertEquals(sep09, DateUtil.epochMillis("Wed Sep 09 13:44:34 +0000 2020"))
        assertEquals(sep09, DateUtil.epochMillis("Wed Sep 09 21:44:34 +0800 2020"))
    }

    @Test
    fun 旧格式的星期与日期不符时仍按日期解析() {
        assertEquals(sep09, DateUtil.epochMillis("Mon Sep 09 13:44:34 +0000 2020"))
    }

    @Test
    fun 旧格式忽略大小写() {
        assertEquals(sep09, DateUtil.epochMillis("wed sep 09 13:44:34 +0000 2020"))
        assertEquals(sep09, DateUtil.epochMillis("WED SEP 09 13:44:34 +0000 2020"))
    }

    @Test
    fun 旧格式的日允许不补零() {
        assertEquals(sep09, DateUtil.epochMillis("Wed Sep 9 13:44:34 +0000 2020"))
    }

    @Test
    fun 旧格式缺少星期前缀也能解析() {
        assertEquals(sep09, DateUtil.epochMillis("Sep 09 13:44:34 +0000 2020"))
    }

    @Test
    fun 相同日期返回同一个字符串实例() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(utc)
            val a = DateUtil.localDateOf(sep09, "")
            val b = DateUtil.localDateOf(sep09 + 60_000, "")
            assertEquals(a, b)
            assertSame("同一天的日期串应当被池化成同一个实例", a, b)
        } finally {
            TimeZone.setDefault(original)
        }
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
