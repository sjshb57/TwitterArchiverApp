package io.github.twitterarchiver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.twitterarchiver.data.Config
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.data.Tweet
import io.github.twitterarchiver.ui.components.Avatar
import io.github.twitterarchiver.ui.components.GlobalPostCard
import io.github.twitterarchiver.viewmodel.GlobalTimelineViewModel
import io.github.twitterarchiver.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch

/**
 * 原生个人推文页（照搬 reader.html 的结构与交互）：
 * 账号头 + 吸顶(搜索框+推文/回复Tab) + 日期树(年→月→日,带每天数量) + 无限滚动。
 * 引用/转推折叠、展开回复链、置顶推文均对齐 reader。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AccountFeedScreen(
    repo: String,
    account: String,
    displayName: String,
    onImageClick: (List<String>, Int) -> Unit,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    readerVm: ReaderViewModel = viewModel(),
    globalVm: GlobalTimelineViewModel = viewModel(),
    bookmarkVm: io.github.twitterarchiver.viewmodel.BookmarkViewModel = viewModel()
) {
    val state by readerVm.state.collectAsStateWithLifecycle()
    val bookmarks by bookmarkVm.bookmarks.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) } // 0=推文 1=回复
    val listState = externalListState ?: rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showDateTree by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(repo, account) { readerVm.load(repo, account) }

    val idxAccount = remember(repo, account, displayName, state.profile) {
        IndexAccount(
            r = repo, a = account,
            u = state.profile.username.ifBlank { "@$account" },
            n = state.profile.name.ifBlank { displayName },
            av = state.profile.avatar.substringAfterLast('/')
        )
    }

    // 每条主推文的回复数（本账号回复 replyMap[conversation_id] + 跨账号 cross-replies）
    val replyCountMap = remember(state.allTweets) {
        val map = HashMap<String, Int>()
        for (t in state.allTweets) {
            if (!t.isVirtual && t.isReply && t.conversationId.isNotBlank()) {
                map[t.conversationId] = (map[t.conversationId] ?: 0) + 1
            }
        }
        map
    }

    val pinnedId = state.profile.pinned
    // 当前 Tab 的推文流（搜索/日期已在 visibleTweets 里过滤）
    val feed = remember(state.allTweets, state.visibleTweets, tab, pinnedId, state.searchQuery, state.activeDay) {
        val base = if (state.searchQuery.isNotBlank() || state.activeDay != null)
            state.visibleTweets else state.allTweets
        val filtered = base.filter { t ->
            t.hasFile && !t.isVirtual && if (tab == 0) !t.isReply else t.isReply
        }.sortedByDescending { it.timestamp }
        if (tab == 0) {
            // 置顶判断：profile.pinned 匹配 或 推文自身 is_pinned=true（兼容两种数据）
            val pin = filtered.filter { it.tweetId == pinnedId || it.isPinned }
            val rest = filtered.filter { !(it.tweetId == pinnedId || it.isPinned) }
            if (pin.isNotEmpty()) pin + rest else filtered
        } else filtered
    }

    // 日期树（当前 Tab）：年 → 月 → 日 → 数量
    val dateTree = remember(state.allTweets, tab) {
        val src = state.allTweets.filter { it.hasFile && !it.isVirtual &&
            if (tab == 0) !it.isReply else it.isReply }
        val tree = sortedMapOf<String, java.util.TreeMap<String, java.util.TreeMap<String, Int>>>(compareByDescending { it })
        for (t in src) {
            val parts = io.github.twitterarchiver.util.DateUtil.localDate(t.timestamp).split("-")
            if (parts.size != 3) continue
            val (y, m, d) = parts
            val yMap = tree.getOrPut(y) { java.util.TreeMap(compareByDescending { it }) }
            val mMap = yMap.getOrPut(m) { java.util.TreeMap(compareByDescending { it }) }
            mMap[d] = (mMap[d] ?: 0) + 1
        }
        tree
    }

    // 日期 → feed 索引（跳转）。前置项：账号头(1) + 吸顶(1) = 2
    val headerCount = 2
    val dateToIndex = remember(feed) {
        val map = HashMap<String, Int>()
        feed.forEachIndexed { i, t ->
            val ld = io.github.twitterarchiver.util.DateUtil.localDate(t.timestamp)
            if (ld.isNotBlank() && !map.containsKey(ld)) map[ld] = i + headerCount
        }
        map
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val statusBarH = androidx.compose.foundation.layout.WindowInsets.statusBars
            .asPaddingValues().calculateTopPadding()
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                ProfileHeader(state.profile, repo, account, displayName,
                    showHamburger = true, onHamburger = { showDateTree = true })
            }
            stickyHeader {
                val stuck by remember {
                    androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex >= 1 }
                }
                Column(Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)) {
                    // 搜索行：（吸顶后）汉堡 + 搜索框
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (stuck) {
                            HamburgerIcon(
                                onBanner = false,
                                modifier = Modifier.clickable { showDateTree = true })
                            Spacer(Modifier.width(10.dp))
                        }
                        SearchInner(
                            value = query,
                            onValueChange = { query = it; readerVm.search(it) },
                            onClear = { query = ""; readerVm.search("") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TabBtn("推文", tab == 0, Modifier.weight(1f)) { tab = 0 }
                        TabBtn("回复", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
            }
            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                feed.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> items(feed, key = { it.tweetId }) { t ->
                    val isPin = tab == 0 && (t.tweetId == pinnedId || t.isPinned)
                    if (isPin) {
                        Text("📌 已置顶", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp))
                    }
                    val gp = t.toGlobalPost(idxAccount, replyCountMap[t.tweetId] ?: 0)
                    GlobalPostCard(
                        post = gp,
                        onAvatarClick = { },
                        onImageClick = onImageClick,
                        loadThread = { globalVm.loadThread(it) },
                        isBookmarked = bookmarks.any { b -> b.tweetId == gp.tweetId },
                        onBookmark = {
                            if (bookmarks.any { b -> b.tweetId == gp.tweetId }) {
                                bookmarkVm.remove(gp.tweetId)
                            } else {
                                bookmarkVm.add(io.github.twitterarchiver.data.Bookmark(
                                    tweetId = gp.tweetId,
                                    repo = gp.account.r,
                                    account = gp.account.a,
                                    authorName = gp.account.n,
                                    text = gp.text,
                                    date = gp.displayDate
                                ))
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
                }
            }
        }

        // 状态栏区域：纯背景色（不做 banner 沉浸，避免割裂）
        Box(Modifier.fillMaxWidth().height(statusBarH)
            .background(MaterialTheme.colorScheme.background))

    }

    // 日期树弹层
    if (showDateTree) {
        DateTreeSheet(
            tree = dateTree,
            activeDay = state.activeDay,
            onPick = { day ->
                showDateTree = false
                // 只滚动定位到那天（不筛选，保持无限滚动）
                dateToIndex[day]?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
            },
            onClear = {
                showDateTree = false
                scope.launch { listState.scrollToItem(0) }
            },
            onDismiss = { showDateTree = false }
        )
    }
}

/** 细汉堡按钮：三条 17dp 宽、1.5dp 细线（对齐 reader menu-toggle） */
@Composable
private fun HamburgerIcon(onBanner: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.size(24.dp),
        verticalArrangement = Arrangement.spacedBy(3.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(4.dp))
        repeat(3) {
            if (onBanner) {
                // banner 上：白线 + 深色描边（阴影），白/深 banner 都可见
                Box(Modifier.width(17.dp).height(2.dp)) {
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.Black.copy(alpha = 0.45f)))
                    Box(Modifier.fillMaxWidth().height(1.5.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.95f)))
                }
            } else {
                Box(Modifier.width(17.dp).height(1.5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

/** 搜索框内部：🔍 + 输入 + ✕（全圆，照搬 reader search-inner） */
@Composable
private fun SearchInner(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔍", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            ) { inner ->
                if (value.isEmpty()) Text("搜索关键词或日期…", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            }
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            // 用矢量图标而非 ✕ 字形：字形自带基线留白，在容器里看着不居中
            Box(Modifier.size(22.dp).clip(CircleShape).clickable { onClear() },
                Alignment.Center) {
                Icon(
                    Icons.Filled.Close, contentDescription = "清空",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Tab 按钮（推文/回复），选中有下划线 */
@Composable
private fun TabBtn(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable { onClick() }.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (selected) {
            Box(Modifier.width(56.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary))
        } else {
            Spacer(Modifier.height(3.dp))
        }
    }
}

/** 账号资料头：照搬 reader 字号 + banner 渐变遮罩沉浸（延伸到状态栏） */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileHeader(
    profile: io.github.twitterarchiver.data.Profile,
    repo: String, account: String, displayName: String,
    showHamburger: Boolean = false,
    onHamburger: () -> Unit = {}
) {
    val base = Config.snapshotsBase(repo, account)
    val bannerUrl = if (profile.banner.isNotBlank())
        "$base/${profile.banner.removePrefix("../")}" else null
    val statusBarH = androidx.compose.foundation.layout.WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxWidth()) {
        // 顶部留出状态栏高度（状态栏区域为纯背景色，不做 banner 沉浸）
        Spacer(Modifier.height(statusBarH))
        Box(Modifier.fillMaxWidth()) {
            // banner：固定 170dp（reader 尺寸，正常位置，不被状态栏切）
            if (bannerUrl != null) {
                AsyncImage(
                    model = bannerUrl,
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(170.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant))
            } else {
                Box(Modifier.fillMaxWidth().height(170.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            }
            // 头像：压在 banner 下方一半
            Box(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 14.dp).offset(y = 50.dp)
                    .size(100.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background).padding(3.dp)
            ) {
                Avatar(
                    url = if (profile.avatar.isNotBlank()) "$base/${profile.avatar.removePrefix("../")}" else "",
                    size = 94.dp)
            }
            // 关注页：banner 左上角汉堡（随 banner 滚动划走，吸顶后由搜索栏汉堡接替）
            if (showHamburger) {
                Box(
                    Modifier.align(Alignment.TopStart)
                        .padding(8.dp).size(38.dp)
                        .clickable { onHamburger() },
                    contentAlignment = Alignment.Center
                ) {
                    HamburgerIcon(onBanner = true)
                }
            }
        }
        Spacer(Modifier.height(56.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            // 名字 17sp/800, 用户名 14sp, bio 14sp（对齐 reader 移动端）
            Text(profile.name.ifBlank { displayName }, fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold, lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onBackground)
            Text(profile.username.ifBlank { "@$account" }, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile.bio.isNotBlank()) {
                val bioAnnotated = buildBioAnnotated(profile.bio,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onBackground)
                Text(bioAnnotated, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
            // 📍🔗：对齐 reader p-meta。字号用 dp→sp 固定，不受系统字体缩放影响
            // （reader 用 px，与系统缩放无关；这样换行行为和 reader 一致）
            if (profile.location.isNotBlank() || profile.link.isNotBlank()) {
                val metaSize = with(androidx.compose.ui.platform.LocalDensity.current) { 13.dp.toSp() }
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (profile.location.isNotBlank())
                        Text("📍 ${profile.location}", fontSize = metaSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.link.isNotBlank()) {
                        val shown = profile.link.replace(Regex("^https?://"), "")
                        Text("🔗 $shown", fontSize = metaSize,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 日期树弹层：年→月→日折叠，每天带推文数（照搬 reader date-tree） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTreeSheet(
    tree: Map<String, java.util.TreeMap<String, java.util.TreeMap<String, Int>>>,
    activeDay: String?,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            tree.forEach { (year, months) ->
                item {
                    var yearOpen by remember(year) { mutableStateOf(true) }
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { yearOpen = !yearOpen }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (yearOpen) "▼" else "▶", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("$year 年", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        AnimatedVisibility(visible = yearOpen) {
                            Column {
                                months.forEach { (month, days) ->
                                    MonthNode(year, month, days, activeDay, onPick)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun MonthNode(
    year: String, month: String, days: java.util.TreeMap<String, Int>,
    activeDay: String?, onPick: (String) -> Unit
) {
    var open by remember(year, month) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }
            .padding(start = 28.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▼" else "▶", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("$month 月", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = open) {
            Column {
                days.forEach { (day, count) ->
                    val full = "$year-$month-$day"
                    val active = activeDay == full
                    Row(Modifier.fillMaxWidth().clickable { onPick(full) }
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                        .padding(start = 42.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("$day 日", fontSize = 14.sp,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f))
                        // 推文数徽章
                        Box(Modifier.clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("$count", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Tweet → GlobalPost */
private fun Tweet.toGlobalPost(account: IndexAccount, replyCount: Int): GlobalPost {
    return GlobalPost(
        acctIndex = 0,
        // 优先 body_text：它保留原推换行，text 会把换行压成空格；
        // 全站时间线的 search-index 也取 body_text，两处一致
        text = bodyText.ifBlank { text },
        tweetId = tweetId,
        time = timestamp,
        // 图片 + 视频都放进 media，由 GlobalPost 按扩展名分流到 image/ 与 video/
        media = (images + wantedVideos + embeddedVideos)
            .map { it.substringAfterLast('/') }
            .distinct(),
        replyCount = replyCount,
        hasQuote = hasQuoted,
        account = account
    )
}

/** bio 高亮：@用户名 + http/https 链接 显示为强调色（对齐 reader renderBioWithEntities） */
private fun buildBioAnnotated(
    bio: String,
    accent: Color,
    normal: Color
): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        // 匹配 @用户名 或 http(s) 链接
        val regex = Regex("(@[A-Za-z0-9_]+)|(https?://\\S+)")
        var last = 0
        for (m in regex.findAll(bio)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > last) {
                withStyle(androidx.compose.ui.text.SpanStyle(color = normal)) {
                    append(bio.substring(last, start))
                }
            }
            withStyle(androidx.compose.ui.text.SpanStyle(color = accent)) {
                append(bio.substring(start, end))
            }
            last = end
        }
        if (last < bio.length) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = normal)) {
                append(bio.substring(last))
            }
        }
    }
}
