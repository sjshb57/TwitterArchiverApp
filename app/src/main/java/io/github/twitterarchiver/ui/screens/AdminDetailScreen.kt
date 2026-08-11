package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.WorkflowRun
import io.github.twitterarchiver.viewmodel.AdminViewModel
import io.github.twitterarchiver.viewmodel.DashRepo
import androidx.core.net.toUri
import kotlin.time.Duration.Companion.milliseconds
import io.github.twitterarchiver.ui.components.LifecyclePolling
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

/** 仪表盘详情：工作流运行状态 + 操作（触发/暂停/重试）+ 各仪表盘专属操作入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDetailScreen(
    vm: AdminViewModel,
    dash: DashRepo,
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    onBack: () -> Unit,
    onEditYml: (String, String) -> Unit,   // (repo, path)
    onDeleteTweets: () -> Unit,
    onNewArchive: () -> Unit,
    onOpenArchive: (String) -> Unit         // 打开某存档仓库
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(dash) {
        if (dash == DashRepo.ALL_ARCHIVES) vm.loadAllArchives()
        else vm.loadRuns(dash.repo)
    }
    // 自动刷新：仅当有运行中的任务时才轮询（避免打断浏览）
    LifecyclePolling(8000.milliseconds, keys = arrayOf(dash)) {
        if (dash == DashRepo.ALL_ARCHIVES) {
            if (state.repoStatus.any { it.value == "running" }) vm.refreshRunningStatus()
        } else {
            val hasRunning = (state.runsByRepo[dash.repo] ?: emptyList())
                .any { it.status == "in_progress" || it.status == "queued" }
            if (hasRunning) vm.loadRuns(dash.repo, silent = true)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(dash.titleRes), fontSize = 17.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
            actions = {
                Text(stringResource(R.string.refresh), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp).clickable {
                        if (dash == DashRepo.ALL_ARCHIVES) {
                            vm.loadAllArchives(); vm.refreshAllStatus()
                        } else vm.loadRuns(dash.repo)
                    })
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        if (dash == DashRepo.ALL_ARCHIVES) {
            AllArchivesView(state.allArchives, state.archivesLoading, state.pinnedRepos, state.repoStatus, onOpenArchive, onNewArchive, onDeleteTweets,
                listState = listState,
                onCheck = { vm.runIntegrityCheck() },
                onFix = { repoName -> onOpenArchive(repoName) },
                checking = state.checking, checkProgress = state.checkProgress, checkTotal = state.checkTotal,
                checkDone = state.checkDone, missingBanner = state.missingBanner, missingPinned = state.missingPinned,
                missingAvatar = state.missingAvatar,
                onFixAvatar = { m -> vm.fixLatestAvatar(m.repo, m.account, m.avatarName) },
                onClearCheck = { vm.clearCheck() })
        } else {
            DashDetailView(vm, dash, state, onEditYml)
        }
    }
}

@Composable
private fun DashDetailView(
    vm: AdminViewModel,
    dash: DashRepo,
    state: io.github.twitterarchiver.viewmodel.AdminState,
    onEditYml: (String, String) -> Unit
) {
    val runs = state.runsByRepo[dash.repo] ?: emptyList()
    val loading = state.loadingRepo == dash.repo
    // 待确认的触发操作：(标题, 说明, 执行)
    var pending by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    pending?.let { (title, msg, action) ->
        io.github.twitterarchiver.ui.components.ConfirmDialog(
            title = title, message = msg, confirmText = stringResource(R.string.admin_detail_29),
            onConfirm = action, onDismiss = { pending = null }
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        // 专属操作区
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.admin_archive_16), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 6.dp))
            when (dash) {
                DashRepo.HOME -> {
                    ActionItem(AppStrings[R.string.admin_detail_31]) { pending = Triple(AppStrings[R.string.admin_detail_37], AppStrings[R.string.admin_detail_18]) { vm.triggerWorkflow("home", "update-repos.yml") } }
                    ActionItem(AppStrings[R.string.admin_detail_35]) { pending = Triple(AppStrings[R.string.admin_detail_37], AppStrings[R.string.admin_detail_22]) { vm.triggerWorkflow("home", "build_search_index.yml") } }
                    ActionItem(AppStrings[R.string.admin_detail_32]) { pending = Triple(AppStrings[R.string.admin_detail_37], AppStrings[R.string.admin_detail_19]) { vm.triggerWorkflow("home", "build-manifest.yml") } }
                    ActionItem(AppStrings[R.string.admin_detail_34]) { pending = Triple(AppStrings[R.string.admin_detail_37], AppStrings[R.string.admin_detail_21]) { vm.triggerWorkflow("home", "aggregate_avatars.yml") } }
                    ActionItem(AppStrings[R.string.admin_detail_11]) { pending = Triple(AppStrings[R.string.admin_detail_39], AppStrings[R.string.admin_detail_06]) { vm.triggerWorkflow("home", "push_best_avatars.yml", mapOf("dry_run" to "true")) } }
                    ActionItem(AppStrings[R.string.admin_detail_10]) { pending = Triple(AppStrings[R.string.admin_detail_08], AppStrings[R.string.admin_push_avatar_confirm]) { vm.triggerWorkflow("home", "push_best_avatars.yml", mapOf("dry_run" to "false")) } }
                    ActionItem(stringResource(R.string.admin_detail_24)) { onEditYml("home", ".github/workflows/build_search_index.yml") }
                }
                DashRepo.DISPATCHER -> {
                    ActionItem(AppStrings[R.string.admin_detail_33]) { pending = Triple(AppStrings[R.string.admin_detail_38], AppStrings[R.string.admin_detail_20]) { vm.triggerWorkflow("Dispatcher", "dispatch.yml") } }
                    ActionItem(AppStrings[R.string.admin_detail_30]) { pending = Triple(AppStrings[R.string.admin_detail_36], AppStrings[R.string.admin_detail_17]) { vm.triggerWorkflow("Dispatcher", "dispatch.yml", mapOf("force_all" to "true")) } }
                    ActionItem(stringResource(R.string.admin_detail_25)) { onEditYml("Dispatcher", ".github/workflows/dispatch.yml") }
                }
                DashRepo.STARTER -> {
                    ActionItem(stringResource(R.string.admin_detail_27)) { onEditYml("project-starter", ".github/workflows/setup.yml") }
                    ActionItem(stringResource(R.string.admin_detail_28)) { onEditYml("project-starter", ".github/workflows/update.yml") }
                    ActionItem(stringResource(R.string.admin_detail_26)) { onEditYml("project-starter", ".github/workflows/retry_all.yml") }
                }
                else -> {}
            }
        }
        // 运行状态
        item {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.admin_archive_19), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 6.dp))
        }
        if (loading) {
            item { Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        } else if (runs.isEmpty()) {
            item { Text(stringResource(R.string.admin_archive_18), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
        } else {
            items(runs, key = { it.id }) { run -> RunRow(run, dash.repo, vm) }
        }
    }
}

@Composable
private fun RunRow(run: WorkflowRun, repo: String, vm: AdminViewModel) {
    val (color, label) = runStatus(run)
    val running = run.status == "in_progress" || run.status == "queued"
    val failed = run.conclusion == "failure"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmPause by remember { mutableStateOf(false) }
    var confirmRerun by remember { mutableStateOf(false) }
    if (confirmPause) {
        io.github.twitterarchiver.ui.components.ConfirmDialog(
            title = stringResource(R.string.admin_detail_13), message = stringResource(R.string.admin_detail_16),
            confirmText = stringResource(R.string.admin_archive_17), danger = true,
            onConfirm = { vm.cancelRun(repo, run.id) }, onDismiss = { confirmPause = false }
        )
    }
    if (confirmRerun) {
        io.github.twitterarchiver.ui.components.ConfirmDialog(
            title = stringResource(R.string.admin_detail_40), message = stringResource(R.string.admin_detail_23),
            confirmText = stringResource(R.string.retry),
            onConfirm = { vm.rerunRun(repo, run.id) }, onDismiss = { confirmRerun = false }
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(run.name ?: stringResource(R.string.admin_archive_12), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground)
            Text("#${run.runNumber} · $label · ${runDuration(run)}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 运行时间点（本地时区）
            val runTime = run.runStartedAt?.let {
                io.github.twitterarchiver.util.DateUtil.localDateTime(it)
            }
            if (!runTime.isNullOrBlank()) {
                Text(runTime, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        if (running) {
            Text(stringResource(R.string.admin_archive_17), fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { confirmPause = true }.padding(6.dp))
        }
        if (failed) {
            Text(stringResource(R.string.retry), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { confirmRerun = true }.padding(6.dp))
        }
        Text(stringResource(R.string.admin_archive_15), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable {
                run.htmlUrl?.let {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        it.toUri()))
                }
            }.padding(6.dp))
    }
}

@Composable
private fun AllArchivesView(
    archives: List<io.github.twitterarchiver.data.ArchiveRepo>,
    loading: Boolean,
    pinned: List<String>,
    status: Map<String, String>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDeleteTweets: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCheck: () -> Unit,
    checking: Boolean = false,
    checkProgress: Int = 0,
    checkTotal: Int = 0,
    checkDone: Boolean = false,
    missingBanner: List<io.github.twitterarchiver.viewmodel.MissingItem> = emptyList(),
    missingPinned: List<io.github.twitterarchiver.viewmodel.MissingItem> = emptyList(),
    missingAvatar: List<io.github.twitterarchiver.viewmodel.MissingItem> = emptyList(),
    onFix: (String) -> Unit = {},
    onFixAvatar: (io.github.twitterarchiver.viewmodel.MissingItem) -> Unit = {},
    onClearCheck: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(archives, query, pinned, status) {
        val filtered = if (query.isBlank()) archives
        else archives.filter {
            it.displayName.contains(query, true) || it.account.contains(query, true) ||
                it.handle.contains(query, true) || it.repoName.contains(query, true)
        }
        // 未知 → 出错 → 运行中 → 已完成 → 无状态。稳定排序。
        filtered.sortedBy { r ->
            when (status[r.repoName]) {
                "unknown" -> 0
                "failure" -> 1
                "running" -> 2
                "success" -> 3
                else -> 4
            }
        }
    }
    // 检测结果弹窗
    if (checkDone) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onClearCheck,
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onClearCheck) { Text(stringResource(R.string.close)) }
            },
            title = { Text(stringResource(R.string.admin_detail_07), fontSize = 16.sp) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item {
                        Text(stringResource(R.string.admin_detail_miss_avatar, missingAvatar.size), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.admin_detail_15), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (missingAvatar.isEmpty()) item {
                        Text(stringResource(R.string.admin_detail_04), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(missingAvatar, key = { "avatar:" + it.repo + "/" + it.account }) { m ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onFixAvatar(m) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("· ${m.displayName}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.admin_detail_02), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.admin_detail_miss_banner, missingBanner.size), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (missingBanner.isEmpty()) item {
                        Text(stringResource(R.string.admin_detail_03), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(missingBanner, key = { "banner:" + it.repo + "/" + it.account }) { m ->
                        Text("· ${m.displayName} ›", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onFix(m.repo) }.padding(vertical = 4.dp))
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.admin_detail_miss_pinned, missingPinned.size), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (missingPinned.isEmpty()) item {
                        Text(stringResource(R.string.admin_detail_03), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(missingPinned, key = { "pinned:" + it.repo + "/" + it.account }) { m ->
                        Text("· ${m.displayName} ›", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onFix(m.repo) }.padding(vertical = 4.dp))
                    }
                }
            }
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), state = listState) {
        item {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { onNew() }.padding(14.dp)
            ) {
                Text(stringResource(R.string.admin_detail_01), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                    .clickable { onDeleteTweets() }.padding(14.dp)
            ) {
                Text(stringResource(R.string.admin_detail_42), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            // 完整性检测入口（检测中显示进度）
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    .clickable(enabled = !checking) { onCheck() }.padding(14.dp)
            ) {
                Text(
                    if (checking) stringResource(R.string.admin_detail_checking, checkProgress, checkTotal)
                    else stringResource(R.string.admin_detail_41),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(12.dp))
            // 搜索框（带搜索图标）
            io.github.twitterarchiver.ui.components.SearchField(
                value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.admin_detail_12),
                horizontalPadding = 0.dp
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.admin_detail_all_repos, shown.size, archives.size), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(vertical = 6.dp))
        }
        // 仅首次（列表还空着）显示转圈；重新加载时保留现有项，
        // 否则项数塌缩会把滚动位置夹到 0
        if (loading && shown.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        } else {
            items(shown, key = { it.repoName }) { r ->
                val st = status[r.repoName]
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(r.repoName) }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (st != null) {
                        val c = when (st) {
                            "running" -> Color(0xFFF5A623)
                            "success" -> Color(0xFF17BF63)
                            "failure" -> Color(0xFFE0245E)
                            else -> Color(0xFF8899A6)
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(r.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground)
                        val sub = if (r.repoName != r.account) stringResource(R.string.admin_detail_repo_line, r.handle, r.repoName) else r.handle
                        Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val stLabel = when (st) {
                        "running" -> stringResource(R.string.admin_archive_29)
                        "success" -> stringResource(R.string.admin_detail_09)
                        "failure" -> stringResource(R.string.admin_detail_05)
                        else -> null
                    }
                    if (stLabel != null) {
                        Text(stLabel, fontSize = 10.sp,
                            color = when (st) {
                                "failure" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            })
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 运行时长：运行中动态计时（每秒更新），完成显示总耗时 */
@Composable
private fun runDuration(run: WorkflowRun): String {
    val running = run.status == "in_progress" || run.status == "queued"
    val start = parseIso(run.runStartedAt)
    // 运行中：每秒触发重组
    var now by remember(run.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LifecyclePolling(1000.milliseconds, enabled = running, keys = arrayOf(run.id)) {
        now = System.currentTimeMillis()
    }
    if (start <= 0) return run.runStartedAt?.substringBefore("T") ?: ""
    val end = if (running) now else parseIso(run.updatedAt).takeIf { it > 0 } ?: now
    val sec = ((end - start) / 1000).coerceAtLeast(0)
    val m = sec / 60; val s2 = sec % 60
    val dur = if (m > 0) AppStrings.get(R.string.dur_min_sec, m, s2)
    else AppStrings.get(R.string.dur_sec, s2)
    return if (running) AppStrings.get(R.string.dur_running, dur)
    else AppStrings.get(R.string.dur_elapsed, dur)
}

private fun parseIso(t: String?): Long {
    if (t.isNullOrBlank()) return 0
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(t.substringBefore(".").replace("Z",""))?.time ?: 0
    } catch (e: Exception) { 0 }
}

@Composable
private fun runStatus(run: WorkflowRun): Pair<Color, String> = when {
    run.status == "in_progress" || run.status == "queued" -> Color(0xFFF5A623) to stringResource(R.string.admin_archive_29)
    run.conclusion == "success" -> Color(0xFF17BF63) to stringResource(R.string.admin_archive_14)
    run.conclusion == "failure" -> Color(0xFFE0245E) to stringResource(R.string.admin_archive_11)
    run.conclusion == "cancelled" -> Color(0xFF8899A6) to stringResource(R.string.admin_archive_13)
    else -> Color(0xFF8899A6) to (run.conclusion ?: run.status ?: stringResource(R.string.admin_detail_14))
}

