package io.github.twitterarchiver.data

import io.github.twitterarchiver.R
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
    /**
     * 错误的分类结果。把「判断是哪种错」和「文案怎么写」分开：
     * 前者是纯逻辑、可以单测，后者依赖 Android 资源。
     * 合在一起的话，测试就得先初始化 AppStrings，而且改文案会连带弄坏测试。
     */
    sealed interface Kind {
        data object TokenInvalid : Kind
        data class RateLimited(val retryAfterSec: Long?) : Kind
        data class QuotaExhausted(val resetInSec: Long?) : Kind
        data object Forbidden : Kind
        data object NotFound : Kind
        data object Conflict : Kind
        data class Rejected(val reason: String) : Kind
        data class ServerError(val status: Int) : Kind
        data class Other(val status: Int, val reason: String) : Kind
    }

    /**
     * [retryAfter] 用于区分两种 403：主限流（Remaining 为 0）和二级限流
     * （Remaining 不为 0 但带 Retry-After，或响应体里写明 secondary rate limit）。
     * 后者若落到「权限不足」，会让用户白跑去改令牌权限。
     */
    fun classify(
        status: Int,
        body: String,
        remaining: String?,
        resetEpoch: String?,
        retryAfter: String? = null
    ): Kind = when {
        status == 401 -> Kind.TokenInvalid

        status == 429 ||
            (status == 403 && (retryAfter != null ||
                body.contains("secondary rate limit", ignoreCase = true))) ->
            Kind.RateLimited(retryAfter?.toLongOrNull())

        status == 403 && remaining == "0" -> Kind.QuotaExhausted(
            resetEpoch?.toLongOrNull()
                ?.let { (it * 1000 - System.currentTimeMillis()) / 1000 }
                ?.coerceAtLeast(1)
        )

        status == 403 -> Kind.Forbidden
        status == 404 -> Kind.NotFound
        status == 409 -> Kind.Conflict
        status == 422 -> Kind.Rejected(reasonOf(body))
        status in 500..599 -> Kind.ServerError(status)
        else -> Kind.Other(status, reasonOf(body))
    }

    /**
     * 直接把 bodyAsText() 拼进异常消息的话，GitHub 的错误 JSON 会原样显示在
     * 界面上——既看不懂，也可能带上不该展示的细节。
     */
    fun describe(
        status: Int,
        body: String,
        remaining: String?,
        resetEpoch: String?,
        retryAfter: String? = null
    ): String = when (val k = classify(status, body, remaining, resetEpoch, retryAfter)) {
        Kind.TokenInvalid -> AppStrings[R.string.err_token_invalid]
        is Kind.RateLimited ->
            if (k.retryAfterSec != null)
                AppStrings.get(R.string.err_rate_limited_in, humanize(k.retryAfterSec))
            else AppStrings[R.string.err_rate_limited]
        is Kind.QuotaExhausted ->
            if (k.resetInSec != null)
                AppStrings.get(R.string.err_quota_reset_in, humanize(k.resetInSec))
            else AppStrings[R.string.err_quota_exhausted]
        Kind.Forbidden -> AppStrings[R.string.err_forbidden]
        Kind.NotFound -> AppStrings[R.string.err_not_found]
        Kind.Conflict -> AppStrings[R.string.err_conflict]
        is Kind.Rejected -> AppStrings[R.string.err_rejected] + suffix(k.reason)
        is Kind.ServerError -> AppStrings.get(R.string.err_server, k.status)
        is Kind.Other -> AppStrings.get(R.string.err_generic, k.status) + suffix(k.reason)
    }

    private fun suffix(reason: String) =
        if (reason.isBlank()) "" else AppStrings.get(R.string.err_reason_suffix, reason)

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
        if (seconds < 60) AppStrings.get(R.string.dur_seconds, seconds)
        else AppStrings.get(R.string.dur_minutes, (seconds + 59) / 60)

}
