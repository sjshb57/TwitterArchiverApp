package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.ArchiveRepo
import io.github.twitterarchiver.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

data class HomeState(
    val loading: Boolean = true,
    val repos: List<ArchiveRepo> = emptyList(),
    val filtered: List<ArchiveRepo> = emptyList(),
    val query: String = "",
    val error: String? = null
)

/** 首页：账号列表 + 搜索 */
class HomeViewModel(private val repo: Repository = Repository.shared) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val list = repo.getRepos(forceRefresh)
                    .sortedByDescending { it.account.equals("AnIncandescence", ignoreCase = true) }
                _state.value = _state.value.copy(
                    loading = false,
                    repos = list,
                    filtered = applyFilter(list, _state.value.query)
                )
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppStrings.get(R.string.load_failed, e.message.orEmpty())
                )
            }
        }
    }

    fun search(q: String) {
        _state.value = _state.value.copy(
            query = q,
            filtered = applyFilter(_state.value.repos, q)
        )
    }

    private fun applyFilter(list: List<ArchiveRepo>, q: String): List<ArchiveRepo> {
        if (q.isBlank()) return list
        val lower = q.lowercase()
        return list.filter {
            it.displayName.lowercase().contains(lower) ||
                it.account.lowercase().contains(lower) ||
                it.handle.lowercase().contains(lower) ||
                (it.description ?: "").lowercase().contains(lower)
        }
    }
}
