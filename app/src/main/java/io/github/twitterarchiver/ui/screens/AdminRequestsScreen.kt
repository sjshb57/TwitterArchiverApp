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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.twitterarchiver.data.GitHubIssue
import io.github.twitterarchiver.ui.components.ConfirmDialog
import io.github.twitterarchiver.viewmodel.AdminViewModel

/** 处理存档申请：列出用户提交的 Issue，可批准（建档）或拒绝（关闭） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRequestsScreen(vm: AdminViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadRequests() }
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    confirm?.let { (title, msg, action) ->
        ConfirmDialog(title = title, message = msg, onConfirm = action, onDismiss = { confirm = null })
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("存档申请", fontSize = 17.sp) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                Text("刷新", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp).clickable { vm.loadRequests() })
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground)
        )
        when {
            state.requestsLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.requests.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("暂无待处理申请", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                items(state.requests) { req ->
                    RequestCard(
                        req,
                        onApprove = { acct ->
                            confirm = Triple("批准申请", "将为「$acct」建档并关闭申请，确定？"
                            ) { vm.approveRequest(req.number, acct) }
                        },
                        onReject = {
                            confirm = Triple("拒绝申请", "确定拒绝并关闭这条申请？"
                            ) { vm.rejectRequest(req.number) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestCard(req: GitHubIssue, onApprove: (String) -> Unit, onReject: () -> Unit) {
    // 从标题提取账号：标题格式「存档申请：<账号>」，再统一规范化（去 @ / URL / 空白）
    val account = io.github.twitterarchiver.util.AccountUtil.normalize(
        req.title.substringAfter("存档申请：", req.title)
    )
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(req.title, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        req.body?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        Text("#${req.number} · ${req.user?.login ?: "匿名"} · ${req.createdAt.substringBefore("T")}",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text("拒绝", fontSize = 13.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { onReject() }.padding(horizontal = 14.dp, vertical = 6.dp))
            Text("批准建档", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onApprove(account) }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}
