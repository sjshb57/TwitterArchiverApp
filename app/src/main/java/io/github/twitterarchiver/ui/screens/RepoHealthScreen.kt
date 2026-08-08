package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.RepoHealth
import io.github.twitterarchiver.viewmodel.AdminViewModel
import io.github.twitterarchiver.viewmodel.DashRepo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoHealthScreen(
    vm: AdminViewModel,
    onBack: () -> Unit,
    onOpenDash: (DashRepo) -> Unit,
    onOpenRepo: (String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (state.health.isEmpty()) vm.loadHealth() }

    val needAttention = state.health.filter { it.overdue || it.sizeLevel > 0 }
    val shown = if (showAll) state.health else needAttention

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("仓库健康", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Text("‹", fontSize = 30.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp).clickable { onBack() })
                },
                actions = {
                    Text(if (state.healthLoading) "检查中…" else "重新检查",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp).clickable { vm.loadHealth() })
                }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                val r = state.rotation
                Text(
                    when {
                        state.healthLoading -> "正在读取组织仓库…"
                        r != null -> "共 ${state.health.size} 个存档 · 每天 ${r.batch * r.runsPerDay} 个 · " +
                            "转一圈约 ${r.cycleDays} 天 · 超过 ${r.overdueDays} 天算异常"
                        else -> "共 ${state.health.size} 个存档"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                state.healthError?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            }

            item {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenDash(DashRepo.STARTER) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("存档模板", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("project-starter · 新建存档使用的模板仓库",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            }

            if (!state.healthLoading && state.health.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showAll) "全部 ${state.health.size} 个"
                            else "需要注意 ${needAttention.size} 个",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                        )
                        Text(if (showAll) "只看异常" else "显示全部",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showAll = !showAll })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
            }

            if (!state.healthLoading && shown.isEmpty() && state.health.isNotEmpty()) {
                item {
                    Text("没有异常仓库", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(40.dp))
                }
            }

            items(shown, key = { it.name }) { h -> HealthRow(h) { onOpenRepo(h.name) } }

            if (state.healthLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun HealthRow(h: RepoHealth, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(h.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                h.daysSincePush?.let { "$it 天前更新" } ?: "无更新记录",
                fontSize = 12.sp,
                color = if (h.overdue) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (h.sizeLevel > 0) {
            val c = if (h.sizeLevel == 2) MaterialTheme.colorScheme.error else Color(0xFFF5A623)
            Text(
                "%.1f GB".format(h.sizeMb / 1024),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        } else {
            Text(
                if (h.sizeMb >= 1) "%.0f MB".format(h.sizeMb) else "%.1f MB".format(h.sizeMb),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
}
