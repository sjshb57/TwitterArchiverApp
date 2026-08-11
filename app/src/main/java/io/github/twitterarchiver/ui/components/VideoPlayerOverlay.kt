package io.github.twitterarchiver.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.twitterarchiver.util.VideoSaver
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

/**
 * 视频播放浮层：视频按自身宽高比显示（aspectRatio 跟随视频尺寸），
 * 不撑满全屏，因此上下无多余黑边。半透明背景，点击空白关闭。
 */
@Composable
fun VideoPlayerOverlay(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 视频宽高比（加载后由 onVideoSizeChanged 更新），默认 16:9
    var aspect by remember { mutableFloatStateOf(16f / 9f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 让遮罩延伸到状态栏/导航栏后面（异形屏也全屏覆盖）
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val dialogWindow = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            dialogWindow?.let {
                it.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                it.makeSystemBarsTransparent()
            }
        }
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            // 视频容器：按视频真实比例，高度自适应，无黑边
            VideoPlayer(
                url = url,
                onAspectRatio = { if (it > 0f) aspect = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .aspectRatio(aspect)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White)
            }
            IconButton(
                onClick = {
                    Toast.makeText(context, AppStrings[R.string.video_player_01], Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val ok = VideoSaver.saveVideo(context, url, "TA_${System.currentTimeMillis()}.mp4")
                        Toast.makeText(context, if (ok) AppStrings[R.string.img_preview_02] else AppStrings[R.string.img_preview_01], Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = 60.dp, end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Filled.Download, stringResource(R.string.save), tint = Color.White)
            }
        }
    }
}

/**
 * 让浮层窗口的系统栏透明。
 * statusBarColor/navigationBarColor 自 API 35 起废弃（35+ 强制边到边，调用为无操作），
 * 但 API 26–34 仍需要它们，否则全屏浮层会出现系统栏色条，故保留并抑制警告。
 */
@Suppress("DEPRECATION")
private fun android.view.Window.makeSystemBarsTransparent() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}
