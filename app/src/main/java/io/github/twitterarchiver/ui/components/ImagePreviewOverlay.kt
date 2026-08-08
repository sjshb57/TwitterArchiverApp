package io.github.twitterarchiver.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import io.github.twitterarchiver.util.ImageSaver
import kotlinx.coroutines.launch

/**
 * 图片预览（方案B：卡片式）。
 * 图片放进一个居中卡片，卡片高度包裹图片本身（图片多高卡片多高），
 * 卡片外是半透明背景。这样图片上下不再有大片纯黑空区。
 */
@Composable
fun ImagePreviewOverlay(
    urls: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex) { urls.size }

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
        // 半透明背景（不是纯黑铺满），点击空白关闭
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                pageSpacing = 12.dp
            ) { page ->
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }

                // 卡片：包裹图片，圆角，深色卡底（图片透明区域也好看）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0E0F11))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* 点卡片本身不关闭 */ },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = urls[page],
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                                    else { offsetX = 0f; offsetY = 0f }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                                        else scale = 2.5f
                                    }
                                )
                            }
                            .graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offsetX, translationY = offsetY
                            )
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White)
            }
            if (urls.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${urls.size}",
                    color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                )
            }

            IconButton(
                onClick = {
                    scope.launch {
                        val url = urls[pagerState.currentPage]
                        val ok = try {
                            val req = ImageRequest.Builder(context).data(url)
                                .allowHardware(false).build()
                            val result = context.imageLoader.execute(req)
                            val bmp = (result as? SuccessResult)?.image
                                ?.let { it as? coil3.BitmapImage }?.bitmap
                            bmp != null && ImageSaver.saveBitmap(
                                context, bmp, "TA_${System.currentTimeMillis()}.jpg")
                        } catch (e: Exception) { false }
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败",
                            Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = 60.dp, end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(Icons.Filled.Download, "保存", tint = Color.White)
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
