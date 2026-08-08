package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import io.github.twitterarchiver.ui.components.ImagePreviewOverlay
import io.github.twitterarchiver.ui.components.VideoPlayerOverlay
import io.github.twitterarchiver.viewmodel.ImagesViewModel
import io.github.twitterarchiver.viewmodel.MediaItem
import io.github.twitterarchiver.viewmodel.MediaType

/** 媒体浏览页：图片+视频+其他，右上角类型筛选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(
    vm: ImagesViewModel,
    title: String,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<MediaType?>(null) }  // null=全部
    var previewImages by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var playVideo by remember { mutableStateOf<String?>(null) }

    val shown = remember(state.all, filter) {
        if (filter == null) state.all else state.all.filter { it.type == filter }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var menuOpen by remember { mutableStateOf(false) }
        val filterLabel = when (filter) {
            null -> "全部"
            MediaType.IMAGE -> "图片"
            MediaType.VIDEO -> "视频"
            MediaType.OTHER -> "其他"
        }
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$title · 媒体", fontSize = 16.sp)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
            actions = {
                // 左上角风格的下拉筛选（放在 actions 区，点开往下展开）
                Box {
                    androidx.compose.material3.TextButton(onClick = { menuOpen = true }) {
                        Text(filterLabel, fontSize = 14.sp)
                        Text(" ▾", fontSize = 12.sp)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropItem("全部") { filter = null; menuOpen = false }
                        DropItem("图片") { filter = MediaType.IMAGE; menuOpen = false }
                        DropItem("视频") { filter = MediaType.VIDEO; menuOpen = false }
                        DropItem("其他") { filter = MediaType.OTHER; menuOpen = false }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("暂无该类型媒体", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(2.dp)
            ) {
                items(shown) { item ->
                    MediaCell(item) {
                        when (item.type) {
                            MediaType.VIDEO -> playVideo = item.url
                            else -> {
                                // 图片/其他：收集同类型的所有 url 供翻页
                                val imgs = shown.filter { it.type != MediaType.VIDEO }.map { it.url }
                                val idx = imgs.indexOf(item.url).coerceAtLeast(0)
                                previewImages = imgs to idx
                            }
                        }
                    }
                }
            }
        }
    }

    previewImages?.let { (urls, idx) ->
        ImagePreviewOverlay(urls = urls, startIndex = idx, onDismiss = { previewImages = null })
    }
    playVideo?.let { url ->
        VideoPlayerOverlay(url = url, onDismiss = { playVideo = null })
    }
}

@Composable
private fun DropItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp) },
        onClick = onClick
    )
}

@Composable
private fun MediaCell(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.aspectRatio(1f).padding(1.dp).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when (item.type) {
            MediaType.VIDEO -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.url)
                        .videoFrameMillis(0)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF16181C))
                )
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, "播放", tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(4.dp))
                }
                Text("视频", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
            }
            else -> {
                AsyncImage(
                    model = item.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (item.type == MediaType.OTHER) {
                    Text("GIF", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
        }
    }
}
