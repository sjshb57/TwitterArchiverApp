package io.github.twitterarchiver.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.AuthUser
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val loading: Boolean = false,
    val user: AuthUser? = null,
    val error: String? = null
)

/** 管理登录状态（管理版用）：PAT 存取 + 验证 */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val repo = Repository()

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var currentPat: String? = null
    val pat: String? get() = currentPat

    init {
        // 启动时尝试恢复已保存的 PAT
        viewModelScope.launch {
            val saved = store.getPat()
            if (saved != null) {
                _state.value = _state.value.copy(loading = true)
                val user = repo.verifyToken(saved)
                if (user != null) {
                    currentPat = saved
                    _state.value = AuthState(user = user)
                } else {
                    store.clearPat()
                    _state.value = AuthState()
                }
            }
        }
    }

    /** 用输入的 PAT 登录 */
    fun login(inputPat: String) {
        viewModelScope.launch {
            _state.value = AuthState(loading = true)
            val user = repo.verifyToken(inputPat.trim())
            if (user != null) {
                store.savePat(inputPat.trim())
                currentPat = inputPat.trim()
                _state.value = AuthState(user = user)
            } else {
                _state.value = AuthState(error = "PAT 无效或权限不足，请检查后重试")
            }
        }
    }
}
