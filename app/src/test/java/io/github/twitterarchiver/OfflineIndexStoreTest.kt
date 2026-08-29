package io.github.twitterarchiver

import io.github.twitterarchiver.data.AppDirs
import io.github.twitterarchiver.data.GitHubApi
import io.github.twitterarchiver.data.IndexManifest
import io.github.twitterarchiver.data.LocalIndexState
import io.github.twitterarchiver.data.MonthSpan
import io.github.twitterarchiver.data.OfflineIndexStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 离线索引的策略序列与状态文件一致性测试。
 *
 * 这块是全项目最容易出隐藏 bug 的地方：回退路径多、_state.json 要和磁盘上的
 * 月份文件保持一致，出错时不抛异常，只是内容旧了或每次都重下一遍，
 * 而项目里几乎没有日志，线上根本发现不了。
 */
@Suppress("NonAsciiCharacters")
class OfflineIndexStoreTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("offline-index", "").apply {
            delete()
            mkdirs()
        }
        AppDirs.setRootForTest(tmp)
    }

    @After
    fun tearDown() {
        AppDirs.setRootForTest(null)
        tmp.deleteRecursively()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 与 OfflineIndexStore.sha16 同口径：SHA-256 取前 8 字节的十六进制 */
    private fun sha16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8).joinToString("") { "%02x".format(it) }

    private fun record(id: String, month: String) =
        """{"tweet_id":"$id","timestamp":"$month-01T00:00:00.000Z"}"""

    /** 两条记录、两个月份，5 月在前（索引本来就是时间倒序） */
    private val indexJson = "[${record("m1", "2026-05")},${record("a1", "2026-04")}]"

    private fun blockOf(month: String): ByteArray {
        val id = if (month == "2026-05") "m1" else "a1"
        return record(id, month).toByteArray()
    }

    private fun offsetOf(month: String): Long =
        indexJson.indexOf(String(blockOf(month))).toLong()

    private fun dirFor(repo: String, account: String) = File(tmp, "index_cache/$repo/$account")

    private fun stateOf(repo: String, account: String): LocalIndexState =
        json.decodeFromString(File(dirFor(repo, account), "_state.json").readText())

    private class FakeApi(
        var manifest: IndexManifest? = null,
        var whole: ByteArray? = null,
        var ranges: MutableMap<String, Pair<Int, ByteArray>?> = mutableMapOf(),
        var monthOfOffset: (Long) -> String = { "" },
    ) : GitHubApi() {
        var wholeCalls = 0
        var rangeCalls = 0

        override suspend fun fetchIndexManifest(repo: String): IndexManifest? = manifest

        override suspend fun fetchIndexBytes(repo: String, account: String): ByteArray {
            wholeCalls++
            return whole ?: error("离线")
        }

        override suspend fun fetchIndexRange(
            repo: String, account: String, offset: Long, length: Long
        ): Pair<Int, ByteArray>? {
            rangeCalls++
            return ranges[monthOfOffset(offset)]
        }
    }

    private fun manifestOf(range: Boolean, months: Map<String, String>) = IndexManifest(
        repo = "repo",
        account = "acct",
        range = range,
        total = months.size,
        months = months.entries.associate { (m, hash) ->
            m to MonthSpan(
                offset = offsetOf(m),
                length = blockOf(m).size.toLong(),
                count = 1,
                hash = hash,
            )
        },
    )

    @Test
    fun 全量下载会按月切分落盘并能读回() = runBlocking {
        val api = FakeApi(manifest = null, whole = indexJson.toByteArray())
        val out = OfflineIndexStore(api).load("repo", "acct")

        assertNotNull(out)
        assertEquals(listOf("m1", "a1"), out!!.map { it.tweetId })
        val dir = dirFor("repo", "acct")
        assertTrue("应生成 2026-05.json", File(dir, "2026-05.json").isFile)
        assertTrue("应生成 2026-04.json", File(dir, "2026-04.json").isFile)
    }

    @Test
    fun 没有清单时不把客户端自算的哈希写进状态文件() = runBlocking {
        // 客户端切分边界（前导逗号、尾随空白）未必和服务端一致，写进去会让
        // 下次比对把每个月都判成 changed，增量更新永久退化成全量
        val api = FakeApi(manifest = null, whole = indexJson.toByteArray())
        OfflineIndexStore(api).load("repo", "acct")

        assertEquals(emptyMap<String, String>(), stateOf("repo", "acct").months)
    }

    @Test
    fun 有清单的全量下载写入服务端口径的哈希() = runBlocking {
        val hashes = mapOf("2026-05" to "server5", "2026-04" to "server4")
        // range=false 让增量策略直接放弃，走全量
        val api = FakeApi(
            manifest = manifestOf(range = false, months = hashes),
            whole = indexJson.toByteArray()
        )
        OfflineIndexStore(api).load("repo", "acct")

        assertEquals(hashes, stateOf("repo", "acct").months)
    }

    @Test
    fun 增量同步成功时不会再触发全量下载() = runBlocking {
        val dir = dirFor("repo", "acct").apply { mkdirs() }
        File(dir, "2026-05.json").writeBytes(blockOf("2026-05"))
        File(dir, "2026-04.json").writeBytes(blockOf("2026-04"))
        File(dir, "_state.json").writeText(
            """{"months":{"2026-05":"old5","2026-04":"new4"},"total":2}"""
        )

        val new5 = blockOf("2026-05")
        val api = FakeApi(
            manifest = manifestOf(true, mapOf("2026-05" to sha16(new5), "2026-04" to "new4")),
            ranges = mutableMapOf("2026-05" to (206 to new5)),
        )
        api.monthOfOffset = { off -> if (off == offsetOf("2026-05")) "2026-05" else "2026-04" }

        val out = OfflineIndexStore(api).load("repo", "acct")

        assertNotNull(out)
        assertEquals(0, api.wholeCalls)
        assertEquals("只应重下变化的那一个月", 1, api.rangeCalls)
        assertEquals(sha16(new5), stateOf("repo", "acct").months["2026-05"])
    }

    @Test
    fun 增量中途失败时状态文件与磁盘保持一致() = runBlocking {
        // 回归 §1.4：以前 writeState 在整轮循环之后，中途失败会留下
        // 「月份文件部分是新的、_state.json 全是旧的」的混合状态
        val dir = dirFor("repo", "acct").apply { mkdirs() }
        File(dir, "2026-05.json").writeBytes(blockOf("2026-05"))
        File(dir, "2026-04.json").writeBytes(blockOf("2026-04"))
        File(dir, "_state.json").writeText(
            """{"months":{"2026-05":"old5","2026-04":"old4"},"total":2}"""
        )

        val new5 = blockOf("2026-05")
        val api = FakeApi(
            manifest = manifestOf(true, mapOf("2026-05" to sha16(new5), "2026-04" to "new4")),
            whole = null,   // 全量兜底也失败，这样才观察得到增量留下的中间状态
            ranges = mutableMapOf(
                "2026-05" to (206 to new5),
                "2026-04" to null,          // 第二个月请求失败
            ),
        )
        api.monthOfOffset = { off -> if (off == offsetOf("2026-05")) "2026-05" else "2026-04" }

        OfflineIndexStore(api).load("repo", "acct")

        val state = stateOf("repo", "acct")
        assertEquals("已成功写盘的月份，状态必须同步更新", sha16(new5), state.months["2026-05"])
        assertEquals("未成功的月份，状态必须保持旧值以便下次重试", "old4", state.months["2026-04"])
    }

    @Test
    fun 全部策略都失败时回退读本地() = runBlocking {
        val dir = dirFor("repo", "acct").apply { mkdirs() }
        File(dir, "2026-05.json").writeBytes(blockOf("2026-05"))

        val api = FakeApi(manifest = null, whole = null)   // 清单没有、全量抛异常
        val out = OfflineIndexStore(api).load("repo", "acct")

        assertNotNull("应回退到本地副本", out)
        assertEquals(listOf("m1"), out!!.map { it.tweetId })
    }

    @Test
    fun 本地为空且网络失败时返回空() = runBlocking {
        val api = FakeApi(manifest = null, whole = null)
        assertNull(OfflineIndexStore(api).load("repo", "acct"))
    }
}
