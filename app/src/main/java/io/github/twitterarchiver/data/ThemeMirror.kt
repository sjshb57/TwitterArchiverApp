package io.github.twitterarchiver.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit

object ThemeMirror {

    private const val FILE = "theme_mirror"
    private const val KEY = "dark"
    private const val KEY_APPLIED = "applied_night_mode"
    private const val UNSET = -1

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 主题变化时写入，供下次冷启动使用 */
    fun save(context: Context, dark: Boolean) {
        val p = prefs(context)
        val v = if (dark) 1 else 0
        if (p.getInt(KEY, UNSET) != v) p.edit { putInt(KEY, v) }
    }

    fun applyNightMode(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val want = if (isDark(context)) UiModeManager.MODE_NIGHT_YES
        else UiModeManager.MODE_NIGHT_NO
        val p = prefs(context)
        if (p.getInt(KEY_APPLIED, UNSET) == want) return
        runCatching {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(want)
            p.edit { putInt(KEY_APPLIED, want) }
        }
    }

    /** 还没有镜像值时（首次启动）退回系统深浅色 */
    fun isDark(context: Context): Boolean = when (prefs(context).getInt(KEY, UNSET)) {
        1 -> true
        0 -> false
        else -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
