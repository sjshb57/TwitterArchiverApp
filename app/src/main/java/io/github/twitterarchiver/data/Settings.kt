package io.github.twitterarchiver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

/** 主题模式 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** 应用设置：主题、动态配色、关注 Tab、默认启动页 */
class Settings(private val context: Context) {

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
        private val KEY_FOLLOW_ENABLED = booleanPreferencesKey("follow_tab_enabled")
        private val KEY_FOLLOW_REPO = stringPreferencesKey("follow_repo")
        private val KEY_FOLLOW_ACCOUNT = stringPreferencesKey("follow_account")
        private val KEY_FOLLOW_NAME = stringPreferencesKey("follow_name")
        private val KEY_DEFAULT_TAB = intPreferencesKey("default_tab")
        private val KEY_BARSTYLE = stringPreferencesKey("bar_style")
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map {
        when (it[KEY_THEME]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            "SYSTEM" -> ThemeMode.SYSTEM
            else -> ThemeMode.LIGHT
        }
    }
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_DYNAMIC] ?: false }
    val followEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_FOLLOW_ENABLED] ?: false }
    val followRepo: Flow<String> = context.settingsDataStore.data.map { it[KEY_FOLLOW_REPO] ?: "" }
    val followAccount: Flow<String> = context.settingsDataStore.data.map { it[KEY_FOLLOW_ACCOUNT] ?: "" }
    val followName: Flow<String> = context.settingsDataStore.data.map { it[KEY_FOLLOW_NAME] ?: "" }
    val defaultTab: Flow<Int> = context.settingsDataStore.data.map { it[KEY_DEFAULT_TAB] ?: 0 }
    val barStyle: Flow<String> = context.settingsDataStore.data.map { it[KEY_BARSTYLE] ?: "text" }

    suspend fun setTheme(mode: ThemeMode) =
        context.settingsDataStore.edit { it[KEY_THEME] = mode.name }
    suspend fun setDynamicColor(v: Boolean) =
        context.settingsDataStore.edit { it[KEY_DYNAMIC] = v }
    suspend fun setFollowEnabled(v: Boolean) =
        context.settingsDataStore.edit { it[KEY_FOLLOW_ENABLED] = v }
    suspend fun setFollow(repo: String, account: String, name: String) =
        context.settingsDataStore.edit {
            it[KEY_FOLLOW_REPO] = repo; it[KEY_FOLLOW_ACCOUNT] = account; it[KEY_FOLLOW_NAME] = name
        }
    suspend fun setDefaultTab(i: Int) =
        context.settingsDataStore.edit { it[KEY_DEFAULT_TAB] = i }
    suspend fun setBarStyle(s: String) =
        context.settingsDataStore.edit { it[KEY_BARSTYLE] = s }
}
