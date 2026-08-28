package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings
import io.github.twitterarchiver.data.ArchiveRepo
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.util.AccountUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RequestState(
    val submitting: Boolean = false,
    val result: String? = null,
    val success: Boolean = false,
    val existing: ArchiveRepo? = null,
    val checking: Boolean = false
)

/**
 * 访客申请存档：用受限 token 创建 Issue。
 * 受限 token 内置在访客版（只能对 requests 仓库开 Issue，泄露危害有限）。
 */
class RequestViewModel(private val repo: Repository = Repository.shared) : ViewModel() {

    private val _state = MutableStateFlow(RequestState())
    val state: StateFlow<RequestState> = _state.asStateFlow()
    private var archives: List<ArchiveRepo>? = null
    private var lastTyped: String = ""

    companion object {
        const val NOTE_MAX = 100
        private const val COOLDOWN_MS = 60_000L
        @Volatile
        private var lastSubmitAt = 0L
    }

    init {
        loadArchives()
    }

    private fun loadArchives() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true) }
            val list = runCatching { repo.getRepos() }.getOrNull()
            if (list != null) archives = list
            _state.update { it.copy(checking = false, existing = match(lastTyped)) }
        }
    }

    fun check(account: String) {
        lastTyped = account
        _state.update { it.copy(existing = match(account)) }
    }

    private fun match(raw: String): ArchiveRepo? {
        val handle = AccountUtil.normalize(raw)
        if (handle.isBlank() || !AccountUtil.isValidHandle(handle)) return null
        return archives?.firstOrNull {
            it.repoName.equals(handle, true) || it.account.equals(handle, true)
        }
    }

    fun submit(restrictedToken: String, account: String, note: String) {
        val handle = AccountUtil.normalize(account)
        if (!AccountUtil.isValidHandle(handle)) {
            _state.update {
                it.copy(
                    submitting = false, success = false,
                    result = AppStrings[R.string.request_invalid_handle]
                )
            }
            return
        }
        val hit = match(handle)
        if (hit != null) {
            _state.update {
                it.copy(
                    submitting = false, success = false, existing = hit,
                    result = AppStrings.get(R.string.request_already_archived, hit.account)
                )
            }
            return
        }
        val since = System.currentTimeMillis() - lastSubmitAt
        if (lastSubmitAt > 0 && since < COOLDOWN_MS) {
            _state.update {
                it.copy(
                    submitting = false, success = false,
                    result = AppStrings.get(
                        R.string.request_too_frequent,
                        ((COOLDOWN_MS - since) / 1000) + 1
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, result = null, success = false) }
            val r = repo.submitRequest(restrictedToken, handle, note.take(NOTE_MAX))
            if (r.isSuccess) lastSubmitAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    submitting = false,
                    success = r.isSuccess,
                    result = if (r.isSuccess) AppStrings[R.string.request_success]
                    else AppStrings.get(
                        R.string.request_failed,
                        r.exceptionOrNull()?.message.orEmpty()
                    )
                )
            }
        }
    }
}
