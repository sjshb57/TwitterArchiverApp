package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import io.github.twitterarchiver.viewmodel.AdminViewModel
import kotlin.time.Duration.Companion.milliseconds

/** 建立新存档 + 待完善列表（建档完成但缺 banner/置顶的仓库，可点击去处理） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminNewArchiveScreen(
    vm: AdminViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    onBack: () -> Unit,
    onOpenArchive: (String) -> Unit
) {
    var repoName by remember { mutableStateOf("") }
    var since by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    var lastRemoveAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.loadMissingCache(); vm.loadNewlyCreated()
        if (state.allArchives.isEmpty()) vm.loadAllArchives()
    }
    LaunchedEffect(state.newlyCreated) { vm.refreshNewlyCreatedStatus() }

    val hasRunning = state.newlyCreated.any { state.repoStatus[it] == "running" }
    LaunchedEffect(hasRunning) {
        if (!hasRunning) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(8000.milliseconds)
            vm.refreshNewlyCreatedStatus()
        }
    }
    LaunchedEffect(state.pendingSetup.keys) { vm.resumePendingSetups() }

    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val typed = io.github.twitterarchiver.util.AccountUtil.normalize(repoName)

    // 一级：本地已知列表，即时反馈
    val localTaken = remember(typed, state.allArchives, state.newlyCreated) {
        typed.isNotBlank() && (
            state.allArchives.any { it.repoName.equals(typed, true) || it.account.equals(typed, true) } ||
                state.newlyCreated.any { it.equals(typed, true) })
    }
    var remoteTaken by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(typed, localTaken) {
        remoteTaken = false
        if (typed.isBlank() || localTaken) { checking = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(500.milliseconds)
        checking = true
        remoteTaken = vm.checkRepoExists(typed) == true
        checking = false
    }
    val taken = localTaken || remoteTaken

    val doRemove: (String) -> Unit = { name ->
        vm.removeNewlyCreated(name)
        lastRemoveAt = System.currentTimeMillis()
        scope.launch {
            val r = snackbarHost.showSnackbar(
                message = "已移除 $name", actionLabel = "撤销",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.addNewlyCreated(name)
        }
    }

    pendingRemove?.let { name ->
        io.github.twitterarchiver.ui.components.ConfirmDialog(
            title = "移除记录",
            message = "将「$name」从新建记录中移除？仓库本身不会被删除。",
            confirmText = "移除",
            onConfirm = { pendingRemove = null; doRemove(name) },
            onDismiss = { pendingRemove = null }
        )
    }

    if (showConfirm) {
        io.github.twitterarchiver.ui.components.ConfirmDialog(
            title = "建立新存档",
            message = "将创建仓库「${repoName.trim()}」并触发首次建档。建档完成后不会自动增量，需回来上传 banner + 设置置顶，再手动触发增量更新。",
            confirmText = "建档",
            onConfirm = { vm.createArchive(repoName.trim(), since.trim()) },
            onDismiss = { showConfirm = false }
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("建立新存档", fontSize = 16.sp) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground)
        )
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                vm.loadNewlyCreated()
                vm.refreshNewlyCreatedStatus()
                vm.loadAllArchives()
                scope.launch { kotlinx.coroutines.delay(800.milliseconds); refreshing = false }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState) {
            item {
                Text("输入账号用户名作为仓库名，从模板创建新仓库并触发首次建档（setup）。建档完成后回来上传 banner、设置置顶，再手动触发增量更新。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                Field2("新仓库名 / 账号", repoName, isError = taken) { repoName = it }
                if (typed.isNotBlank()) {
                    Text(
                        when {
                            taken -> "「$typed」已存在，换一个名字"
                            checking -> "检查中…"
                            else -> "「$typed」可用"
                        },
                        fontSize = 11.sp,
                        color = if (taken) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Field2("起始日期 YYYYMMDD（留空全量）", since) { since = it }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { if (typed.isNotBlank() && !taken) showConfirm = true },
                    enabled = typed.isNotBlank() && !taken,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开始建档") }
                Spacer(Modifier.height(28.dp))
            }

            // 新建记录：批准/手动新建的仓库，带状态灯，点击进入
            if (state.newlyCreated.isNotEmpty()) {
                item {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    Spacer(Modifier.height(14.dp))
                    Text("新建的存档", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("刚建档的仓库（建档中→完成），点击进入处理", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                }
                items(state.newlyCreated, key = { it }) { repoName ->
                    val status = state.repoStatus[repoName] ?: ""
                    NewlyCreatedRow(
                        repoName = repoName,
                        status = status,
                        onOpen = { onOpenArchive(repoName) },
                        onRemove = {
                            if (System.currentTimeMillis() - lastRemoveAt < 3_000L) doRemove(repoName)
                            else pendingRemove = repoName
                        }
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }

            // 待完善列表：缺 banner
            item {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("待完善存档", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    if (state.checking) {
                        Text("检测中 ${state.checkProgress}/${state.checkTotal}", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("🔄 重新检测", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { vm.runIntegrityCheck() })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("建档完成但缺 banner 或置顶的仓库，点击去处理", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp))
            }

            item {
                Text("缺少 Banner（${state.missingBanner.size}）", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            }
            if (state.missingBanner.isEmpty()) item {
                Text(if (state.hasCheckedOnce) "全部已设置 ✓" else "尚未检测",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.missingBanner, key = { it.repo + "/" + it.account }) { m ->
                MissingRow(m.displayName, "上传 banner") { onOpenArchive(m.repo) }
            }

            item {
                Text("缺少 置顶推文（${state.missingPinned.size}）", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            if (state.missingPinned.isEmpty()) item {
                Text(if (state.hasCheckedOnce) "全部已设置 ✓" else "尚未检测",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.missingPinned, key = { it.repo + "/" + it.account }) { m ->
                MissingRow(m.displayName, "设置置顶") { onOpenArchive(m.repo) }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
        }
    }
    androidx.compose.material3.SnackbarHost(
        hostState = snackbarHost,
        modifier = Modifier.align(Alignment.BottomCenter)
            .padding(start = 14.dp, end = 14.dp, bottom = 32.dp)
    ) { data ->
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    data.visuals.message,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                data.visuals.actionLabel?.let { label ->
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { data.performAction() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun MissingRow(name: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$action ›", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Field2(
    hint: String,
    value: String,
    isError: Boolean = false,
    onChange: (String) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .background(
                if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f), shape)
            .then(
                if (isError) Modifier.border(1.dp, MaterialTheme.colorScheme.error, shape)
                else Modifier
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) { inner ->
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        }
    }
}

/** 新建记录行：状态灯 + 仓库名 + 进入 + 移除 */
@Composable
private fun NewlyCreatedRow(
    repoName: String,
    status: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val (dotColor, label) = when (status) {
        "running" -> androidx.compose.ui.graphics.Color(0xFFF5A623) to "建档中"
        "success" -> androidx.compose.ui.graphics.Color(0xFF34C759) to "完成"
        "failure" -> androidx.compose.ui.graphics.Color(0xFFFF3B30) to "失败"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to ""
    }
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp)
            .background(dotColor, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(repoName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (label.isNotBlank()) {
            Text(label, fontSize = 11.sp, color = dotColor,
                modifier = Modifier.padding(end = 10.dp))
        }
        Text("移除", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onRemove() }.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
