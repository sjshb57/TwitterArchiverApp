package io.github.twitterarchiver.data

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit

/**
 * 主题设置的同步镜像。
 *
 * 窗口背景必须在 setContent 之前定好，否则冷启动会先显示系统主题的底色再跳到
 * Compose 的配色。而 values-night 只跟系统深浅色走——用户把应用设成深色、
 * 系统是浅色时，仍然会闪一下白（反之闪黑）。
 *
 * 真正的设置存在 DataStore 里，但那只能挂起读取，在 onCreate 里 runBlocking
 * 等于在启动最敏感的路径上做磁盘 I/O。SharedPreferences 有内存缓存，
 * 同步读的开销可以忽略，所以在这里放一份镜像。
 */
object ThemeMirror {

    private const val FILE = "theme_mirror"
    private const val KEY = "dark"
    private const val UNSET = -1

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 主题变化时写入，供下次冷启动使用 */
    fun save(context: Context, dark: Boolean) {
        val p = prefs(context)
        val v = if (dark) 1 else 0
        if (p.getInt(KEY, UNSET) != v) p.edit { putInt(KEY, v) }
    }

    /** 还没有镜像值时（首次启动）退回系统深浅色 */
    fun isDark(context: Context): Boolean = when (prefs(context).getInt(KEY, UNSET)) {
        1 -> true
        0 -> false
        else -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
