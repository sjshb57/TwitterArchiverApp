package io.github.twitterarchiver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.NetworkState
import io.github.twitterarchiver.data.QuotedTweet
import io.github.twitterarchiver.data.ThreadItem

/** 全站推文卡片（含引用原推 + 完整回复链） */
@Composable
fun GlobalPostCard(
    post: GlobalPost,
    onAvatarClick: () -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    isBookmarked: Boolean = false,
    onBookmark: () -> Unit = {},
    loadThread: suspend (GlobalPost) -> Pair<QuotedTweet?, List<ThreadItem>> = { null to emptyList() }
) {
    var expanded by remember(post.tweetId) { mutableStateOf(false) }
    var quoteExpanded by remember(post.tweetId) { mutableStateOf(false) }
    var loaded by remember(post.tweetId) { mutableStateOf(false) }
    var showShare by remember(post.tweetId) { mutableStateOf(false) }
    var playVideo by remember(post.tweetId) { mutableStateOf<String?>(null) }
    var quoted by remember(post.tweetId) { mutableStateOf<QuotedTweet?>(null) }
    var thread by remember(post.tweetId) { mutableStateOf<List<ThreadItem>>(emptyList()) }
    var loading by remember(post.tweetId) { mutableStateOf(false) }

    // 展开回复或展开引用时，按需加载对话数据（只加载一次）
    LaunchedEffect(expanded, quoteExpanded) {
        if ((expanded || quoteExpanded) && !loaded && !loading) {
            loading = true
            val (q, t) = loadThread(post)
            quoted = q; thread = t; loaded = true; loading = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        // 头像 + 名字 + 分享（顶部一行）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(url = post.avatarUrl, size = 44.dp,
                modifier = Modifier.clickable { onAvatarClick() })
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(post.account.n, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${post.account.u} · ${post.displayDate}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 分享按钮（右上角）
            Text("↗", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { showShare = true }.padding(start = 6.dp))
        }
        // 以下内容占满全宽（reader 风格，不缩在头像右侧）
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (post.text.isNotBlank()) {
                LinkedText(post.text, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 23.sp)
            }
            val imgs = post.mediaUrls
            if (imgs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                imgs.take(4).forEachIndexed { idx, url ->
                    PostImage(url, 14.dp,
                        if (idx < imgs.size - 1) 6.dp else 0.dp) { onImageClick(imgs, idx) }
                }
            }
            val vids = post.videoUrls
            if (vids.isNotEmpty()) {
                Spacer(Modifier.height(if (imgs.isEmpty()) 8.dp else 6.dp))
                vids.take(4).forEachIndexed { i, url ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    VideoThumb(url = url, onClick = { playVideo = url })
                }
            }

            // 精确时间（本地时区 yyyy-MM-dd HH:mm:ss，对齐 reader post-time-label）
            val fullTime = io.github.twitterarchiver.util.DateUtil.localDateTime(post.time)
            if (fullTime.isNotBlank()) {
                Text(fullTime, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }

            // 引用原推（默认折叠，点击展开）
            if (post.hasQuoteOrRt) {
                Text(
                    if (quoteExpanded) "收起引用" else "查看引用",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp).clickable { quoteExpanded = !quoteExpanded }
                )
                AnimatedVisibility(visible = quoteExpanded) {
                    quoted?.let { QuotedCard(it, onImageClick) }
                        ?: Text(if (loading) "加载中…" else "原推内容不可用",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (post.replyCount > 0 || (loaded && thread.isNotEmpty())) {
                    Text(
                        if (expanded) "收起回复" else "展开回复",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (isBookmarked) "已收藏" else "收藏",
                    fontSize = 11.sp,
                    fontWeight = if (isBookmarked) FontWeight.Bold else FontWeight.Normal,
                    color = if (isBookmarked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onBookmark() }
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp)
                ) {
                    when {
                        loading -> Text("加载中…", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        thread.isEmpty() -> Text("暂无回复", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> thread.forEach { item -> ReplyCard(item, onImageClick) }
                    }
                }
            }
        }
    }

    if (showShare) {
        ShareSheet(
            username = post.account.u,
            tweetId = post.tweetId,
            repo = post.account.r,
            onDismiss = { showShare = false }
        )
    }

    playVideo?.let { url ->
        VideoPlayerOverlay(url = url, onDismiss = { playVideo = null })
    }
}

/** 引用原推卡片（带边框，区别于主推文） */
/**
 * 推文图片。加载失败（离线、文件缺失）时整块收起，
 * 否则会留下一片与图片等高的空白，离线时满屏都是。
 */
@Composable
private fun PostImage(
    url: String,
    corner: Dp,
    bottomGap: Dp,
    onClick: () -> Unit
) {
    val gen = NetworkState.generation
    if (NetworkState.isFailed(url)) return
    val ctx = LocalContext.current
    AsyncImage(
        model = remember(url, gen, NetworkState.online) {
            val canNet = NetworkState.isOnlineNow()
            ImageRequest.Builder(ctx).data(url)
                .apply { if (!canNet) networkCachePolicy(CachePolicy.DISABLED) }
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = { st -> if (st is AsyncImagePainter.State.Error) NetworkState.markFailed(url) },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(corner))
            .clickable(onClick = onClick)
            .padding(bottom = bottomGap)
    )
}

/**
 * 视频封面：VideoFrameDecoder 取一帧当背景 + 播放按钮，点击进全屏播放。
 * 列表里不做内联自动播放，避免多个播放器同时占用解码器与流量。
 */
@Composable
private fun VideoThumb(url: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF11161B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .videoFrameMillis(1000)   // 取第 1 秒：开头常是黑场
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 压一层暗色，保证白色播放按钮在亮画面上也看得清
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        Box(
            Modifier.size(54.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow, contentDescription = "播放视频",
                tint = Color.White, modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun QuotedCard(q: QuotedTweet, onImageClick: (List<String>, Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (q.authorAvatarUrl.isNotBlank()) {
                Avatar(url = q.authorAvatarUrl, size = 22.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(q.authorName, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (q.authorUsername.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(q.authorUsername, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (q.text.isNotBlank()) {
            LinkedText(q.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (q.images.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            q.images.take(4).forEachIndexed { idx, url ->
                PostImage(url, 10.dp,
                    if (idx < q.images.size - 1) 6.dp else 0.dp) { onImageClick(q.images, idx) }
            }
        }
    }
}

/** 回复链里的一条卡片 */
@Composable
private fun ReplyCard(item: ThreadItem, onImageClick: (List<String>, Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        if (item.authorAvatarUrl.isNotBlank()) {
            Avatar(url = item.authorAvatarUrl, size = 26.dp)
        } else {
            Spacer(Modifier.width(26.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.authorName.ifBlank { "某账号" }, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!item.isOwner && item.authorUsername.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(item.authorUsername, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.isQuoted) {
                    Spacer(Modifier.width(5.dp))
                    Text("引用", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
            if (item.text.isNotBlank()) {
                LinkedText(item.text, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                    lineHeight = 17.sp, modifier = Modifier.padding(top = 1.dp))
            }
            if (item.images.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                item.images.take(4).forEachIndexed { idx, url ->
                    PostImage(url, 8.dp,
                        if (idx < item.images.size - 1) 4.dp else 0.dp) { onImageClick(item.images, idx) }
                }
            }
        }
    }
}

/** 分享弹层：推特原链接 + 推文 ID + 系统分享（照搬 reader openShare 的信息） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    username: String,
    tweetId: String,
    repo: String,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
    fun copy(text: String) =
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", text))
    val uname = username.removePrefix("@")
    val twitterUrl = if (uname.isNotBlank() && tweetId.isNotBlank())
        "https://twitter.com/$uname/status/$tweetId" else ""
    // 定位短码 = tweetId 后 8 位（对齐 reader openShare）
    val shortId = if (tweetId.length >= 8) tweetId.takeLast(8) else tweetId
    val locateUrl = if (shortId.isNotBlank() && repo.isNotBlank())
        "https://twitterarchiver.github.io/$repo/?t=$shortId" else ""
    val shortCode = if (shortId.isNotBlank()) "?t=$shortId" else ""

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("分享这条推文", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp))

            if (twitterUrl.isNotBlank()) {
                ShareRow("推特原链接", twitterUrl) {
                    copy(twitterUrl)
                }
            }
            if (tweetId.isNotBlank()) {
                ShareRow("推文 ID", tweetId) {
                    copy(tweetId)
                }
            }
            if (locateUrl.isNotBlank()) {
                ShareRow("存档定位链接（浏览器打开）", locateUrl) {
                    copy(locateUrl)
                }
            }
            if (shortCode.isNotBlank()) {
                ShareRow("定位短码（粘贴到搜索框）", shortCode) {
                    copy(shortCode)
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable {
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT,
                                twitterUrl.ifBlank { tweetId })
                        }
                        ctx.startActivity(android.content.Intent.createChooser(share, "分享"))
                        onDismiss()
                    }.padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("通过系统分享…", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 分享信息行：标签 + 值 + 点击复制 */
@Composable
private fun ShareRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("复制", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onCopy() }.padding(8.dp))
    }
}
