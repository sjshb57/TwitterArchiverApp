package io.github.twitterarchiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.twitterarchiver.data.AppDirs
import io.github.twitterarchiver.data.Settings
import io.github.twitterarchiver.data.ThemeMode
import io.github.twitterarchiver.ui.AppNav
import io.github.twitterarchiver.ui.screens.SplashScreen
import io.github.twitterarchiver.ui.theme.TwitterArchiverTheme
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDirs.init(this)
        io.github.twitterarchiver.data.NetworkState.register(this)
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
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dynamicColor by settings.dynamicColor.collectAsState(initial = false)

    var showSplash by rememberSaveable { mutableStateOf(true) }

    TwitterArchiverTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
        if (showSplash) {
            SplashScreen(onFinish = { showSplash = false })
        } else {
            // 受限 token：访客版申请存档用。构建时通过 BuildConfig 注入
            AppNav(restrictedToken = BuildConfig.REQ_TOKEN)
        }
    }
}
