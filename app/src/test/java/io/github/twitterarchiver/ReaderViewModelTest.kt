package io.github.twitterarchiver

import io.github.twitterarchiver.data.Profile
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.viewmodel.ReaderViewModel
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
 * ReaderViewModel 的状态机。
 *
 * 这一层此前零覆盖，而排序方向、搜索去抖、按日筛选这三处恰好都出过 bug——
 * 它们的错误形态是"顺序不对""结果对不上"，不抛异常，靠人眼在几万条里发现不了。
 *
 * 用真实调度器 + 轮询而不是虚拟时间：ReaderViewModel 内部会跳
 * Dispatchers.Default（搜索）和 Dispatchers.IO（仓储），虚拟时间管不到它们，
 * advanceUntilIdle() 会在这些跳转处提前返回，断言就打在半成品状态上。
 */
@Suppress("NonAsciiCharacters")
class ReaderViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun tweet(id: String, ts: String, date: String, text: String = "") =
        Tweet(file = "$id.html", timestamp = ts, date = date, tweetId = id, text = text)

    /** 可控数据源：内容随时可换，用来构造"加载中途数据变了"这类时序 */
    private class FakeRepo(@Volatile var tweets: List<Tweet>) : Repository() {
        override suspend fun getTweets(repo: String, account: String, forceRefresh: Boolean) = tweets
        override suspend fun getProfile(repo: String, account: String, forceRefresh: Boolean) = Profile()
    }

    private val sample = listOf(
        tweet("a", "2020-09-10T00:00:00.000Z", "2020-09-10", "早期内容"),
        tweet("b", "Wed Sep 09 13:44:34 +0000 2020", "2020-09-09", "老格式那一条"),
        tweet("c", "2026-05-18T09:36:48.000Z", "2026-05-18", "最近的一条")
    )

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

    private suspend fun loaded(vm: ReaderViewModel, repo: FakeRepo) {
        onMain { vm.load("repo", "acct") }
        waitFor(what = "首次加载完成") {
            !vm.state.value.loading && vm.state.value.allTweets.size == repo.tweets.size
        }
    }

    @Test
    fun `默认降序，老格式时间戳按真实时间排而不是按字符串`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)
        assertEquals(listOf("c", "a", "b"), vm.state.value.visibleTweets.map { it.tweetId })
    }

    @Test
    fun `toggleSort 翻转顺序`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)

        onMain { vm.toggleSort() }
        assertTrue(vm.state.value.ascending)
        assertEquals(listOf("b", "a", "c"), vm.state.value.visibleTweets.map { it.tweetId })

        onMain { vm.toggleSort() }
        assertEquals(listOf("c", "a", "b"), vm.state.value.visibleTweets.map { it.tweetId })
    }

    @Test
    fun `时间解析不出来的一条，升序降序都排最后`() = runBlocking {
        val repo = FakeRepo(sample + tweet("x", "不是时间", "2021-01-01"))
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)
        assertEquals("x", vm.state.value.visibleTweets.last().tweetId)

        onMain { vm.toggleSort() }
        assertEquals("x", vm.state.value.visibleTweets.last().tweetId)
    }

    @Test
    fun `selectDay 按日筛选，传 null 恢复全部`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)

        onMain { vm.selectDay("2026-05-18") }
        assertEquals(listOf("c"), vm.state.value.visibleTweets.map { it.tweetId })
        assertEquals("2026-05-18", vm.state.value.activeDay)

        onMain { vm.selectDay(null) }
        assertEquals(3, vm.state.value.visibleTweets.size)
        assertEquals(null, vm.state.value.activeDay)
    }

    @Test
    fun `搜索去抖期间数据被换掉，结果必须基于新数据`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)

        onMain { vm.search("新来的") }
        repo.tweets = sample + tweet("z", "2026-06-01T00:00:00.000Z", "2026-06-01", "新来的一条")
        onMain { vm.load("repo", "acct") }
        waitFor(what = "第二次加载完成") { vm.state.value.allTweets.size == 4 }

        waitFor(what = "去抖后的搜索结果") { vm.state.value.searchQuery == "新来的" }
        assertEquals(listOf("z"), vm.state.value.visibleTweets.map { it.tweetId })
    }

    @Test
    fun `搜索清空后恢复全部，不留残留 query`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)

        onMain { vm.search("最近") }
        waitFor(what = "搜索生效") { vm.state.value.searchQuery == "最近" }
        assertEquals(listOf("c"), vm.state.value.visibleTweets.map { it.tweetId })

        onMain { vm.search("") }
        assertEquals("", vm.state.value.searchQuery)
        assertEquals(3, vm.state.value.visibleTweets.size)
    }
}
