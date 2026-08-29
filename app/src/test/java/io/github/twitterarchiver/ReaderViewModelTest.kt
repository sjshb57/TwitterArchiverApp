package io.github.twitterarchiver

import io.github.twitterarchiver.data.Profile
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.util.DateUtil
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

@Suppress("NonAsciiCharacters")
class ReaderViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Default)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun tweet(id: String, ts: String, date: String, text: String = "") =
        Tweet(file = "$id.html", timestamp = ts, date = date, tweetId = id, text = text)

    private class FakeRepo(@Volatile var tweets: List<Tweet>) : Repository() {
        override suspend fun getTweets(repo: String, account: String, forceRefresh: Boolean) = tweets
        override suspend fun getProfile(repo: String, account: String, forceRefresh: Boolean) = Profile()
    }

    private val sample = listOf(
        tweet("a", "2020-09-10T00:00:00.000Z", "2020-09-10", "早期内容"),
        tweet("b", "Wed Sep 09 13:44:34 +0000 2020", "Wed Sep 09", "老格式那一条"),
        tweet("c", "2026-05-18T09:36:48.000Z", "2026-05-18", "最近的一条")
    )

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

    private fun dayOf(t: Tweet) = DateUtil.localDateOf(t.epochMs, t.timestamp)

    @Test
    fun `selectDay 按日筛选，传 null 恢复全部`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)

        val day = dayOf(sample[2])
        onMain { vm.selectDay(day) }
        assertEquals(listOf("c"), vm.state.value.visibleTweets.map { it.tweetId })
        assertEquals(day, vm.state.value.activeDay)

        onMain { vm.selectDay(null) }
        assertEquals(3, vm.state.value.visibleTweets.size)
        assertEquals(null, vm.state.value.activeDay)
    }

    @Test
    fun `按日筛选用本地日期，老格式记录也筛得出来`() = runBlocking {
        val repo = FakeRepo(sample)
        val vm = ReaderViewModel(repo)
        loaded(vm, repo)

        val legacy = sample[1]
        onMain { vm.selectDay(dayOf(legacy)) }
        assertEquals(listOf("b"), vm.state.value.visibleTweets.map { it.tweetId })

        onMain { vm.selectDay(legacy.date) }
        assertEquals(emptyList<String>(), vm.state.value.visibleTweets.map { it.tweetId })
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
