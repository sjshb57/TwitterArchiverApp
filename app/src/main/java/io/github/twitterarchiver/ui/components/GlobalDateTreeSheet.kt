package io.github.twitterarchiver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.data.GlobalShard

/**
 * 全站时间线的日期树：年 → 月 → 日。
 *
 * 与个人存档页那棵树的区别在于内容可能还没下载到本地——月份和条数来自 meta.json，
 * 因此未下载的月份同样能展示，点进去会先触发下载再跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalDateTreeSheet(
    shards: List<GlobalShard>,
    /** 已下载到本地的月份 */
    downloaded: Set<String>,
    /** 正在下载的月份 → 进度 0..1 */
    progress: Map<String, Float>,
    /** 已加载进内存的每日条数：yyyy-MM-dd → 数量 */
    dayCounts: Map<String, Int>,
    activeDay: String?,
    onPickDay: (String) -> Unit,
    onDownloadYear: (String) -> Unit,
    onDownloadMonth: (String) -> Unit,
    onDeleteYear: (String) -> Unit,
    onDeleteMonth: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val byYear = remember(shards) {
        shards.groupBy { it.month.take(4) }.toSortedMap(compareByDescending { it })
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("按日期浏览", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (activeDay != null) {
                        Text("清除筛选", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onClear() })
                    }
                }
                Text("未下载的月份也能看到条数，点开会先下载。已下载的长按可删除。",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }

            byYear.forEach { (year, months) ->
                item(key = year) {
                    YearNode(
                        year = year, months = months.sortedByDescending { it.month },
                        downloaded = downloaded, progress = progress, dayCounts = dayCounts,
                        activeDay = activeDay, onPickDay = onPickDay,
                        onDownloadYear = onDownloadYear, onDownloadMonth = onDownloadMonth,
                        onDeleteYear = onDeleteYear, onDeleteMonth = onDeleteMonth
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YearNode(
    year: String,
    months: List<GlobalShard>,
    downloaded: Set<String>,
    progress: Map<String, Float>,
    dayCounts: Map<String, Int>,
    activeDay: String?,
    onPickDay: (String) -> Unit,
    onDownloadYear: (String) -> Unit,
    onDownloadMonth: (String) -> Unit,
    onDeleteYear: (String) -> Unit,
    onDeleteMonth: (String) -> Unit
) {
    var open by remember(year) { mutableStateOf(false) }
    val allDone = months.all { it.month in downloaded }
    val running = months.mapNotNull { progress[it.month] }
    val yearBytes = months.sumOf { it.bytes }
    val yearCount = months.sumOf { it.count }

    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }
            .padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▼" else "▶", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("$year 年", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("${fmtCount(yearCount)} 条 · ${fmtBytes(yearBytes)}", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DownloadAction(
                done = allDone,
                // 年的进度 = 已完成月份 + 进行中月份的比例，除以总月数
                progress = if (running.isEmpty()) null
                           else (months.count { it.month in downloaded } + running.sum()) / months.size,
                onDownload = { onDownloadYear(year) },
                onDelete = { onDeleteYear(year) }
            )
        }
        AnimatedVisibility(visible = open) {
            Column {
                months.forEach { shard ->
                    MonthNode(shard, downloaded, progress, dayCounts, activeDay,
                        onPickDay, onDownloadMonth, onDeleteMonth)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthNode(
    shard: GlobalShard,
    downloaded: Set<String>,
    progress: Map<String, Float>,
    dayCounts: Map<String, Int>,
    activeDay: String?,
    onPickDay: (String) -> Unit,
    onDownloadMonth: (String) -> Unit,
    onDeleteMonth: (String) -> Unit
) {
    var open by remember(shard.month) { mutableStateOf(false) }
    val isDone = shard.month in downloaded
    val month = shard.month.substring(5)

    Column {
        Row(Modifier.fillMaxWidth().clickable {
                if (isDone) open = !open else onDownloadMonth(shard.month)
            }.padding(start = 28.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▼" else "▶", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("$month 月", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${fmtCount(shard.count)} 条 · ${fmtBytes(shard.bytes)}", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            DownloadAction(
                done = isDone,
                progress = progress[shard.month],
                onDownload = { onDownloadMonth(shard.month) },
                onDelete = { onDeleteMonth(shard.month) }
            )
        }
        AnimatedVisibility(visible = open) {
            Column {
                val days = dayCounts.keys.filter { it.startsWith(shard.month) }.sortedDescending()
                days.forEach { full ->
                    val active = activeDay == full
                    Row(Modifier.fillMaxWidth().clickable { onPickDay(full) }
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else Color.Transparent)
                        .padding(start = 42.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(full.substring(8) + " 日", fontSize = 13.sp,
                            color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        Text("${dayCounts[full] ?: 0}", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 未下载 → 「下载」；下载中 → 百分比 + 转圈；已下载 → 「已下载」，长按删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadAction(
    done: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    when {
        progress != null -> Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp)) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary)
        }
        done -> Text("已下载", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.combinedClickable(
                onClick = { }, onLongClick = onDelete
            ).padding(horizontal = 8.dp, vertical = 4.dp))
        else -> Text("下载", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onDownload() }
                .padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun fmtCount(n: Int): String = if (n >= 1000) "%,d".format(n) else n.toString()

private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
