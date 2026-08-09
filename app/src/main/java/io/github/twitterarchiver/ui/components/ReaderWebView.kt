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

/**
 * 复用 reader.html 的 WebView（系统内核，不打包）。
 * 用 url 作为 key —— 不同账号是不同的 WebView 实例，彻底避免状态串账号(#15)。
 *
 * reloadTrigger：值每变化一次就重新加载当前页。
 * WebView 默认吃缓存，仓库内容更新后不刷新看不到变化。
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
    // 已处理的 trigger 值，仅在真正变化时 reload，避免重组误触发
    var lastTrigger by remember(url) { mutableIntStateOf(reloadTrigger) }

    Box(modifier = modifier.fillMaxSize()) {
        // key(url)：url 变化时整个 WebView 重建，不复用上个账号的实例/状态
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
                                // 注入主题：让 reader 跟随 App 的深浅色
                                // applyTheme(light, save)：light=true 浅色，false 深色
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
                                // 子框架/子资源不拦，否则 reader 里的 iframe 会被踢到浏览器
                                if (request.isForMainFrame != true) return false
                                // host 必须全等。endsWith 会把 evil-twitterarchiver.github.io
                                // 也判成自己人
                                if (target.host == "twitterarchiver.github.io") return false
                                // 非 http(s) 一律拦下：存档 HTML 里可能出现
                                // file:// intent:// javascript: 之类
                                if (target.scheme !in setOf("http", "https")) return true
                                // 无论能否交给外部应用打开，都返回 true 拦住。
                                // 返回 startActivity 的成败会导致没有应用可处理时
                                // WebView 照样把它加载进来，正好绕过这次限制
                                runCatching {
                                    view?.context?.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, target)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
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
                    // key(url) 会让每换一个账号就重建一个 WebView。不显式销毁的话
                    // 旧实例的 native 内存与渲染线程会一直累积
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
