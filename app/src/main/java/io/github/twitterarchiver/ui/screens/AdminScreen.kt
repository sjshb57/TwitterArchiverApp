package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.viewmodel.AdminViewModel
import io.github.twitterarchiver.viewmodel.DashRepo
import kotlinx.coroutines.launch

/** 管理台：4 仪表盘入口。点击进各自详情页（AdminDetailScreen）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    vm: AdminViewModel,
    onOpenDash: (DashRepo) -> Unit,
    onOpenRequests: () -> Unit,
    onNewArchive: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        vm.checkPat()
        vm.loadMissingCache()   // 读缓存显示角标（秒开，不检测）
        vm.loadRequests()       // 查待处理申请（红点）
    }

    val missingCount = state.missingBanner.size
    val requestCount = state.requests.size

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            vm.loadRequests()
            vm.runIntegrityCheck()   // 下拉刷新 = 重新全量检测更新角标
            scope.launch { kotlinx.coroutines.delay(1200); refreshing = false }
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("管理台", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground)
            Text(if (state.hasPat) "已连接" else "未配置令牌", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }

        if (!state.hasPat) {
            PatSetup(onSave = { vm.savePat(it) })
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            ) {
                items4(DashRepo.entries.filter { it != DashRepo.STARTER }) { dash ->
                    DashCard(dash) { onOpenDash(dash) }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 建立新存档（带角标：缺 banner 的仓库数）
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                        .clickable { onNewArchive() }.padding(16.dp)
                ) {
                    Text("➕ 建立新存档", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                if (missingCount > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text("$missingCount 待完善", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 待处理申请（带红点）
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .clickable { onOpenRequests() }.padding(16.dp)
                ) {
                    Text("待处理存档申请 ›", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (requestCount > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text("$requestCount", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("清除令牌", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 20.dp).clickable { vm.clearPat() })
        }
    }
    }
}

// LazyVerticalGrid.items 简易封装
private inline fun <T> androidx.compose.foundation.lazy.grid.LazyGridScope.items4(
    list: List<T>, crossinline block: @Composable (T) -> Unit
) {
    items(list.size) { block(list[it]) }
}

@Composable
private fun DashCard(dash: DashRepo, onClick: () -> Unit) {
    Box(
        Modifier.padding(6.dp).aspectRatio(1.15f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text(dash.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
            if (dash.repo.isNotBlank()) {
                Text(dash.repo, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("查看 ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun PatSetup(onSave: (String) -> Unit) {
    var pat by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("配置 GitHub 令牌 (PAT)", fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Text("需要 repo + workflow 权限，用于触发工作流、编辑文件、删推、处理申请。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                    RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = pat, onValueChange = { pat = it }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp),
                visualTransformation = PasswordVisualTransformation(),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) { inner ->
                if (pat.isEmpty()) Text("粘贴 PAT…", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { if (pat.isNotBlank()) onSave(pat.trim()) },
            modifier = Modifier.fillMaxWidth()) { Text("保存令牌") }
    }
}
