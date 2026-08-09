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
    val images: Int = 0
)

/**
 * 账号统计。全应用一个实例，内部按 repo/account 缓存结果。
 *
 * 之前是 viewModel(key = "stats_repo_account")，每看一个账号就在 Activity 的
 * ViewModelStore 上永久留下一个 ViewModel，浏览 50 个账号就是 50 个，退出弹窗也不清。
 */
class ProfileStatsViewModel(private val repo: Repository = Repository.shared) : ViewModel() {

    private val _stats = MutableStateFlow(ProfileStats())
    val stats: StateFlow<ProfileStats> = _stats.asStateFlow()

    /** 算过的结果留着，重复打开同一个账号不必再算 */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ProfileStats>()
    private var job: Job? = null

    fun load(repoName: String, account: String) {
        val key = "$repoName/$account"
        cache[key]?.let { _stats.value = it; return }

        job?.cancel()
        _stats.value = ProfileStats(loading = true)
        job = viewModelScope.launch {
            val result = try {
                val realTweets = repo.getTweets(repoName, account).filter { it.hasFile }
                ProfileStats(
                    loading = false,
                    tweets = realTweets.size,
                    images = realTweets.sumOf { it.images.size }
                )
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                ProfileStats(loading = false, tweets = 0, images = 0)
            }
            cache[key] = result
            _stats.value = result
        }
    }
}
