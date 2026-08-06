package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.ArchiveRepo
import io.github.twitterarchiver.ui.components.Avatar
import io.github.twitterarchiver.ui.theme.Accent
import io.github.twitterarchiver.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/** Tab 1：账号列表（顶部 logo+统计 + 搜索 + 带头像和简介的列表） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(
    vm: HomeViewModel,
    onOpenAccount: (ArchiveRepo) -> Unit,
    onAvatarClick: (ArchiveRepo) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            vm.load(forceRefresh = true)
            scope.launch { kotlinx.coroutines.delay(800); refreshing = false }
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
    Column(Modifier.fillMaxSize()) {
        // 顶部 A：大 logo + 可点击切换的统计小字
        var statStyle by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 双击大标题回到顶部，与双击底栏 Tab 一致
            Row(Modifier.combinedClickable(
                onClick = { },
                onDoubleClick = { scope.launch { listState.animateScrollToItem(0) } }
            )) {
                Text("Twitter", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("Archiver", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
            }
            val total = state.repos.size
            Text(
                if (statStyle == 0) "已存档 $total 个账号"
                else "基于 Wayback Machine 的永久存档",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { statStyle = 1 - statStyle }
            )
        }

        // 搜索框（自适应背景，收窄）
        io.github.twitterarchiver.ui.components.SearchField(
            value = state.query,
            onValueChange = vm::search,
            placeholder = "搜索账号…"
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))

        when {
            state.loading -> Box_Center { CircularProgressIndicator() }
            state.error != null -> Box_Center {
                Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.filtered) { repo ->
                    AccountRow(repo, onOpenAccount, onAvatarClick)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
            }
        }
    }
    }
}

@Composable
private fun AccountRow(
    repo: ArchiveRepo,
    onOpen: (ArchiveRepo) -> Unit,
    onAvatar: (ArchiveRepo) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(repo) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            url = repo.avatarUrl,
            size = 44.dp,
            modifier = Modifier.clickable { onAvatar(repo) }
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(repo.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(repo.handle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!repo.description.isNullOrBlank()) {
                Text(
                    repo.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun Box_Center(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) { content() }
}
