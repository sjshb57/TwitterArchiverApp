package io.github.twitterarchiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.viewmodel.GlobalTimelineViewModel

/**
 * 单条推文卡片浮层：只显示一条推文，渲染方式与个推页完全一致
 * （头像/正文/图片/引用/展开回复/分享/收藏），但不带 banner 和账号资料头。
 */
@Composable
fun SingleTweetDialog(
    repo: String,
    account: String,
    tweetId: String,
    fallbackName: String,
    onDismiss: () -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    isBookmarked: Boolean = false,
    onBookmark: () -> Unit = {},
    globalVm: GlobalTimelineViewModel = viewModel()
) {
    var post by remember(tweetId) { mutableStateOf<GlobalPost?>(null) }
    var loading by remember(tweetId) { mutableStateOf(true) }
    var error by remember(tweetId) { mutableStateOf<String?>(null) }

    val repository = remember { Repository() }

    LaunchedEffect(repo, account, tweetId) {
        loading = true
        error = null
        try {
            val profile = repository.getProfile(repo, account)
            val tweets = repository.getTweets(repo, account)
            val t = tweets.firstOrNull { it.tweetId == tweetId }
            if (t == null) {
                error = "这条推文已不在存档中"
            } else {
                val idxAccount = IndexAccount(
                    r = repo, a = account,
                    u = profile.username.ifBlank { "@$account" },
                    n = profile.name.ifBlank { fallbackName },
                    av = profile.avatar.substringAfterLast('/')
                )
                // 回复数：本账号中回复该推文的条数
                val replyCount = tweets.count { it.isReply && it.conversationId == t.conversationId && it.tweetId != t.tweetId }
                post = GlobalPost(
                    acctIndex = 0,
                    text = t.bodyText.ifBlank { t.text },
                    tweetId = t.tweetId,
                    time = t.timestamp,
                    media = t.images.map { it.substringAfterLast('/') },
                    replyCount = replyCount,
                    hasQuote = t.hasQuoted,
                    account = idxAccount
                )
            }
        } catch (e: Exception) {
            error = "加载失败"
        }
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp)
        ) {
            Column {
                // 顶部：标题 + 关闭
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "推文", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close, "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(strokeWidth = 2.dp) }

                    error != null -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            error!!, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> post?.let { p ->
                        Column(
                            Modifier.verticalScroll(rememberScrollState())
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            GlobalPostCard(
                                post = p,
                                onAvatarClick = { },
                                onImageClick = onImageClick,
                                loadThread = { globalVm.loadThread(it) },
                                isBookmarked = isBookmarked,
                                onBookmark = onBookmark
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}
