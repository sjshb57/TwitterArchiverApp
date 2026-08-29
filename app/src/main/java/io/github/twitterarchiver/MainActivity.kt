package io.github.twitterarchiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import io.github.twitterarchiver.data.AppDirs
import io.github.twitterarchiver.data.Settings
import io.github.twitterarchiver.data.ThemeMirror
import io.github.twitterarchiver.data.ThemeMode
import io.github.twitterarchiver.ui.AppNav
import io.github.twitterarchiver.ui.screens.SplashScreen
import io.github.twitterarchiver.ui.theme.TwitterArchiverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDirs.init(this)
        io.github.twitterarchiver.data.NetworkState.register(this)
        window.setBackgroundDrawable(
            ContextCompat.getColor(
                this,
                if (ThemeMirror.isDark(this)) R.color.window_background_dark
                else R.color.window_background_light
            ).toDrawable()
        )
        enableEdgeToEdge()
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        io.github.twitterarchiver.data.NetworkState.recheck()
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val themeMode by settings.themeMode.collectAsState(initial = null)
    val dynamicColor by settings.dynamicColor.collectAsState(initial = false)

    var showSplash by rememberSaveable { mutableStateOf(true) }

    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(themeMode, systemDark) {
        val mode = themeMode ?: return@LaunchedEffect
        ThemeMirror.save(ctx, when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemDark
        })
    }

    val mirrorDark = remember { ThemeMirror.isDark(ctx) }
    val effectiveMode = themeMode ?: if (mirrorDark) ThemeMode.DARK else ThemeMode.LIGHT
    TwitterArchiverTheme(themeMode = effectiveMode, dynamicColor = dynamicColor) {
        if (showSplash) {
            SplashScreen(onFinish = { showSplash = false })
        } else {
            // 受限 token：访客版申请存档用。构建时通过 BuildConfig 注入
            AppNav(restrictedToken = BuildConfig.REQ_TOKEN)
        }
    }
}
