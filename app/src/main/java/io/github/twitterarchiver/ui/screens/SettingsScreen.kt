package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.imageLoader
import io.github.twitterarchiver.data.AppDirs
import io.github.twitterarchiver.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Tab：设置（无 emoji，扁平分组） */
@Composable
fun SettingsScreen(
    followSummary: String,
    onOpenFollow: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDefaultTab: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenRequest: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
    ) {
        Text(
            "设置", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(16.dp)
        )

        SettingGroup {
            SettingRow("主题管理", "浅色 / 深色 / 动态配色", onClick = onOpenTheme)
            Divider()
            SettingRow("\"关注\" 标签页", followSummary, onClick = onOpenFollow)
            Divider()
            SettingRow("默认启动页", "打开应用时进入的页面", onClick = onOpenDefaultTab)
        }

        SettingGroup {
            SettingRow("书签管理", "收藏的推文 · 可导出备份", onClick = onOpenBookmarks)
            Divider()
            SettingRow("申请存档", "申请存档某个账号", onClick = onOpenRequest)
        }

        SettingGroup {
            CacheRow()
            Divider()
            SettingRow("关于", "版本 · 开源协议", onClick = onOpenAbout)
        }
    }
}

/** 缓存管理行：离线索引 + 图片缓存，显示占用并支持一键清理 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
@Composable
private fun CacheRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizeText by remember { mutableStateOf("统计中…") }
    var confirm by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        sizeText = withContext(Dispatchers.IO) {
            val index = AppDirs.root?.let { dirSize(File(it, "index_cache")) } ?: 0L
            val image = ctx.imageLoader.diskCache?.size ?: 0L
            "离线索引 ${fmtSize(index)} · 图片 ${fmtSize(image)}"
        }
    }

    SettingRow("清理缓存", sizeText, onClick = { confirm = true })

    if (confirm) {
        ConfirmDialog(
            title = "清理缓存",
            message = "将删除已下载的离线索引与图片缓存。书签、设置不受影响；下次浏览时会重新下载所需内容。",
            confirmText = "清理",
            danger = true,
            onConfirm = {
                confirm = false
                scope.launch(Dispatchers.IO) {
                    AppDirs.root?.let { File(it, "index_cache").deleteRecursively() }
                    try { ctx.imageLoader.diskCache?.clear() } catch (e: Exception) { }
                    try { ctx.imageLoader.memoryCache?.clear() } catch (e: Exception) { }
                    refreshKey++
                }
            },
            onDismiss = { confirm = false }
        )
    }
}

private fun dirSize(dir: File): Long =
    dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

@Composable
private fun SettingGroup(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingRow(title: String, sub: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp))
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() = HorizontalDivider(
    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
    modifier = Modifier.padding(horizontal = 14.dp)
)
