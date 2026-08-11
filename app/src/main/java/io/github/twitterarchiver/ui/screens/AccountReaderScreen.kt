package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.data.Config
import io.github.twitterarchiver.ui.components.ReaderWebView
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R

/**
 * 个人推文页：整个 reader 页塞进 WebView（系统内核，复用你喜欢的 reader.html）。
 * 顶部一个轻量返回栏，下面全是 reader。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountReaderScreen(
    repo: String,
    account: String,
    displayName: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    isMainTab: Boolean = false
) {
    // 刷新计数：每点一次 +1，传给 WebView 触发重新加载
    var reloadTick by remember { mutableIntStateOf(0) }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            },
            actions = {
                IconButton(onClick = { reloadTick++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            },
            // 主 Tab 模式下 AppScaffold 已消费状态栏 inset，这里置 0 避免重复留白；
            // 二级页模式保留默认 inset（正常状态栏沉浸）。
            windowInsets = if (isMainTab)
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            else TopAppBarDefaults.windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        // reader 页 URL：账号存档的 Pages 根
        val url = Config.readerUrl(repo, account)
        // 当前是否深色（用 isSystemInDarkTheme 结合 MaterialTheme 背景亮度判断）
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        ReaderWebView(url = url, dark = isDark, reloadTrigger = reloadTick,
            modifier = Modifier.fillMaxSize())
    }
}
