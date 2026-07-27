package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileStats(
    val loading: Boolean = true,
    val tweets: Int = 0,
    val images: Int = 0
)

class ProfileStatsViewModel(private val repo: Repository = Repository()) : ViewModel() {

    private val _stats = MutableStateFlow(ProfileStats())
    val stats: StateFlow<ProfileStats> = _stats.asStateFlow()

    fun load(repoName: String, account: String) {
        _stats.value = ProfileStats(loading = true)
        viewModelScope.launch {
            try {
                val tweets = repo.getTweets(repoName, account)
                val realTweets = tweets.filter { it.hasFile }
                _stats.value = ProfileStats(
                    loading = false,
                    tweets = realTweets.size,
                    images = realTweets.sumOf { it.images.size }
                )
            } catch (e: Exception) {
                _stats.value = ProfileStats(loading = false, tweets = 0, images = 0)
            }
        }
    }
}
