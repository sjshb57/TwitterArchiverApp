package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.util.MediaUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 媒体类型 */
enum class MediaType { IMAGE, VIDEO, OTHER }

data class MediaItem(
    val url: String,
    val type: MediaType
)

data class ImagesState(
    val loading: Boolean = true,
    val all: List<MediaItem> = emptyList(),
    val error: String? = null
)

/** 某账号的全部媒体（图片 + 视频 + 其他如 GIF），按类型可筛选 */
class ImagesViewModel(private val repo: Repository = Repository()) : ViewModel() {

    private val _state = MutableStateFlow(ImagesState())
    val state: StateFlow<ImagesState> = _state.asStateFlow()

    fun load(repoName: String, account: String) {
        viewModelScope.launch {
            _state.value = ImagesState(loading = true)
            try {
                val tweets = repo.getTweets(repoName, account)
                    .filter { it.hasFile }
                    .sortedByDescending { it.timestamp }
                val items = mutableListOf<MediaItem>()
                for (t in tweets) {
                    MediaUtil.resolveImages(repoName, account, t.images).forEach {
                        items.add(MediaItem(it, classify(it)))
                    }
                    (t.wantedVideos + t.embeddedVideos).forEach { rel ->
                        val url = MediaUtil.resolveAsset(repoName, account, rel)
                        items.add(MediaItem(url, MediaType.VIDEO))
                    }
                }
                _state.value = ImagesState(loading = false, all = items)
            } catch (e: Exception) {
                _state.value = ImagesState(loading = false, error = "加载失败：${e.message}")
            }
        }
    }

    private fun classify(url: String): MediaType {
        val lower = url.substringAfterLast('.').lowercase()
        return when (lower) {
            "jpg", "jpeg", "png", "webp", "bmp" -> MediaType.IMAGE
            "mp4", "mov", "webm", "m4v" -> MediaType.VIDEO
            "gif" -> MediaType.OTHER
            else -> MediaType.OTHER
        }
    }
}
