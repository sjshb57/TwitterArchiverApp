package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.ui.components.GlobalPostCard
import io.github.twitterarchiver.viewmodel.GlobalTimelineViewModel
import kotlinx.coroutines.launch

/** Tab 2：全站时间线（用 search-index.json，分页 + 搜索） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GlobalScreen(
    vm: GlobalTimelineViewModel,
    onAvatarClick: (GlobalPost) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    bookmarkVm: io.github.twitterarchiver.viewmodel.BookmarkViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bookmarks by bookmarkVm.bookmarks.collectAsStateWithLifecycle()
    var showFilter by remember { mutableStateOf(false) }
    var showDates by remember { mutableStateOf(false) }

    // 滚动到底自动加载更多
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.visible.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) vm.loadMore()
    }

    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            io.github.twitterarchiver.data.NetworkState.clearFailed()
            vm.load()
            refreshScope.launch { kotlinx.coroutines.delay(800); refreshing = false }
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (state.filterAccounts.isEmpty()) "全站时间线"
                else "已筛选 ${state.filterAccounts.size} 人",
                fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onDoubleClick = { refreshScope.launch { listState.animateScrollToItem(0) } }
                ))
            state.indexError?.let { err ->
                Text(err, fontSize = 9.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        .clickable { vm.clearIndexError() })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp)
            ) {
                val browsing = state.query.isBlank() && state.filterAccounts.isEmpty() &&
                    state.activeDate == null
                // 默认显示全站总量（来自 meta.json，与本地下载了多少无关），
                // 有搜索/筛选时才切成当前结果数
                val shown = if (browsing && state.globalTotal > 0) state.globalTotal else state.totalCount
                if (shown > 0) {
                    Text("共 %,d 条".format(shown), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" · ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (state.activeDate != null) "按日期·${state.activeDate}" else "按日期",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showDates = true })
                if (state.activeDate != null) {
                    Text(" · ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("清除", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.clearDate() })
                }
                Text(" · ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("筛选账号", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showFilter = true })
                if (state.filterAccounts.isNotEmpty()) {
                    Text(" · ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("清除", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.filterByAccounts(emptySet()) })
                }
            }
        }

        // 搜索框（自适应背景）
        io.github.twitterarchiver.ui.components.SearchField(
            value = state.query,
            onValueChange = vm::search,
            placeholder = "搜索全站推文…"
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在加载全站索引…", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.visible) { post ->
                    GlobalPostCard(
                        post = post,
                        onAvatarClick = { onAvatarClick(post) },
                        onImageClick = onImageClick,
                        loadThread = { vm.loadThread(it) },
                        isBookmarked = bookmarks.any { it.tweetId == post.tweetId },
                        onBookmark = {
                            if (bookmarks.any { it.tweetId == post.tweetId }) {
                                bookmarkVm.remove(post.tweetId)
                            } else {
                                bookmarkVm.add(io.github.twitterarchiver.data.Bookmark(
                                    tweetId = post.tweetId,
                                    repo = post.account.r,
                                    account = post.account.a,
                                    authorName = post.account.n,
                                    text = post.text,
                                    date = post.displayDate
                                ))
                            }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
                if (state.canLoadMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
    }

    // 账号筛选面板
    if (showDates) {
        io.github.twitterarchiver.ui.components.GlobalDateTreeSheet(
            shards = state.shards,
            downloaded = state.downloadedMonths,
            progress = state.monthProgress,
            dayCounts = state.dayCounts,
            activeDate = state.activeDate,
            onPickDate = { vm.pickDate(it); showDates = false },
            onDownloadYear = { vm.downloadYear(it) },
            onDownloadMonth = { vm.downloadMonth(it) },
            onDeleteYear = { vm.deleteYear(it) },
            onDeleteMonth = { vm.deleteMonth(it) },
            onClear = { vm.clearDate(); showDates = false },
            onDismiss = { showDates = false }
        )
    }

    if (showFilter) {
        io.github.twitterarchiver.ui.components.AccountFilterSheet(
            accounts = state.accounts,
            currentSelected = state.filterAccounts,
            onConfirm = { chosen ->
                vm.filterByAccounts(chosen)
                showFilter = false
            },
            onDismiss = { showFilter = false }
        )
    }
}
