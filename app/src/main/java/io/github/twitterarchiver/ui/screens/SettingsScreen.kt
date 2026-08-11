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
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

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
            stringResource(R.string.tab_settings), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(16.dp)
        )

        SettingGroup {
            SettingRow(stringResource(R.string.settings_02), stringResource(R.string.settings_07), onClick = onOpenTheme)
            Divider()
            SettingRow(stringResource(R.string.settings_01), followSummary, onClick = onOpenFollow)
            Divider()
            SettingRow(stringResource(R.string.deftab_04), stringResource(R.string.settings_05), onClick = onOpenDefaultTab)
        }

        SettingGroup {
            SettingRow(stringResource(R.string.settings_03), stringResource(R.string.settings_06), onClick = onOpenBookmarks)
            Divider()
            SettingRow(stringResource(R.string.request_05), stringResource(R.string.settings_11), onClick = onOpenRequest)
        }

        SettingGroup {
            CacheRow()
            Divider()
            SettingRow(stringResource(R.string.about_01), stringResource(R.string.settings_10), onClick = onOpenAbout)
        }
    }
}

/** 缓存管理行：离线索引 + 图片缓存，显示占用并支持一键清理 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
@Composable
private fun CacheRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizeText by remember { mutableStateOf(AppStrings[R.string.health_08]) }
    var confirm by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        sizeText = withContext(Dispatchers.IO) {
            val index = AppDirs.root?.let { dirSize(File(it, "index_cache")) } ?: 0L
            val image = ctx.imageLoader.diskCache?.size ?: 0L
            AppStrings.get(R.string.settings_cache, fmtSize(index), fmtSize(image))
        }
    }

    SettingRow(stringResource(R.string.settings_09), sizeText, onClick = { confirm = true })

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_09),
            message = stringResource(R.string.settings_04),
            confirmText = stringResource(R.string.settings_08),
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
