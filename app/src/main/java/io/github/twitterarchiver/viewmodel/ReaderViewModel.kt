package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Profile
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.util.DateUtil
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings
import kotlinx.coroutines.flow.update

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
class ReaderViewModel(private val repo: Repository = Repository.shared) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var repoName = ""
    private var account = ""

    fun load(repoName: String, account: String, forceRefresh: Boolean = false) {
        this.repoName = repoName
        this.account = account
        viewModelScope.launch {
            if (!forceRefresh) _state.value = ReaderState(loading = true)
            try {
                val profile = repo.getProfile(repoName, account, forceRefresh)
                val tweets = repo.getTweets(repoName, account, forceRefresh)
                    .filter { it.hasFile || it.isVirtual }
                _state.update { prev -> prev.copy(
                    loading = false,
                    error = null,
                    profile = profile,
                    allTweets = tweets,
                    visibleTweets = applyFilters(tweets, prev)
                ) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (forceRefresh) _state.update { it.copy(loading = false) }
                else _state.value = ReaderState(loading = false, error = AppStrings.get(R.string.load_failed, e.message.orEmpty()))
            }
        }
    }

    /** 选择某一天，只显示那天的推文 */
    fun selectDay(day: String?) {
        _state.update { s ->
            val list = if (day == null) s.allTweets else s.allTweets.filter { it.date == day }
            s.copy(
                activeDay = day,
                searchQuery = "",
                visibleTweets = sortTweets(list, s.ascending)
            )
        }
    }

    /** 搜索 */
    private var searchJob: Job? = null

    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            selectDay(_state.value.activeDay)
            _state.update { it.copy(searchQuery = "") }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS.milliseconds)
            val s = _state.value
            val hit = withContext(Dispatchers.Default) { matchQuery(s.allTweets, q) }
            _state.update { it.copy(
                searchQuery = q,
                visibleTweets = sortTweets(hit, s.ascending)
            ) }
        }
    }

    /** 切换时间排序：旧→新 / 新→旧 */
    fun toggleSort() {
        _state.update { s -> s.copy(
            ascending = !s.ascending,
            visibleTweets = sortTweets(s.visibleTweets, !s.ascending)
        ) }
    }

    private fun matchQuery(list: List<Tweet>, q: String): List<Tweet> {
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
                it.tweetId.contains(q)
        }
    }

    /** 刷新后按用户当前的日期/搜索/排序重建可见列表 */
    private fun applyFilters(all: List<Tweet>, prev: ReaderState): List<Tweet> {
        val byDay = if (prev.activeDay == null) all else all.filter { it.date == prev.activeDay }
        val res = if (prev.searchQuery.isBlank()) byDay else matchQuery(all, prev.searchQuery)
        return sortTweets(res, prev.ascending)
    }

    private fun sortTweets(list: List<Tweet>, asc: Boolean): List<Tweet> =
        list.sortedWith(DateUtil.timeComparator(asc) { it.epochMs })
}
