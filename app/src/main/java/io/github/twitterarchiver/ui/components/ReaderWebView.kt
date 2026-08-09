package io.github.twitterarchiver.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val ARCHIVE_HOST = "twitterarchiver.github.io"
private val HTTP_SCHEMES = setOf("http", "https")

/**
 * 复用 reader.html 的 WebView（系统内核，不打包）。
 * 用 url 作为 key —— 不同账号是不同的 WebView 实例，彻底避免状态串账号(#15)。
 *
 * reloadTrigger：值每变化一次就重新加载当前页。
 * WebView 默认吃缓存，仓库内容更新后不刷新看不到变化。
 *
 * 需要 JavaScript：存档 HTML 的排版、图片网格、折叠引用都靠内联脚本实现，
 * 关掉就只剩裸文本。风险由 shouldOverrideUrlLoading 的白名单兜住——
 * 只有 twitterarchiver.github.io 能在此上下文内加载，其余一律拦下。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderWebView(
    url: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    reloadTrigger: Int = 0
) {
    var loading by remember(url) { mutableStateOf(true) }
    var lastTrigger by remember(url) { mutableIntStateOf(reloadTrigger) }

    Box(modifier = modifier.fillMaxSize()) {
        androidx.compose.runtime.key(url) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                                loading = true
                            }
                            override fun onPageFinished(view: WebView?, u: String?) {
                                loading = false
                                val light = if (dark) "false" else "true"
                                view?.evaluateJavascript(
                                    "try{localStorage.setItem('reader-theme','${if (dark) "dark" else "light"}');" +
                                    "if(typeof applyTheme==='function')applyTheme($light,false);}catch(e){}",
                                    null
                                )
                            }
                            override fun shouldOverrideUrlLoading(
                                view: WebView?, request: WebResourceRequest?
                            ): Boolean {
                                val target = request?.url ?: return false
                                if (target.host == ARCHIVE_HOST) return false
                                if (target.scheme !in HTTP_SCHEMES) return true
                                if (request.isForMainFrame) {
                                    runCatching {
                                        view?.context?.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, target)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                                return true
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        loadUrl(url)
                    }
                },
                update = { webView ->
                    // 绕过缓存重新加载
                    if (reloadTrigger != lastTrigger) {
                        lastTrigger = reloadTrigger
                        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        webView.reload()
                        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }
                },
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
