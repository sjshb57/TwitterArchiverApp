package io.github.twitterarchiver.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全应用共享的 OkHttp 客户端。
 *
 * Ktor（GitHub API + Pages 内容）和 Coil（图片）原本各建一个，两套连接池、
 * 两套线程池，而它们的目标域名高度重合（都是 twitterarchiver.github.io），
 * 共用一个能省掉重复的 TCP/TLS 握手。
 */
object HttpClients {

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** GitHub 接口返回的错误，转成能给用户看的说法 */
object GitHubError {

    /**
     * 把状态码和响应体整理成一句话。
     *
     * 原来是直接把 `bodyAsText()` 拼进异常消息，GitHub 的错误 JSON 会原样
     * 显示在界面上——既看不懂，也可能带上不该展示的细节。
     */
    fun describe(status: Int, body: String, remaining: String?, resetEpoch: String?): String = when {
        status == 401 -> "令牌无效或已过期，请重新填写"
        status == 403 && remaining == "0" -> {
            val mins = resetEpoch?.toLongOrNull()
                ?.let { ((it * 1000 - System.currentTimeMillis()) / 60_000).coerceAtLeast(1) }
            if (mins != null) "已达 GitHub 接口调用上限，约 $mins 分钟后恢复"
            else "已达 GitHub 接口调用上限，请稍后再试"
        }
        status == 403 -> "没有权限执行此操作，请确认令牌勾选了 repo 与 workflow"
        status == 404 -> "目标不存在或令牌无权访问"
        status == 409 -> "仓库状态冲突，请稍后重试"
        status == 422 -> "请求被拒绝：" + shortReason(body)
        status in 500..599 -> "GitHub 服务异常（$status），请稍后重试"
        else -> "请求失败（$status）" + shortReason(body).let { if (it.isBlank()) "" else "：$it" }
    }

    /** 只取 GitHub 错误 JSON 里的 message 字段，且限长 */
    private fun shortReason(body: String): String {
        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]{0,120})\"").find(body)?.groupValues?.get(1)
        return msg?.trim().orEmpty()
    }
}
