package io.github.twitterarchiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.twitterarchiver.BuildConfig
import io.github.twitterarchiver.ui.components.ProfileDialog
import io.github.twitterarchiver.ui.screens.*
import io.github.twitterarchiver.viewmodel.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf

/** 应用内的"页面"状态（简单栈式导航） */
/**
 * 导航目标。标注 @Serializable 是为了能存进 rememberSaveable——
 * 没有它的话，转屏、切深浅色、改系统字号都会重建 Activity，
 * 导航栈全丢、直接跳回首页。
 */
@kotlinx.serialization.Serializable
sealed class Screen {
    @kotlinx.serialization.Serializable data object Tabs : Screen()
    @kotlinx.serialization.Serializable data class Reader(val repo: String, val account: String, val name: String) : Screen()
    @kotlinx.serialization.Serializable data class AccountFeed(val repo: String, val account: String, val name: String) : Screen()
    @kotlinx.serialization.Serializable data class Images(val repo: String, val account: String, val name: String) : Screen()
    @kotlinx.serialization.Serializable data object Theme : Screen()
    @kotlinx.serialization.Serializable data object Bookmarks : Screen()
    @kotlinx.serialization.Serializable data object Request : Screen()
    @kotlinx.serialization.Serializable data object About : Screen()
    @kotlinx.serialization.Serializable data object Thanks : Screen()
    @kotlinx.serialization.Serializable data object DefaultTab : Screen()
    @kotlinx.serialization.Serializable data object FollowSelect : Screen()
    @kotlinx.serialization.Serializable data class AdminDash(val dash: DashRepo) : Screen()
    @kotlinx.serialization.Serializable data class AdminEditYml(val repo: String, val path: String) : Screen()
    @kotlinx.serialization.Serializable data object AdminDeleteTweets : Screen()
    @kotlinx.serialization.Serializable data object AdminNewArchive : Screen()
    @kotlinx.serialization.Serializable data object PrivateRepos : Screen()
    @kotlinx.serialization.Serializable data class AdminArchive(val repo: String) : Screen()
    @kotlinx.serialization.Serializable data class AdminEditProfile(val repo: String, val account: String) : Screen()
    @kotlinx.serialization.Serializable data object AdminRequests : Screen()
}

private val navJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** 单个 Screen 的 Saver */
private val TabIdSaver = androidx.compose.runtime.saveable.Saver<TabId, String>(
    save = { it.name },
    restore = { runCatching { TabId.valueOf(it) }.getOrNull() }
)

private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = { runCatching { navJson.encodeToString(it) }.getOrNull() },
    restore = { runCatching { navJson.decodeFromString<Screen>(it) }.getOrNull() }
)

/** 返回栈的 Saver：整条栈编码成一个字符串 */
private val BackStackSaver =
    androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateList<Screen>, String>(
        save = { runCatching { navJson.encodeToString(it.toList()) }.getOrNull() },
        restore = { text ->
            runCatching {
                androidx.compose.runtime.mutableStateListOf<Screen>().apply {
                    addAll(navJson.decodeFromString<List<Screen>>(text))
                }
            }.getOrNull()
        }
    )

/** 弹窗目标账号（统一 ListScreen 和 GlobalScreen 的来源） */
data class DialogTarget(
    val repo: String, val account: String,
    val name: String, val handle: String, val bio: String,
    val avatar: String = ""
)

