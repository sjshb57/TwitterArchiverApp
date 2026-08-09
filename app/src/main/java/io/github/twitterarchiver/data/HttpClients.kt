package io.github.twitterarchiver.data

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全应用共享的 OkHttp 客户端。
 *
 * Ktor（GitHub API + Pages 内容）和 Coil（图片）共用一份，省掉重复的
 * TCP/TLS 握手——它们的目标域名高度重合。
 */
object HttpClients {

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .build()
    }

    /**
     * 图片专用：加一个总时长上限。
     * NetworkState 的占位逻辑依赖图片快速失败，不设上限的话弱网时
     * 图片位会一直转圈而不出占位。
     */
    val images: OkHttpClient by lazy {
        shared.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
