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
    val dateTree: Map<String, Map<String, List<String>>> = emptyMap(),
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

    fun load(repoName: String, account: String) {
        this.repoName = repoName
        this.account = account
        viewModelScope.launch {
            _state.value = ReaderState(loading = true)
            try {
                val profile = repo.getProfile(repoName, account)
                val tweets = repo.getTweets(repoName, account)
                    .filter { it.hasFile || it.isVirtual }  // 显示实体+虚拟
                val realDated = tweets.filter { it.date.isNotBlank() }
                val tree = DateUtil.buildDateTree(realDated.map { it.date })
                _state.value = ReaderState(
                    loading = false,
                    profile = profile,
                    allTweets = tweets,
                    visibleTweets = sortTweets(tweets, false),
                    dateTree = tree
                )
            } catch (e: Exception) {
                _state.value = ReaderState(loading = false, error = "加载失败：${e.message}")
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
        val raw = q.trim()
        // 定位短码 ?t=后8位（reader.html 的分享链接格式）→ 按 tweetId 后缀匹配
        val tCode = Regex("""^\?t=(\w+)$""").find(raw)?.groupValues?.get(1)
        val res = if (tCode != null) {
            s.allTweets.filter { it.tweetId.endsWith(tCode) }
        } else {
            val lower = raw.lowercase()
            s.allTweets.filter {
                it.text.lowercase().contains(lower) ||
                    it.bodyText.lowercase().contains(lower) ||
                    it.date.contains(raw) || it.time.contains(raw) ||
                    it.tweetId.contains(raw)   // 支持按完整/部分推文 ID 搜索
            }
        }
        _state.value = s.copy(
            searchQuery = q,
            visibleTweets = sortTweets(res, s.ascending)
        )
    }

    private fun sortTweets(list: List<Tweet>, asc: Boolean): List<Tweet> =
        if (asc) list.sortedBy { it.timestamp }
        else list.sortedByDescending { it.timestamp }
}
