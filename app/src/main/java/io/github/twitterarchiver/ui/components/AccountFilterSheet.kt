package io.github.twitterarchiver.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.twitterarchiver.data.Config
import io.github.twitterarchiver.data.IndexAccount

/** 全站账号筛选面板：多选账号（勾选多个人 → 全站只看这些人）*/
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFilterSheet(
    accounts: List<IndexAccount>,
    currentSelected: Set<Pair<String, String>>,
    onConfirm: (Set<IndexAccount>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // 本地选中态（key = repo to account）
    val selected = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(currentSelected) } }
    val filtered = remember(accounts, query) {
        if (query.isBlank()) accounts
        else accounts.filter {
            it.n.contains(query, true) || it.u.contains(query, true) || it.a.contains(query, true)
        }
    }

    // skipPartiallyExpanded：关掉"半屏"中间态，面板只有展开/关闭两种状态。
    // 否则列表滚动会通过 nested-scroll 拖动面板，往回滑时面板跟着往下掉。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // 固定 72% 屏高：面板高度稳定，内部列表独立滚动
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("筛选账号（可多选）", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { selected.clear() }.padding(end = 12.dp))
                }
                Text("确定 (${selected.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val chosen = accounts.filter { (it.r to it.a) in selected }.toSet()
                        onConfirm(chosen)
                    })
            }

            SearchField(value = query, onValueChange = { query = it }, placeholder = "搜索账号…")
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            LazyColumn(Modifier.weight(1f)) {
                items(filtered) { acc ->
                    val key = acc.r to acc.a
                    val isSel = key in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (isSel) selected.remove(key) else selected.add(key)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(
                            url = "${Config.snapshotsBase(acc.r, acc.a)}/avatar/${acc.av.ifBlank { "avatar.jpg" }}",
                            size = 34.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acc.n, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(acc.u, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // 多选勾选框
                        Text(if (isSel) "☑" else "☐",
                            fontSize = 18.sp,
                            color = if (isSel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
