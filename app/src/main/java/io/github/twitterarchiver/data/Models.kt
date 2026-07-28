package io.github.twitterarchiver.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 组织门面 repos.json 里的一条：一个存档账号 */
@Serializable
data class ArchiveRepo(
    val repo: String? = null,
    val name: String? = null,
    val acct: String? = null,
    val username: String? = null,
    val description: String? = null,
    val avatar: String? = null
) {
    val repoName: String get() = repo ?: name ?: ""
    val account: String get() = acct ?: repo ?: name ?: ""
    val displayName: String get() = name ?: repoName
    val handle: String get() = username ?: "@$account"
    /** 头像完整 URL：优先用 repos.json 里的真实 avatar 路径，回退到 avatar/avatar.jpg */
    val avatarUrl: String get() {
        val rel = avatar?.takeIf { it.isNotBlank() } ?: "avatar/avatar.jpg"
        return "${Config.snapshotsBase(repoName, account)}/$rel"
    }
}

/** 阅读器 index.json 里的一条推文 */
@Serializable
data class Tweet(
    val file: String = "",
    val timestamp: String = "",
    val date: String = "",
    val time: String = "",
    val text: String = "",
    @SerialName("tweet_id") val tweetId: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("is_reply") val isReply: Boolean = false,
    @SerialName("reply_to_id") val replyToId: String = "",
    @SerialName("has_quoted") val hasQuoted: Boolean = false,
    @SerialName("quoted_id") val quotedId: String = "",
    @SerialName("has_media") val hasMedia: Boolean = false,
    @SerialName("media_keys") val mediaKeys: List<String> = emptyList(),
    @SerialName("author_name") val authorName: String = "",
    @SerialName("author_username") val authorUsername: String = "",
    @SerialName("author_avatar") val authorAvatar: String = "",
    @SerialName("body_text") val bodyText: String = "",
    val images: List<String> = emptyList(),
    @SerialName("wanted_videos") val wantedVideos: List<String> = emptyList(),
    @SerialName("embedded_videos") val embeddedVideos: List<String> = emptyList(),
    @SerialName("embedded_images") val embeddedImages: List<String> = emptyList(),
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_virtual") val isVirtual: Boolean = false
) {
    val hasFile: Boolean get() = file.isNotBlank()
}

/** 账号 profile.json */
@Serializable
data class Profile(
    val name: String = "",
    val username: String = "",
    val bio: String = "",
    val location: String = "",
    val link: String = "",
    val avatar: String = "",
    val banner: String = "",
    val pinned: String = ""
)

/** GitHub Issue（用于访客申请存档 / 管理员接收） */
@Serializable
data class GitHubIssue(
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val state: String = "open",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val user: GitHubUser? = null
)

@Serializable
data class GitHubUser(
    val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String = ""
)

/** GitHub 仓库内容 API 响应（用于读取文件 sha） */
@Serializable
data class GitHubContent(
    val sha: String = "",
    val content: String = ""
)

/** 当前登录用户信息 */
@Serializable
data class AuthUser(
    val login: String = "",
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String = ""
)

/** GitHub Actions 工作流运行状态 */
@Serializable
data class WorkflowRun(
    val id: Long = 0,
    val name: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    val status: String? = null,        // queued/in_progress/completed
    val conclusion: String? = null,    // success/failure/cancelled
    @SerialName("run_started_at") val runStartedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("run_number") val runNumber: Int = 0
)

@Serializable
data class WorkflowRunsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("workflow_runs") val workflowRuns: List<WorkflowRun> = emptyList()
)

/* ── 离线索引清单（home/manifest/{repo}.json，由 build-manifest 工作流生成） ── */

@Serializable
data class IndexManifest(
    val repo: String = "",
    val account: String = "",
    val source: String = "",
    val etag: String = "",
    val bytes: Long = 0,
    val generated: String = "",
    val range: Boolean = false,
    val total: Int = 0,
    val months: Map<String, MonthSpan> = emptyMap(),
)

@Serializable
data class MonthSpan(
    val offset: Long = 0,
    val length: Long = 0,
    val count: Int = 0,
    val hash: String = "",
)

/** 本地已缓存月份的状态（存 index_cache/{repo}/{account}/_state.json） */
@Serializable
data class LocalIndexState(
    val months: Map<String, String> = emptyMap(),   // 月份 -> 清单哈希
    val total: Int = 0,
)
