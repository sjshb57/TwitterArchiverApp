package io.github.twitterarchiver.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * 只在界面可见时循环执行 [action]。
 *
 * 直接写 LaunchedEffect { while(true) { delay; refresh() } } 的话，App 切到后台
 * 仍会继续打接口——管理页几处 8 秒一轮，一小时就是 450 次，而 GitHub 的配额
 * 是 5000/小时，还白费电。repeatOnLifecycle 会在 STOPPED 时挂起、
 * 回到 STARTED 时重新开始。
 *
 * [enabled] 为 false 时不启动，用于「只有存在运行中的任务才轮询」这类场景。
 */
@Composable
fun LifecyclePolling(
    interval: Duration,
    enabled: Boolean = true,
    vararg keys: Any?,
    action: suspend () -> Unit
) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(action)

    LaunchedEffect(owner, interval, enabled, *keys) {
        if (!enabled) return@LaunchedEffect
        var firstStart = true
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!firstStart) current()
            firstStart = false
            while (true) {
                delay(interval)
                current()
            }
        }
    }
}
