package io.github.twitterarchiver

import io.github.twitterarchiver.data.CrossReply
import io.github.twitterarchiver.data.GitHubApi
import io.github.twitterarchiver.data.GlobalIndexMeta
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.GlobalShard
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.viewmodel.GlobalTimelineViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 搜索收窄缓存的回归测试。
 *
 * 曾经的 bug：computeFiltered 有个增量收窄优化——用户在原查询后追加字符时，
 * 直接在上次结果里继续筛。但 rebuildFromLoaded 重建总表后没有让这份缓存失效，
 * 于是「搜索词不变 + 下载新月份」时 canNarrow 依然成立，筛选源仍是下载前的
 * 旧结果集，新月份里的匹配项一条都不会出现。
 *
 * 这个错误不抛异常、界面也一切正常，只是结果少了几条，靠肉眼发现基本不可能。
 */
@Suppress("NonAsciiCharacters")
class GlobalSearchNarrowTest {

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @After
    fun tearDown() = Dispatchers.resetMain()

    private val acct = IndexAccount(r = "repo", a = "acct", n = "某人", u = "@someone", av = "")

    private fun post(id: String, ts: String, text: String) = GlobalPost(
        acctIndex = 0, text = text, tweetId = id, time = ts,
        media = emptyList(), replyCount = 0, hasQuote = false, account = acct
    )

    private val shard0404 = GlobalShard(month = "2026-04", count = 1, bytes = 100, hash = "h4")
    private val shard0505 = GlobalShard(month = "2026-05", count = 1, bytes = 100, hash = "h5")

    /** 近期时间线只有 5 月那条；4 月那条要下载分片才拿得到，两条都含「橘子」 */
    private class FakeApi(
        private val recent: List<GlobalPost>,
        private val shardPosts: Map<String, List<GlobalPost>>,
        private val meta: GlobalIndexMeta
    ) : GitHubApi() {
        override suspend fun fetchRecentTimeline(): Pair<List<IndexAccount>, List<GlobalPost>> =
            meta.accounts to recent

        override suspend fun fetchGlobalMeta(): GlobalIndexMeta = meta
        override suspend fun fetchCrossReplies(): Map<String, List<CrossReply>> = emptyMap()
        override suspend fun fetchGlobalShard(
            shard: GlobalShard,
            accounts: List<IndexAccount>,
            onProgress: ((Long) -> Unit)?
        ): List<GlobalPost> = shardPosts[shard.month].orEmpty()
    }

    private suspend fun onMain(block: () -> Unit) =
        withContext(Dispatchers.Main) { block() }

    private suspend fun waitFor(
        timeoutMs: Long = 5_000,
        what: String = "条件",
        cond: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(5)
        assertTrue("等待超时：$what", cond())
    }

    private fun newVm(): GlobalTimelineViewModel {
        val may = post("m1", "2026-05-18T09:36:48.000Z", "五月的橘子")
        val apr = post("a1", "2026-04-10T08:00:00.000Z", "四月的橘子")
        val meta = GlobalIndexMeta(
            total = 2,
            generated = "2026-08-24T00:00:00Z",
            accounts = listOf(acct),
            shards = listOf(shard0505, shard0404)
        )
        return GlobalTimelineViewModel(
            FakeApi(listOf(may), mapOf("2026-04" to listOf(apr)), meta)
        )
    }

    @Test
    fun `下载新月份后，同一搜索词要能搜到新月份里的内容`() = runBlocking {
        val vm = newVm()
        onMain { vm.load() }
        waitFor(what = "首次加载完成") { vm.state.value.shards.size == 2 && !vm.state.value.loading }

        // 先搜一次，让收窄缓存里存下「只有 5 月那条」的结果
        onMain { vm.search("橘子") }
        waitFor(what = "首次搜索出结果") { vm.state.value.searchTotal == 1 }
        assertEquals(listOf("m1"), vm.state.value.visible.map { it.tweetId })

        // 保持搜索词不变，下载 4 月分片
        onMain { vm.downloadMonth("2026-04") }
        waitFor(what = "4 月的匹配项进入结果") { vm.state.value.searchTotal == 2 }

        assertEquals(
            listOf("m1", "a1"),
            vm.state.value.visible.map { it.tweetId }
        )
    }

    @Test
    fun `追加字符时的收窄仍然有效，不会把结果搞丢`() = runBlocking {
        val vm = newVm()
        // 必须等 meta 到位：分片清单没填回来时 downloadMonth 找不到 shard，是空操作
        onMain { vm.load() }
        waitFor(what = "首次加载完成") { vm.state.value.shards.size == 2 && !vm.state.value.loading }

        onMain { vm.downloadMonth("2026-04") }
        waitFor(what = "4 月合入总表") { vm.state.value.totalCount == 2 }

        onMain { vm.search("橘") }
        waitFor(what = "宽查询命中两条") { vm.state.value.searchTotal == 2 }

        // 在原查询后追加字符：走收窄路径，结果应当是上一次结果的子集且不丢项
        onMain { vm.search("橘子") }
        waitFor(what = "窄查询仍命中两条") { vm.state.value.searchTotal == 2 }
        assertEquals(setOf("m1", "a1"), vm.state.value.visible.map { it.tweetId }.toSet())
    }
}