@Composable
fun AppNav(
    // 受限 token：访客版申请存档用，由 MainActivity 在构建时注入
    restrictedToken: String = ""
) {
    val homeVm: HomeViewModel = viewModel()
    val globalVm: GlobalTimelineViewModel = viewModel()
    val adminVm: AdminViewModel = viewModel()
    val adminState by adminVm.state.collectAsState()
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(adminState.message) {
        adminState.message?.let {
            android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
            adminVm.clearMessage()
        }
    }

    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf(Screen.Tabs)
    }
    val globalListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val followListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val adminListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val newArchiveListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val tabScope = androidx.compose.runtime.rememberCoroutineScope()

    val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf() }
    fun navTo(s: Screen) {
        backStack.add(screen)
        screen = s
        val target = when (s) {
            is Screen.AdminDash -> if (s.dash == DashRepo.ALL_ARCHIVES) adminListState else null
            is Screen.AdminNewArchive -> newArchiveListState
            else -> null
        }
        target?.let { st -> tabScope.launch { st.scrollToItem(0) } }
    }
    fun navBack() { screen = if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1) else Screen.Tabs }
    val settingsVm: SettingsViewModel = viewModel()
    val defaultTab by settingsVm.defaultTab.collectAsState()
    var selectedTab by rememberSaveable(stateSaver = TabIdSaver) {
        mutableStateOf(TabId.LIST)
    }
    var appliedDefault by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(defaultTab) {
        // -1 表示还没从存储加载完；只有拿到真实值(0/1)才应用一次
        if (!appliedDefault && defaultTab >= 0) {
            selectedTab = if (defaultTab == 1) TabId.GLOBAL else TabId.LIST
            appliedDefault = true
        }
    }
    var dialogTarget by remember { mutableStateOf<DialogTarget?>(null) }
    var imagePreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    // 关注状态（用于动态插入"关注"Tab）
    val followEnabled by settingsVm.followEnabled.collectAsState()
    val followRepo by settingsVm.followRepo.collectAsState()
    val followAccount by settingsVm.followAccount.collectAsState()
    val followName by settingsVm.followName.collectAsState()

    val tabs = if (BuildConfig.IS_ADMIN) {
        buildList {
            add(TabItem(TabId.LIST, "列表"))
            add(TabItem(TabId.GLOBAL, "全站"))
            if (followEnabled && followAccount.isNotBlank()) add(TabItem(TabId.FOLLOW, "关注"))
            add(TabItem(TabId.ADMIN, "管理"))
            add(TabItem(TabId.SETTINGS, "设置"))
        }
    } else {
        buildList {
            add(TabItem(TabId.LIST, "列表"))
            add(TabItem(TabId.GLOBAL, "全站"))
            if (followEnabled && followAccount.isNotBlank()) add(TabItem(TabId.FOLLOW, "关注"))
            add(TabItem(TabId.SETTINGS, "设置"))
        }
    }

    BackHandler(enabled = screen !is Screen.Tabs) {
        navBack()
    }
    val backCtx = LocalContext.current
    var lastBackAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    BackHandler(enabled = screen is Screen.Tabs) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            (backCtx as? android.app.Activity)?.finish()
        } else {
            lastBackAt = now
            android.widget.Toast.makeText(backCtx, "再按一次返回键退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    when (val s = screen) {
        is Screen.Tabs -> {
            LaunchedEffect(tabs) {
                if (tabs.none { it.id == selectedTab }) selectedTab = tabs.first().id
            }
            val selectedIdx = tabs.indexOfFirst { it.id == selectedTab }.coerceAtLeast(0)
            AppScaffold(tabs, selectedIdx, { selectedTab = tabs[it].id },
                onTabReselected = { idx ->
                    when (tabs[idx].id) {
                        TabId.LIST -> tabScope.launch { homeListState.animateScrollToItem(0) }
                        TabId.GLOBAL -> tabScope.launch { globalListState.animateScrollToItem(0) }
                        TabId.FOLLOW -> tabScope.launch { followListState.animateScrollToItem(0) }
                        TabId.ADMIN, TabId.SETTINGS -> Unit
                    }
                }) { tab ->
                when (tabs[tab].id) {
                    TabId.LIST -> ListScreen(
                        vm = homeVm,
                        listState = homeListState,
                        onOpenAccount = { navTo(Screen.AccountFeed(it.repoName, it.account, it.displayName)) },
                        onAvatarClick = {
                            dialogTarget = DialogTarget(it.repoName, it.account, it.displayName, it.handle, it.description ?: "", it.avatar ?: "")
                        }
                    )
                    TabId.GLOBAL -> GlobalScreen(
                        vm = globalVm,
                        listState = globalListState,
                        onAvatarClick = { post ->
                            dialogTarget = DialogTarget(post.account.r, post.account.a, post.account.n, post.account.u, "", if (post.account.av.isNotBlank()) "avatar/${post.account.av}" else "")
                        },
                        onImageClick = { urls, idx -> imagePreview = urls to idx }
                    )
                    TabId.FOLLOW -> AccountFeedScreen(
                        repo = followRepo,
                        account = followAccount,
                        displayName = followName,
                        onImageClick = { urls, idx -> imagePreview = urls to idx },
                        externalListState = followListState
                    )
                    TabId.ADMIN -> AdminScreen(
                        vm = adminVm,
                        onOpenDash = { navTo(Screen.AdminDash(it)) },
                        onOpenRequests = { navTo(Screen.AdminRequests) },
                        onNewArchive = { navTo(Screen.AdminNewArchive) }
                    )
                    TabId.SETTINGS -> SettingsScreen(
                        followSummary = if (followEnabled && followName.isNotBlank())
                            "已关注：$followName" else "在底栏固定显示某个账号",
                        onOpenFollow = { navTo(Screen.FollowSelect) },
                        onOpenTheme = { navTo(Screen.Theme) },
                        onOpenDefaultTab = { navTo(Screen.DefaultTab) },
                        onOpenBookmarks = { navTo(Screen.Bookmarks) },
                        onOpenRequest = { navTo(Screen.Request) },
                        onOpenAbout = { navTo(Screen.About) }
                    )
                }
            }
            dialogTarget?.let { t ->
                val statsVm: ProfileStatsViewModel = viewModel()
                androidx.compose.runtime.LaunchedEffect(t.repo, t.account) {
                    statsVm.load(t.repo, t.account)
                }
                val rawStats by statsVm.stats.collectAsState()
                val stats = if (rawStats.key == "${t.repo}/${t.account}") rawStats
                else ProfileStats(loading = true)
                ProfileDialog(
                    repo = io.github.twitterarchiver.data.ArchiveRepo(
                        repo = t.repo, name = t.name, acct = t.account,
                        username = t.handle, description = t.bio, avatar = t.avatar
                    ),
                    profile = null,
                    tweetCount = stats.tweets,
                    imageCount = stats.images,
                    onDismiss = { dialogTarget = null },
                    onOpenReader = {
                        navTo(Screen.Reader(t.repo, t.account, t.name))
                        dialogTarget = null
                    },
                    onOpenImages = {
                        navTo(Screen.Images(t.repo, t.account, t.name))
                        dialogTarget = null
                    }
                )
            }
            // 全站图片预览
            imagePreview?.let { (urls, idx) ->
                io.github.twitterarchiver.ui.components.ImagePreviewOverlay(
                    urls = urls, startIndex = idx,
                    onDismiss = { imagePreview = null }
                )
            }
        }
        is Screen.Reader -> AccountReaderScreen(
            repo = s.repo, account = s.account, displayName = s.name,
            onBack = { navBack() }
        )
        is Screen.AccountFeed -> AccountFeedScreen(
            repo = s.repo, account = s.account, displayName = s.name,
            onImageClick = { urls, idx -> imagePreview = urls to idx }
        )
        is Screen.Images -> {
            val imagesVm: ImagesViewModel = viewModel()
            androidx.compose.runtime.LaunchedEffect(s.account) { imagesVm.load(s.repo, s.account) }
            ImagesScreen(vm = imagesVm, title = s.name, onBack = { navBack() })
        }
        is Screen.Theme -> {
            val ctx = LocalContext.current
            val settings = remember { io.github.twitterarchiver.data.Settings(ctx) }
            val mode by settings.themeMode.collectAsState(initial = io.github.twitterarchiver.data.ThemeMode.SYSTEM)
            val dyn by settings.dynamicColor.collectAsState(initial = false)
            val bar by settings.barStyle.collectAsState(initial = "text")
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            ThemeScreen(
                current = mode, dynamicColor = dyn, barStyle = bar,
                onSetTheme = { scope.launch { settings.setTheme(it) } },
                onSetDynamic = { scope.launch { settings.setDynamicColor(it) } },
                onSetBarStyle = { scope.launch { settings.setBarStyle(it) } },
                onBack = { navBack() }
            )
        }
        is Screen.Bookmarks -> {
            val bmVm: BookmarkViewModel = viewModel()
            val list by bmVm.bookmarks.collectAsState()
            val bmCtx = LocalContext.current
            // 导出：写到用户选择的文件
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                uri?.let {
                    try {
                        bmCtx.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(bmVm.exportJson(list).toByteArray())
                        }
                        android.widget.Toast.makeText(bmCtx, "已导出 ${list.size} 条书签", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(bmCtx, "导出失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // 导入：从用户选择的文件读
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri ->
                uri?.let {
                    try {
                        val content = bmCtx.contentResolver.openInputStream(it)?.use { input ->
                            input.readBytes().decodeToString()
                        } ?: ""
                        bmVm.importJson(content) { count ->
                            val msg = if (count < 0) "导入失败：文件格式不正确" else "已导入 $count 条书签"
                            android.widget.Toast.makeText(bmCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(bmCtx, "导入失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            var previewBookmark by remember { mutableStateOf<io.github.twitterarchiver.data.Bookmark?>(null) }
            BookmarkScreen(
                bookmarks = list,
                onRemove = { bmVm.remove(it) },
                onExport = { exportLauncher.launch("twitterarchiver-bookmarks.json") },
                onImport = { importLauncher.launch("application/json") },
                onOpen = { previewBookmark = it },
                onBack = { navBack() }
            )
            // 点书签 → 弹出单条推文卡片（不跳页）
            previewBookmark?.let { b ->
                io.github.twitterarchiver.ui.components.SingleTweetDialog(
                    repo = b.repo,
                    account = b.account,
                    tweetId = b.tweetId,
                    fallbackName = b.authorName,
                    onDismiss = { previewBookmark = null },
                    onImageClick = { urls, idx -> imagePreview = urls to idx },
                    isBookmarked = list.any { it.tweetId == b.tweetId },
                    onBookmark = { bmVm.remove(b.tweetId); previewBookmark = null }
                )
            }
            // 卡片内点图 → 全屏预览
            imagePreview?.let { (urls, idx) ->
                io.github.twitterarchiver.ui.components.ImagePreviewOverlay(
                    urls = urls, startIndex = idx,
                    onDismiss = { imagePreview = null }
                )
            }
        }
        is Screen.Request -> {
            val reqVm: RequestViewModel = viewModel()
            RequestScreen(vm = reqVm, restrictedToken = restrictedToken, onBack = { navBack() })
        }
        is Screen.About -> AboutScreen(onBack = { navBack() }, onOpenThanks = { navTo(Screen.Thanks) })
        is Screen.Thanks -> ThanksScreen(onBack = { navBack() })
        is Screen.DefaultTab -> DefaultTabScreen(onBack = { navBack() })
        is Screen.FollowSelect -> FollowSelectScreen(
            homeVm = homeVm,
            settingsVm = settingsVm,
            onBack = { navBack() }
        )
        is Screen.AdminDash -> if ((screen as Screen.AdminDash).dash == DashRepo.HEALTH) {
            RepoHealthScreen(
                vm = adminVm,
                onBack = { navBack() },
                onOpenDash = { navTo(Screen.AdminDash(it)) },
                onOpenRepo = { navTo(Screen.AdminArchive(it)) },
                onOpenPrivate = { navTo(Screen.PrivateRepos) }
            )
        } else AdminDetailScreen(
            vm = adminVm,
            dash = (screen as Screen.AdminDash).dash,
            listState = adminListState,
            onBack = { navBack() },
            onEditYml = { repo, path -> navTo(Screen.AdminEditYml(repo, path)) },
            onDeleteTweets = { navTo(Screen.AdminDeleteTweets) },
            onNewArchive = { navTo(Screen.AdminNewArchive) },
            onOpenArchive = { navTo(Screen.AdminArchive(it)) }
        )
        is Screen.PrivateRepos -> RepoHealthScreen(
            vm = adminVm,
            privateOnly = true,
            onBack = { navBack() },
            onOpenDash = { },
            onOpenRepo = { navTo(Screen.AdminArchive(it)) },
            onOpenPrivate = { }
        )
        is Screen.AdminEditYml -> AdminEditYmlScreen(
            vm = adminVm,
            repo = (screen as Screen.AdminEditYml).repo,
            path = (screen as Screen.AdminEditYml).path,
            onBack = { navBack() }
        )
        is Screen.AdminDeleteTweets -> AdminDeleteTweetsScreen(
            vm = adminVm,
            onBack = { navBack() }
        )
        is Screen.AdminNewArchive -> AdminNewArchiveScreen(
            vm = adminVm,
            listState = newArchiveListState,
            onBack = { navBack() },
            onOpenArchive = { repoName -> navTo(Screen.AdminArchive(repoName)) }
        )
        is Screen.AdminArchive -> AdminArchiveScreen(
            vm = adminVm,
            repo = (screen as Screen.AdminArchive).repo,
            onBack = { navBack() },
            onEditProfile = { r, a -> navTo(Screen.AdminEditProfile(r, a)) },
            onOpenReader = { r, a -> navTo(Screen.Reader(r, a, r)) },
            onOpenFeed = { r, a -> navTo(Screen.AccountFeed(r, a, r)) }
        )
        is Screen.AdminEditProfile -> AdminEditProfileScreen(
            vm = adminVm,
            repo = (screen as Screen.AdminEditProfile).repo,
            account = (screen as Screen.AdminEditProfile).account,
            onBack = { navBack() }
        )
        is Screen.AdminRequests -> AdminRequestsScreen(
            vm = adminVm,
            onBack = { navBack() }
        )
    }
}
