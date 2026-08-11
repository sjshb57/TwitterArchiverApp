package io.github.twitterarchiver.data

import android.content.Context
import androidx.annotation.StringRes

object AppStrings {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    operator fun get(@StringRes id: Int): String =
        appContext?.getString(id).orEmpty()

    fun get(@StringRes id: Int, vararg args: Any): String =
        appContext?.getString(id, *args).orEmpty()
}
