package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Profile
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.util.SearchUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val SEARCH_DEBOUNCE_MS = 200L

data class ReaderState(
    val loading: Boolean = true,
    val profile: Profile = Profile(),
    val allTweets: List<Tweet> = emptyList(),
    val visibleTweets: List<Tweet> = emptyList(),
    val activeDay: String? = null,
    val searchQuery: String = "",
    val ascending: Boolean = false,
    val error: String? = null
)

/** 阅读器：某账号的时间线/日期树/搜索/排序 */
class ReaderViewModel(private val repo: Repository = Repository()) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var repoName = ""
    private var account = ""

    fun load(repoName: String, account: String, forceRefresh: Boolean = false) {
        this.repoName = repoName
        this.account = account
        viewModelScope.launch {
            // 下拉刷新时保留现有内容与用户的筛选状态，避免闪白
            if (!forceRefresh) _state.value = ReaderState(loading = true)
            try {
                val profile = repo.getProfile(repoName, account, forceRefresh)
                val tweets = repo.getTweets(repoName, account, forceRefresh)
                    .filter { it.hasFile || it.isVirtual }
                val prev = _state.value
                _state.value = prev.copy(
                    loading = false,
                    error = null,
                    profile = profile,
                    allTweets = tweets,
                    visibleTweets = applyFilters(tweets, prev)
                )
            } catch (e: Exception) {
                if (forceRefresh) _state.value = _state.value.copy(loading = false)
                else _state.value = ReaderState(loading = false, error = "加载失败：${e.message}")
            }
        }
    }

    /** 选择某一天，只显示那天的推文 */
    fun selectDay(day: String?) {
        val s = _state.value
        val list = if (day == null) s.allTweets
        else s.allTweets.filter { it.date == day }
        _state.value = s.copy(
            activeDay = day,
            searchQuery = "",
            visibleTweets = sortTweets(list, s.ascending)
        )
    }

    /** 搜索 */
    private var searchJob: Job? = null

    fun search(q: String) {
        searchJob?.cancel()
        val s = _state.value
        if (q.isBlank()) {
            selectDay(s.activeDay)
            _state.value = _state.value.copy(searchQuery = "")
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS.milliseconds)
            val hit = withContext(Dispatchers.Default) { matchQuery(s.allTweets, q) }
            _state.value = _state.value.copy(
                searchQuery = q,
                visibleTweets = sortTweets(hit, s.ascending)
            )
        }
    }

    /** 切换时间排序：旧→新 / 新→旧 */
    fun toggleSort() {
        val s = _state.value
        _state.value = s.copy(
            ascending = !s.ascending,
            visibleTweets = sortTweets(s.visibleTweets, !s.ascending)
        )
    }

    private fun matchQuery(list: List<Tweet>, q: String): List<Tweet> {
        // 定位短码 ?t=后8位（reader.html 的分享链接格式）→ 按 tweetId 后缀匹配
        SearchUtil.extractTCode(q.trim())?.let { code ->
            return list.filter { it.tweetId.endsWith(code) }
        }
        if (SearchUtil.isFullTweetId(q.trim())) return list.filter { it.tweetId == q.trim() }
        val hasAscii = SearchUtil.hasAsciiLetter(q)
        val lower = q.lowercase(java.util.Locale.ROOT)
        return list.filter {
            SearchUtil.matches(it.text, q, lower, hasAscii) ||
                SearchUtil.matches(it.bodyText, q, lower, hasAscii) ||
                it.date.contains(q) || it.time.contains(q) ||
                it.tweetId.contains(q)   // 支持按完整/部分推文 ID 搜索
        }
    }

    /** 刷新后按用户当前的日期/搜索/排序重建可见列表 */
    private fun applyFilters(all: List<Tweet>, prev: ReaderState): List<Tweet> {
        val byDay = if (prev.activeDay == null) all else all.filter { it.date == prev.activeDay }
        val res = if (prev.searchQuery.isBlank()) byDay else matchQuery(all, prev.searchQuery)
        return sortTweets(res, prev.ascending)
    }

    private fun sortTweets(list: List<Tweet>, asc: Boolean): List<Tweet> =
        if (asc) list.sortedBy { it.timestamp }
        else list.sortedByDescending { it.timestamp }
}
