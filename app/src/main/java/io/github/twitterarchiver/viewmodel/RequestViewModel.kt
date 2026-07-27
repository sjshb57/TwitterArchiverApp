package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RequestState(
    val submitting: Boolean = false,
    val result: String? = null,
    val success: Boolean = false
)

/**
 * 访客申请存档：用受限 token 创建 Issue。
 * 受限 token 内置在访客版（只能对 requests 仓库开 Issue，泄露危害有限）。
 */
class RequestViewModel(private val repo: Repository = Repository()) : ViewModel() {

    private val _state = MutableStateFlow(RequestState())
    val state: StateFlow<RequestState> = _state.asStateFlow()

    fun submit(restrictedToken: String, account: String, note: String) {
        viewModelScope.launch {
            _state.value = RequestState(submitting = true)
            val r = repo.submitRequest(restrictedToken, io.github.twitterarchiver.util.AccountUtil.normalize(account), note)
            _state.value = RequestState(
                submitting = false,
                success = r.isSuccess,
                result = if (r.isSuccess) "申请已提交，感谢！我们会尽快处理。"
                else "提交失败：${r.exceptionOrNull()?.message}"
            )
        }
    }
}
