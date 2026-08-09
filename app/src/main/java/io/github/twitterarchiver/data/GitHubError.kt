package io.github.twitterarchiver.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 把 GitHub 接口的错误响应整理成能给用户看的说法 */
object GitHubError {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 直接把 bodyAsText() 拼进异常消息的话，GitHub 的错误 JSON 会原样显示在
     * 界面上——既看不懂，也可能带上不该展示的细节。
     *
     * [retryAfter] 用于区分两种 403：主限流（Remaining 为 0）和二级限流
     * （Remaining 不为 0 但带 Retry-After）。后者若落到"权限不足"，
     * 会让用户白跑去改令牌权限。
     */
    fun describe(
        status: Int,
        body: String,
        remaining: String?,
        resetEpoch: String?,
        retryAfter: String? = null
    ): String = when {
        status == 401 -> "令牌无效或已过期，请重新填写"

        status == 429 || (status == 403 && retryAfter != null) -> {
            val secs = retryAfter?.toLongOrNull()
            if (secs != null) "请求过于频繁，请 ${humanize(secs)}后再试"
            else "请求过于频繁，请稍后再试"
        }

        status == 403 && remaining == "0" -> {
            val secs = resetEpoch?.toLongOrNull()
                ?.let { (it * 1000 - System.currentTimeMillis()) / 1000 }
                ?.coerceAtLeast(1)
            if (secs != null) "已达 GitHub 接口调用上限，约 ${humanize(secs)}后恢复"
            else "已达 GitHub 接口调用上限，请稍后再试"
        }

        status == 403 -> "没有权限执行此操作，请确认令牌勾选了 repo 与 workflow"
        status == 404 -> "目标不存在或令牌无权访问"
        status == 409 -> "仓库状态冲突，请稍后重试"
        status == 422 -> "请求被拒绝" + reasonOf(body).let { if (it.isBlank()) "" else "：$it" }
        status in 500..599 -> "GitHub 服务异常（$status），请稍后重试"
        else -> "请求失败（$status）" + reasonOf(body).let { if (it.isBlank()) "" else "：$it" }
    }

    /**
     * 取错误 JSON 顶层的 message。
     *
     * 不用正则：422 响应的 errors[] 数组里也有 message 字段，
     * 正则取第一个匹配很容易取到嵌套的那个；message 里含转义引号也会被截断。
     */
    fun reasonOf(body: String): String =
        runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()?.trim()?.take(120).orEmpty()

    private fun humanize(seconds: Long): String =
        if (seconds < 60) "$seconds 秒" else "${(seconds + 59) / 60} 分钟"
}
