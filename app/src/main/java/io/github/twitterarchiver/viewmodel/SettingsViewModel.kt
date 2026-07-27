package io.github.twitterarchiver.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    val defaultTab: StateFlow<Int> = settings.defaultTab.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), -1
    )

    fun setDefaultTab(i: Int) { viewModelScope.launch { settings.setDefaultTab(i) } }

    // ---------- 关注 Tab ----------
    val followEnabled: StateFlow<Boolean> = settings.followEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val followRepo: StateFlow<String> = settings.followRepo.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val followAccount: StateFlow<String> = settings.followAccount.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val followName: StateFlow<String> = settings.followName.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setFollowEnabled(v: Boolean) { viewModelScope.launch { settings.setFollowEnabled(v) } }
    /** 设置关注的账号并开启 */
    fun setFollow(repo: String, account: String, name: String) {
        viewModelScope.launch {
            settings.setFollow(repo, account, name)
            settings.setFollowEnabled(true)
        }
    }
}
