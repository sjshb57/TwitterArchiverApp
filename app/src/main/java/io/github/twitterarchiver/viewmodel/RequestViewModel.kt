package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.twitterarchiver.util.AccountUtil
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

data class RequestState(
    val submitting: Boolean = false,
    val result: String? = null,
    val success: Boolean = false
)

/**
 * 访客申请存档：用受限 token 创建 Issue。
 * 受限 token 内置在访客版（只能对 requests 仓库开 Issue，泄露危害有限）。
 */
class RequestViewModel(private val repo: Repository = Repository.shared) : ViewModel() {

    private val _state = MutableStateFlow(RequestState())
    val state: StateFlow<RequestState> = _state.asStateFlow()

    companion object {
        /** 备注上限。不限长的话能往 Issue 正文里塞几 MB */
        const val NOTE_MAX = 100

        /** 两次提交的最小间隔。挡的是误触和连点，真正的防护要靠服务端 */
        private const val COOLDOWN_MS = 60_000L

        /**
         * 放在伴生对象里而不是实例字段：退出申请页会销毁 ViewModel，
         * 存在实例上的话退出再进来就绕过了冷却
         */
        @Volatile
        private var lastSubmitAt = 0L
    }

    fun submit(restrictedToken: String, account: String, note: String) {
        val handle = AccountUtil.normalize(account)
        if (!AccountUtil.isValidHandle(handle)) {
            _state.value = RequestState(result = AppStrings[R.string.request_invalid_handle])
            return
        }
        val since = System.currentTimeMillis() - lastSubmitAt
        if (lastSubmitAt > 0 && since < COOLDOWN_MS) {
            _state.value = RequestState(
                result = AppStrings.get(R.string.request_too_frequent, ((COOLDOWN_MS - since) / 1000) + 1))
            return
        }
        viewModelScope.launch {
            _state.value = RequestState(submitting = true)
            val r = repo.submitRequest(restrictedToken, handle, note.take(NOTE_MAX))
            if (r.isSuccess) lastSubmitAt = System.currentTimeMillis()
            _state.value = RequestState(
                submitting = false,
                success = r.isSuccess,
                result = if (r.isSuccess) AppStrings[R.string.request_success]
                else AppStrings.get(R.string.request_failed, r.exceptionOrNull()?.message.orEmpty())
            )
        }
    }
}
