package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Profile
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    fun search(q: String) {
        val s = _state.value
        if (q.isBlank()) {
            selectDay(s.activeDay)
            _state.value = _state.value.copy(searchQuery = "")
            return
        }
        _state.value = s.copy(
            searchQuery = q,
            visibleTweets = sortTweets(matchQuery(s.allTweets, q), s.ascending)
        )
    }

    private fun matchQuery(list: List<Tweet>, q: String): List<Tweet> {
        val raw = q.trim()
        // 定位短码 ?t=后8位（reader.html 的分享链接格式）→ 按 tweetId 后缀匹配
        val tCode = Regex("""^\?t=(\w+)$""").find(raw)?.groupValues?.get(1)
        if (tCode != null) return list.filter { it.tweetId.endsWith(tCode) }
        val lower = raw.lowercase()
        return list.filter {
            it.text.lowercase().contains(lower) ||
                it.bodyText.lowercase().contains(lower) ||
                it.date.contains(raw) || it.time.contains(raw) ||
                it.tweetId.contains(raw)   // 支持按完整/部分推文 ID 搜索
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
