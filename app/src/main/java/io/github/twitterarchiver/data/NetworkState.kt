package io.github.twitterarchiver.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局网络状态 + 图片加载失败记录。
 *
 * 失败记录必须活在可组合项之外：LazyColumn 滚出屏幕会销毁项，
 * remember 随之重置，回来时又占位重试，导致高度反复变化、上滑滚不到头。
 */
object NetworkState {

    var online by mutableStateOf(true)
        private set

    /** 联网恢复时自增。可组合项读它即可在恢复后重新尝试加载 */
    var generation by mutableIntStateOf(0)
        private set

    private val failed = mutableStateMapOf<String, Unit>()
    private var cm: ConnectivityManager? = null
    private var registered = false
    private var lastValidated = false
    private val main = Handler(Looper.getMainLooper())

    fun isFailed(url: String) = failed.containsKey(url)

    fun markFailed(url: String) { failed[url] = Unit }

    /** 手动重试：下拉刷新时调用，不依赖系统回调 */
    fun clearFailed() {
        failed.clear()
        generation++
    }

    /** 实时连通性。onLost 有延迟，发请求前再查一次可少等一次超时 */
    fun isOnlineNow(): Boolean {
        val c = cm ?: return online
        val n = c.activeNetwork ?: return false
        return c.getNetworkCapabilities(n)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** 回到前台时复查：系统回调偶尔会漏，漏了就一直停在离线判断上 */
    fun recheck() {
        val up = isOnlineNow()
        if (up && !online) post(true) else online = up
    }

    /** 幂等：Activity 重建（如旋转）会再次调用，重复注册会泄漏回调 */
    fun register(context: Context) {
        if (registered) return
        val c = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        registered = true
        cm = c
        online = isOnlineNow()
        c.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post(true)
            override fun onLost(network: Network) = post(false)

            /**
             * 挂 VPN / 代理时，默认网络始终是 VPN 接口，底层断开重连不会走
             * onAvailable / onLost（Network 对象没变），只有能力会变。
             * 这里捕捉 VALIDATED（系统已探测到真正连通）来触发重试。
             */
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !lastValidated) post(true)
                lastValidated = validated
            }
        })
    }

    /** 状态写回主线程：回调在别的线程上，直接改 Compose 状态传播不可靠 */
    private fun post(up: Boolean) {
        main.post {
            online = up
            if (up) clearFailed()     // 触发已收起的图片重新加载
        }
    }
}
