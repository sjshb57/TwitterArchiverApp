package io.github.twitterarchiver.data

import android.content.Context
import java.io.File

/**
 * 全局目录持有者。ViewModel 里 Repository 以默认参数创建拿不到 Context，
 * 由 MainActivity 启动时注入一次；未注入时离线缓存自动禁用（仅内存+网络）。
 */
object AppDirs {
    @Volatile
    var root: File? = null
        private set

    fun init(context: Context) {
        root = context.applicationContext.filesDir
    }
}
