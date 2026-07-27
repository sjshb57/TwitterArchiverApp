package io.github.twitterarchiver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import io.github.twitterarchiver.ui.components.SearchField
import io.github.twitterarchiver.viewmodel.HomeViewModel
import io.github.twitterarchiver.viewmodel.SettingsViewModel

/** 关注对象选择：从所有账号里选一个固定成"关注"Tab。可关闭关注。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowSelectScreen(
    homeVm: HomeViewModel,
    settingsVm: SettingsViewModel,
    onBack: () -> Unit
) {
    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val enabled by settingsVm.followEnabled.collectAsStateWithLifecycle()
    val curAccount by settingsVm.followAccount.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val shown = remember(homeState.repos, query) {
        if (query.isBlank()) homeState.repos
        else homeState.repos.filter {
            it.displayName.contains(query, true) || it.account.contains(query, true) ||
                (it.username ?: "").contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("关注标签页", fontSize = 16.sp) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                if (enabled) TextButton(onClick = { settingsVm.setFollowEnabled(false); onBack() }) {
                    Text("关闭关注", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground)
        )
        Text("选择一个账号固定为「关注」标签页，打开应用即可直达，无需搜索。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SearchField(value = query, onValueChange = { query = it }, placeholder = "搜索账号…")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { r ->
                FollowRow(r, selected = enabled && curAccount == r.account) {
                    settingsVm.setFollow(r.repoName, r.account, r.displayName)
                    onBack()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            }
        }
    }
}

@Composable
private fun FollowRow(r: ArchiveRepo, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(url = r.avatarUrl, size = 40.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.username ?: "@${r.account}", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Filled.Check, "已选", tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
    }
}
