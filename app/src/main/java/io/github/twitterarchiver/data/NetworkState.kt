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

    var generation by mutableIntStateOf(0)
        private set

    private val failed = mutableStateMapOf<String, Unit>()

    private val failedOrder = ArrayDeque<String>()

    @Volatile
    private var cm: ConnectivityManager? = null
    @Volatile
    private var registered = false
    @Volatile
    private var lastValidated = false
    private val main = Handler(Looper.getMainLooper())

    fun isFailed(url: String) = failed.containsKey(url)

    private const val FAILED_MAX = 2_000

    fun markFailed(url: String) {
        synchronized(failedOrder) {
            if (failed.put(url, Unit) != null) return
            failedOrder.addLast(url)
            while (failedOrder.size > FAILED_MAX) {
                failed.remove(failedOrder.removeFirst())
            }
        }
    }

    fun clearFailed() {
        synchronized(failedOrder) {
            failed.clear()
            failedOrder.clear()
        }
        generation++
    }

    fun isOnlineNow(): Boolean {
        val c = cm ?: return online
        val n = c.activeNetwork ?: return false
        return c.getNetworkCapabilities(n)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun recheck() {
        val up = isOnlineNow()
        if (up && !online) post(true) else online = up
    }

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

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !lastValidated) post(true)
                lastValidated = validated
            }
        })
    }

    private fun post(up: Boolean) {
        main.post {
            online = up
            if (up) clearFailed()
        }
    }
}
