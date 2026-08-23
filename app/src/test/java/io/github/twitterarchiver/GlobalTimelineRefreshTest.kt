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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 全站页下拉刷新的回归测试。
 *
 * 曾经的 bug：load(force = true) 把整个 GlobalState 重建成默认值，
 * 而 fullLoaded 又不重置，导致 loadFull() 短路不再填回——
 * 日期树变空、已下载月份标记消失、全站总数归零，且不重启不恢复。
 * 这类问题不抛异常，只有真的下拉一次才看得见，所以必须由测试守住。
 */
@Suppress("NonAsciiCharacters")
class GlobalTimelineRefreshTest {

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @After fun tearDown() = Dispatchers.resetMain()

    private val acct = IndexAccount(r = "repo", a = "acct", n = "某人", u = "@someone", av = "")

    private fun post(id: String, ts: String) = GlobalPost(
        acctIndex = 0, text = "内容 $id", tweetId = id, time = ts,
        media = emptyList(), replyCount = 0, hasQuote = false, account = acct
    )

    private fun meta(total: Int) = GlobalIndexMeta(
        total = total,
        generated = "2026-08-24T00:00:00Z",
        accounts = listOf(acct),
        shards = listOf(
            GlobalShard(month = "2026-05", count = 10, bytes = 1000, hash = "h1"),
            GlobalShard(month = "2026-04", count = 8, bytes = 900, hash = "h2")
        )
    )

    private class FakeApi(
        @Volatile var posts: List<GlobalPost>,
        @Volatile var meta: GlobalIndexMeta
    ) : GitHubApi() {
        @Volatile var recentCalls = 0
        @Volatile var metaCalls = 0
        override suspend fun fetchRecentTimeline(): Pair<List<IndexAccount>, List<GlobalPost>> {
            recentCalls++
            return listOf(posts.first().account) to posts
        }
        override suspend fun fetchGlobalMeta(): GlobalIndexMeta {
            metaCalls++
            return meta
        }
        override suspend fun fetchCrossReplies(): Map<String, List<CrossReply>> = emptyMap()
    }

    /**
     * VM 的方法在生产环境一律从 Main 调用，字段也据此按 Main 单线程约束设计
     * （recentLoaded / fullLoaded 都不是 volatile）。测试若从 runBlocking 所在线程
     * 直接调，就会读到过期值——那是测试的错，不是被测代码的错。
     */
    private suspend fun onMain(block: () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    private suspend fun waitFor(timeoutMs: Long = 5_000, what: String = "条件", cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(5)
        assertTrue("等待超时：$what", cond())
    }

    @Test
    fun `下拉刷新不会清空分片清单与全站总数`() = runBlocking {
        val api = FakeApi(listOf(post("a", "2026-05-18T09:36:48.000Z")), meta(480_000))
        val vm = GlobalTimelineViewModel(api)

        onMain { vm.load() }
        waitFor(what = "首次加载完成") { vm.state.value.shards.size == 2 && !vm.state.value.loading }
        assertEquals(480_000, vm.state.value.globalTotal)

        onMain { vm.load(force = true) }
        waitFor(what = "刷新重取 meta") { api.metaCalls == 2 }
        waitFor(what = "刷新结束") { !vm.state.value.loading }

        // 核心断言：刷新之后这些必须还在
        assertEquals(2, vm.state.value.shards.size)
        assertEquals(480_000, vm.state.value.globalTotal)
        assertTrue(vm.state.value.visible.isNotEmpty())
    }

    @Test
    fun `下拉刷新会真的重新取数据，而不是空转`() = runBlocking {
        val api = FakeApi(listOf(post("a", "2026-05-18T09:36:48.000Z")), meta(100))
        val vm = GlobalTimelineViewModel(api)

        onMain { vm.load() }
        waitFor(what = "首次加载完成") { vm.state.value.shards.size == 2 && !vm.state.value.loading }
        assertEquals(1, api.recentCalls)
        assertEquals(1, api.metaCalls)

        api.posts = listOf(
            post("b", "2026-05-19T10:00:00.000Z"),
            post("a", "2026-05-18T09:36:48.000Z")
        )
        api.meta = meta(200)

        onMain { vm.load(force = true) }
        waitFor(what = "刷新重取 meta") { api.metaCalls == 2 }
        waitFor(what = "新数据进入列表") { vm.state.value.visible.size == 2 }

        assertEquals(2, api.recentCalls)
        assertEquals(200, vm.state.value.globalTotal)
        assertEquals(listOf("b", "a"), vm.state.value.visible.map { it.tweetId })
    }

    @Test
    fun `非强制的重复 load 是空操作，不重复打网络`() = runBlocking {
        val api = FakeApi(listOf(post("a", "2026-05-18T09:36:48.000Z")), meta(100))
        val vm = GlobalTimelineViewModel(api)

        onMain { vm.load() }
        waitFor(what = "首次加载完成") { vm.state.value.shards.size == 2 && !vm.state.value.loading }

        onMain { vm.load() }
        onMain { vm.load() }
        delay(200)
        assertEquals(1, api.recentCalls)
    }
}
