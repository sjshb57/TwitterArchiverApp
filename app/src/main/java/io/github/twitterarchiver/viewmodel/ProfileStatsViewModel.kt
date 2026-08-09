package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

data class ProfileStats(
    val loading: Boolean = true,
    val tweets: Int = 0,
    val images: Int = 0,
    /** 这份数字属于哪个账号，用于避免显示上一个账号的结果 */
    val key: String = ""
)

/** 账号统计。全应用一个实例，内部按 repo/account 缓存结果 */
class ProfileStatsViewModel(private val repo: Repository = Repository.shared) : ViewModel() {

    private val _stats = MutableStateFlow(ProfileStats())
    val stats: StateFlow<ProfileStats> = _stats.asStateFlow()

    private val cache = java.util.concurrent.ConcurrentHashMap<String, ProfileStats>()
    private var job: Job? = null

    fun load(repoName: String, account: String) {
        val key = "$repoName/$account"
        cache[key]?.let { _stats.value = it; return }

        job?.cancel()
        _stats.value = ProfileStats(loading = true, key = key)
        job = viewModelScope.launch {
            val result = try {
                val realTweets = repo.getTweets(repoName, account).filter { it.hasFile }
                ProfileStats(
                    loading = false,
                    tweets = realTweets.size,
                    images = realTweets.sumOf { it.images.size },
                    key = key
                )
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                ProfileStats(loading = false, tweets = 0, images = 0, key = key)
            }
            cache[key] = result
            _stats.value = result
        }
    }
}
