package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.WorkflowRun
import io.github.twitterarchiver.ui.components.ConfirmDialog
import io.github.twitterarchiver.viewmodel.AdminViewModel
import androidx.core.net.toUri
import kotlin.time.Duration.Companion.milliseconds
import io.github.twitterarchiver.ui.components.LifecyclePolling
import androidx.compose.ui.res.stringResource
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings

/** 单个存档仓库管理：工作流状态 + 触发更新/重试 + 编辑资料入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminArchiveScreen(
    vm: AdminViewModel,
    repo: String,
    onBack: () -> Unit,
    onEditProfile: (String, String) -> Unit = { _, _ -> },
    onOpenReader: (String, String) -> Unit = { _, _ -> },
    onOpenFeed: (String, String) -> Unit = { _, _ -> }
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val runs = state.runsByRepo[repo] ?: emptyList()
    val loading = state.loadingRepo == repo
    var pending by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 该仓库的账号（可能有多个，这里用主账号=仓库名对应的）
    val account = remember(repo, state.allArchives) {
        state.allArchives.firstOrNull { it.repoName == repo }?.account ?: repo
    }
    // banner 图片选择器
    val bannerPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val bytes = ctx.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (bytes != null) {
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    vm.uploadBanner(repo, account, b64, "")
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    LaunchedEffect(repo) { vm.loadRuns(repo, silent = true) }
    // 自动刷新：仅当有运行中的任务时才轮询（避免无谓刷新打断浏览）
    LifecyclePolling(8000.milliseconds, keys = arrayOf(repo)) {
        val hasRunning = (state.runsByRepo[repo] ?: emptyList())
            .any { it.status == "in_progress" || it.status == "queued" }
        if (hasRunning) vm.loadRuns(repo, silent = true)
    }

    pending?.let { (title, msg, action) ->
        ConfirmDialog(title = title, message = msg, confirmText = stringResource(R.string.confirm),
            onConfirm = action, onDismiss = { pending = null })
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(repo, fontSize = 17.sp) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                Text(stringResource(R.string.refresh), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp).clickable { vm.loadRuns(repo) })
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground)
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.admin_archive_16), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(vertical = 6.dp))
                // 一、查看
                Act(stringResource(R.string.admin_archive_21), navigates = true) { onOpenReader(repo, account) }
                Act(stringResource(R.string.admin_archive_20), navigates = true) { onOpenFeed(repo, account) }
                Act(stringResource(R.string.admin_archive_08), navigates = true) {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        "https://github.com/TwitterArchiver/$repo".toUri()))
                }
                ActGroupDivider()
                // 二、抓取更新
                Act(AppStrings[R.string.admin_archive_28]) { pending = Triple(AppStrings[R.string.admin_archive_10], AppStrings.get(R.string.admin_archive_c1, repo)) { vm.triggerWorkflow(repo, "update.yml") } }
                Act(AppStrings[R.string.admin_archive_26]) { pending = Triple(AppStrings[R.string.admin_archive_05], AppStrings.get(R.string.admin_archive_c2, repo)) { vm.triggerWorkflow(repo, "retry_all.yml") } }
                Act(AppStrings[R.string.admin_archive_27]) { pending = Triple(AppStrings[R.string.admin_archive_09], AppStrings.get(R.string.admin_archive_c3, repo)) { vm.triggerWorkflow(repo, "update.yml", mapOf("from_setup" to "true")) } }
                ActGroupDivider()
                // 三、资料维护
                Act(AppStrings[R.string.admin_archive_30]) { pending = Triple(AppStrings[R.string.admin_archive_31], AppStrings.get(R.string.admin_archive_c4, repo)) { vm.triggerWorkflow(repo, "update.yml", mapOf("only_index" to "true")) }
                }
                Act(stringResource(R.string.admin_archive_25), navigates = true) { onEditProfile(repo, account) }
                Act(stringResource(R.string.admin_archive_01), navigates = true) { bannerPicker.launch("image/*") }
                Act(stringResource(R.string.admin_archive_06), danger = true) {
                    pending = Triple(AppStrings[R.string.admin_archive_07],
                        AppStrings.get(R.string.admin_squash_confirm, repo) +
                                AppStrings[R.string.admin_archive_02]) {
                        vm.triggerWorkflow("Dispatcher", "squash_history.yml",
                            mapOf("target_repo" to repo, "confirm" to repo))
                    }
                }
                Act(stringResource(R.string.admin_archive_04)) {
                    pending = Triple(AppStrings[R.string.admin_archive_03],
                        AppStrings[R.string.admin_fix_avatar_confirm] +
                        AppStrings[R.string.admin_archive_22]) {
                        vm.fixLatestAvatar(repo, account)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.admin_archive_19), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            } else if (runs.isEmpty()) {
                item { Text(stringResource(R.string.admin_archive_18), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
            } else {
                items(runs, key = { it.id }) { run -> ArchiveRunRow(run, repo, vm) }
            }
        }
    }
}

/** 操作分组之间的细分隔线 */
@Composable
private fun ActGroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun Act(
    label: String,
    /** 会跳到别处才给箭头；原地触发的动作不给，列表因此自然分成两种质感 */
    navigates: Boolean = false,
    /** 破坏性操作：底部一条渐变横线，不铺整行底色 */
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.error
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (!danger) Modifier else Modifier.drawBehind {
                    val h = 2.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0f))
                        ),
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h)
                    )
                }
            )
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onBackground)
        if (navigates) {
            Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArchiveRunRow(run: WorkflowRun, repo: String, vm: AdminViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val running = run.status == "in_progress" || run.status == "queued"
    val failed = run.conclusion == "failure"
    val color = when {
        running -> Color(0xFFF5A623)
        run.conclusion == "success" -> Color(0xFF17BF63)
        failed -> Color(0xFFE0245E)
        else -> Color(0xFF8899A6)
    }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    confirm?.let { (msg, action) ->
        ConfirmDialog(title = stringResource(R.string.misc_01), message = msg, onConfirm = action, onDismiss = { confirm = null })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(run.name ?: stringResource(R.string.admin_archive_12), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground)
            // 状态中文 + 耗时（对齐 home 仪表盘格式）
            val label = when {
                running -> stringResource(R.string.admin_archive_29)
                run.conclusion == "success" -> stringResource(R.string.admin_archive_14)
                failed -> stringResource(R.string.admin_archive_11)
                run.conclusion == "cancelled" -> stringResource(R.string.admin_archive_13)
                else -> run.status ?: "?"
            }
            Text("#${run.runNumber} · $label · ${runDurationText(run)}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val runTime = run.runStartedAt?.let {
                io.github.twitterarchiver.util.DateUtil.localDateTime(it)
            }?.takeIf { it.isNotBlank() } ?: run.runStartedAt?.substringBefore("T") ?: ""
            if (runTime.isNotBlank()) {
                Text(runTime, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        if (running) Text(stringResource(R.string.admin_archive_17), fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { confirm = AppStrings[R.string.admin_archive_23] to { vm.cancelRun(repo, run.id) } }.padding(6.dp))
        if (failed) Text(stringResource(R.string.retry), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { confirm = AppStrings[R.string.admin_archive_24] to { vm.rerunRun(repo, run.id) } }.padding(6.dp))
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

/** 运行时长：运行中动态计时，完成显示总耗时（对齐 home 仪表盘格式） */
@Composable
private fun runDurationText(run: WorkflowRun): String {
    val running = run.status == "in_progress" || run.status == "queued"
    fun parseIso(t: String?): Long {
        if (t.isNullOrBlank()) return 0
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(t.substringBefore(".").replace("Z", ""))?.time ?: 0
        } catch (e: Exception) { 0 }
    }
    val start = parseIso(run.runStartedAt)
    var now by androidx.compose.runtime.remember(run.id) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LifecyclePolling(1000.milliseconds, enabled = running, keys = arrayOf(run.id)) {
        now = System.currentTimeMillis()
    }
    if (start <= 0) return ""
    val end = if (running) now else parseIso(run.updatedAt).takeIf { it > 0 } ?: now
    val sec = ((end - start) / 1000).coerceAtLeast(0)
    val m = sec / 60; val s2 = sec % 60
    val dur = if (m > 0) AppStrings.get(R.string.dur_min_sec, m, s2)
    else AppStrings.get(R.string.dur_sec, s2)
    return if (running) AppStrings.get(R.string.dur_running, dur)
    else AppStrings.get(R.string.dur_elapsed, dur)
}
