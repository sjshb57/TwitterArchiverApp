package io.github.twitterarchiver.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 统一的网络层：读公开数据 + 带 PAT 的写操作 */
class GitHubApi {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@GitHubApi.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    // ---------- 公开数据（无需 PAT）----------

    /** 拉取所有存档账号列表 */
    suspend fun fetchRepos(): List<ArchiveRepo> =
        client.get(Config.reposJsonUrl()).body()

    /** 拉取"最新一批"轻量时间线(timeline-recent.json，最新2000条，小而快) */
    suspend fun fetchRecentTimeline(): Pair<List<IndexAccount>, List<GlobalPost>> =
        parseIndexStreaming(Config.timelineRecentUrl())

    /** 拉取全站 search-index.json，解析成账号列表 + 全站推文列表 */
    suspend fun fetchSearchIndex(): Pair<List<IndexAccount>, List<GlobalPost>> =
        parseIndexStreaming(Config.searchIndexUrl())

    /**
     * 流式解析 search-index / timeline-recent。
     * 用 android.util.JsonReader 从字节流边读边解析，避免把 25MB 文本 + JSON 树
     * 同时留在内存导致 OOM（旧实现 parseToJsonElement 会一次性建整棵树）。
     */
    private suspend fun parseIndexStreaming(url: String): Pair<List<IndexAccount>, List<GlobalPost>> {
        val channel = client.get(url).bodyAsChannel()
        val input = channel.toInputStream()
        val accts = ArrayList<IndexAccount>()
        val posts = ArrayList<GlobalPost>()
        android.util.JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "accts" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var r = ""; var a = ""; var u = ""; var n = ""; var av = ""
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "r" -> r = reader.nextString()
                                    "a" -> a = reader.nextString()
                                    "u" -> u = reader.nextString()
                                    "n" -> n = reader.nextString()
                                    "av" -> av = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            accts.add(IndexAccount(r = r, a = a, u = u, n = n, av = av))
                        }
                        reader.endArray()
                    }
                    "posts" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            // 每条 post 是一个数组：[idx, text, tweetId, time, [media], replyCount, hasQuote]
                            reader.beginArray()
                            var idx = -1; var text = ""; var tweetId = ""; var time = ""
                            val media = ArrayList<String>(); var replyCount = 0; var hasQuote = false
                            var pos = 0
                            while (reader.hasNext()) {
                                when (pos) {
                                    0 -> idx = reader.nextInt()
                                    1 -> text = reader.nextStringSafe()
                                    2 -> tweetId = reader.nextStringSafe()
                                    3 -> time = reader.nextStringSafe()
                                    4 -> {
                                        if (reader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                                            reader.beginArray()
                                            while (reader.hasNext()) media.add(reader.nextStringSafe())
                                            reader.endArray()
                                        } else reader.skipValue()
                                    }
                                    5 -> replyCount = reader.nextIntSafe()
                                    6 -> hasQuote = reader.nextIntSafe() > 0
                                    else -> reader.skipValue()
                                }
                                pos++
                            }
                            reader.endArray()
                            val acct = accts.getOrNull(idx)
                            if (acct != null) {
                                posts.add(GlobalPost(idx, text, tweetId, time, media, replyCount, hasQuote, acct))
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return accts to posts
    }

    /** 拉取某账号的推文索引 */
    suspend fun fetchTweets(repo: String, account: String): List<Tweet> =
        client.get(Config.indexJsonUrl(repo, account)).body()

    /** 离线增量：拉取月度清单（不存在/失败返回 null，调用方退全量） */
    suspend fun fetchIndexManifest(repo: String): IndexManifest? = try {
        val resp = client.get(Config.indexManifestUrl(repo))
        if (resp.status.value != 200) null
        else json.decodeFromString<IndexManifest>(resp.bodyAsText())
    } catch (e: Exception) { null }

    /** 离线增量：整个 index.json 的原始字节（用于全量落盘/分片） */
    suspend fun fetchIndexBytes(repo: String, account: String): ByteArray =
        client.get(Config.indexJsonUrl(repo, account)).body()

    /**
     * 离线增量：按字节区间取 index.json 片段。
     * 返回 (HTTP 状态码, 字节)；206=片段，200=服务器给了全量（文件已变或不支持 Range）。
     * 失败返回 null。
     */
    suspend fun fetchIndexRange(
        repo: String, account: String, offset: Long, length: Long
    ): Pair<Int, ByteArray>? = try {
        val resp = client.get(Config.indexJsonUrl(repo, account)) {
            header(HttpHeaders.Range, "bytes=$offset-${offset + length - 1}")
        }
        resp.status.value to resp.body<ByteArray>()
    } catch (e: Exception) { null }

    /**
     * 解析某推文 html 里的转推/引用原推（embedded-tweet-container）。
     * RT 转推的原推内容只存在 html 里（index.json 无 quoted_id），点击时按需解析（B 方案）。
     * 返回 QuotedTweet（作者名/用户名/头像/正文/图），无则 null。
     */
    suspend fun fetchEmbeddedTweet(
        repo: String, account: String, file: String, embeddedImages: List<String>
    ): QuotedTweet? {
        if (file.isBlank()) return null
        val html = try {
            client.get(Config.tweetHtmlUrl(repo, account, file)).bodyAsText()
        } catch (e: Exception) { return null }

        val i = html.indexOf("class=\"embedded-tweet-container\"")
        if (i < 0) return null
        val seg = html.substring(i, minOf(i + 3000, html.length))

        fun grab(cls: String): String {
            val m = Regex("class=\"$cls\"[^>]*>\\s*([\\s\\S]*?)<").find(seg) ?: return ""
            return m.groupValues[1].trim()
        }
        // tweet-content 可能含多行（<br/> 分隔），单独抓整段到 </div>，保留换行
        fun grabContent(): String {
            val m = Regex("class=\"tweet-content\"[^>]*>([\\s\\S]*?)</div>").find(seg) ?: return ""
            return m.groupValues[1]
                .replace(Regex("<br\\s*/?>"), "\n")   // <br/> → 换行
                .replace(Regex("<[^>]+>"), "")         // 去掉其他标签
                .replace(Regex("https://t\\.co/\\S+"), "")
                .lines().joinToString("\n") { it.trim() }
                .replace(Regex("\n{2,}"), "\n")
                .trim()
        }
        // 作者头像
        val avatarRel = Regex("tweet-author-profile-image[\\s\\S]*?<img[^>]*src=\"([^\"]+)\"")
            .find(seg)?.groupValues?.get(1)?.trim() ?: ""
        val name = grab("tweet-author-name")
        val uname = grab("tweet-author-username")
        // 正文（多行，去掉 t.co 链接）
        val content = grabContent()
        if (name.isBlank() && content.isBlank()) return null

        val base = Config.snapshotsBase(repo, account)
        fun abs(rel: String): String {
            val n = rel.substringAfterLast('/')
            return if (n.isBlank()) "" else "$base/image/$n"
        }
        fun absAv(rel: String): String {
            val n = rel.substringAfterLast('/')
            return if (n.isBlank()) "" else "$base/avatar/$n"
        }
        return QuotedTweet(
            authorName = name,
            authorUsername = uname,
            authorAvatarUrl = absAv(avatarRel),
            text = content,
            images = embeddedImages.map { abs(it) }
        )
    }

    /** 拉取跨账号回复索引 cross-replies.json。key=主推文id, value=回复列表 */
    suspend fun fetchCrossReplies(): Map<String, List<CrossReply>> {
        val text = client.get(Config.crossRepliesUrl()).bodyAsText()
        val root = json.parseToJsonElement(text).jsonObject
        val result = HashMap<String, List<CrossReply>>()
        for ((convId, arr) in root) {
            val list = arr.jsonArray.mapNotNull { el ->
                try {
                    val a = el.jsonArray
                    CrossReply(
                        acctIndex = a[0].jsonPrimitive.int,
                        tweetId = a[1].jsonPrimitive.contentOrNull ?: "",
                        text = a[2].jsonPrimitive.contentOrNull ?: "",
                        time = a[3].jsonPrimitive.contentOrNull ?: "",
                        replyToId = a.getOrNull(4)?.jsonPrimitive?.contentOrNull ?: ""
                    )
                } catch (e: Exception) { null }
            }
            result[convId] = list
        }
        return result
    }

    /** 拉取某账号的 profile */
    suspend fun fetchProfile(repo: String, account: String): Profile =
        client.get(Config.profileJsonUrl(repo, account)).body()

    /**
     * 检测 banner 是否真实存在：查 profile.json 的 banner 字段指向的文件是否存在。
     * bannerPath 由调用方从已读的 profile 传入，避免重复读 profile.json。
     */
    suspend fun bannerExists(repo: String, account: String, bannerPath: String): Boolean {
        return try {
            val path = bannerPath.removePrefix("../").trim()
            if (path.isBlank()) return false
            val base = Config.snapshotsBase(repo, account)
            val fileName = path.substringAfterLast('/')
            val resp = client.get("$base/avatar/$fileName")
            resp.status.isSuccess()
        } catch (e: Exception) { false }
    }

    /** 存档目录下某文件是否存在（Pages 直连，不耗 API 配额） */
    suspend fun snapshotFileExists(repo: String, account: String, relPath: String): Boolean = try {
        val p = relPath.removePrefix("../").trim()
        if (p.isBlank()) false
        else client.get("${Config.snapshotsBase(repo, account)}/$p").status.isSuccess()
    } catch (e: Exception) { false }

    // ---------- 认证 ----------

    /** 用 PAT 验证身份，返回登录用户；失败返回 null */
    suspend fun verifyToken(pat: String): AuthUser? {
        return try {
            val resp = client.get(Config.apiUser()) {
                header("Authorization", "Bearer $pat")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            if (resp.status.isSuccess()) resp.body<AuthUser>() else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 管理操作（需 PAT）----------

    /** 触发某仓库的一个 workflow */
    /** 取消正在运行的 workflow run（手动暂停增量更新） */
    suspend fun cancelRun(pat: String, repo: String, runId: Long): Result<Unit> = try {
        val resp = client.post(Config.apiCancelRun(repo, runId)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (resp.status.isSuccess() || resp.status == HttpStatusCode.Accepted)
            Result.success(Unit) else Result.failure(Exception("HTTP ${resp.status}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 重跑失败的 workflow run（失败变红后手动重试） */
    suspend fun rerunRun(pat: String, repo: String, runId: Long): Result<Unit> = try {
        val resp = client.post(Config.apiRerunRun(repo, runId)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (resp.status.isSuccess() || resp.status == HttpStatusCode.Created)
            Result.success(Unit) else Result.failure(Exception("HTTP ${resp.status}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 从模板创建新仓库（建档第一步）。name=新仓库名(=账号名) */
    suspend fun generateRepo(pat: String, name: String, private: Boolean = false): Result<Unit> = try {
        val body = buildJsonObject {
            put("owner", Config.ORG)
            put("name", name)
            put("private", private)
        }
        val resp = client.post(Config.apiGenerateRepo()) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (resp.status.isSuccess() || resp.status == HttpStatusCode.Created)
            Result.success(Unit)
        else Result.failure(Exception("HTTP ${resp.status}: ${resp.bodyAsText()}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 读取仓库里某文件内容 + sha（编辑 yml 前先读） */
    suspend fun fetchFileContent(pat: String, repo: String, path: String): Result<Pair<String, String>> = try {
        val resp = client.get(Config.apiRepoContents(repo, path)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (resp.status.isSuccess()) {
            val o = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val contentB64 = o["content"]?.jsonPrimitive?.contentOrNull?.replace("\n", "") ?: ""
            val sha = o["sha"]?.jsonPrimitive?.contentOrNull ?: ""
            val decoded = String(android.util.Base64.decode(contentB64, android.util.Base64.DEFAULT))
            Result.success(decoded to sha)
        } else Result.failure(Exception("HTTP ${resp.status}"))
    } catch (e: Exception) { Result.failure(e) }

    /**
     * 读取仓库文件的原始 base64（不解码，用于图片等二进制）。
     * 返回 (base64NoWrap, sha)；失败返回 null。
     */
    suspend fun fetchFileBase64(pat: String, repo: String, path: String): Pair<String, String>? = try {
        val resp = client.get(Config.apiRepoContents(repo, path)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (resp.status.isSuccess()) {
            val o = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val b64 = o["content"]?.jsonPrimitive?.contentOrNull
                ?.replace("\n", "")?.replace("\r", "") ?: ""
            val sha = o["sha"]?.jsonPrimitive?.contentOrNull ?: ""
            if (b64.isBlank()) null else b64 to sha
        } else null
    } catch (e: Exception) { null }

    /**
     * 新建仓库文件（调用方已确认目标不存在，因此不带 sha，也不预查一次）。
     * 目标若已存在，GitHub 会返回 422，由调用方按失败处理。
     */
    suspend fun putNewFile(pat: String, repo: String, path: String,
                           base64: String, message: String): Result<Unit> = try {
        val body = buildJsonObject {
            put("message", message)
            put("content", base64)
        }
        val resp = client.put(Config.apiRepoContents(repo, path)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (resp.status.isSuccess()) Result.success(Unit)
        else Result.failure(Exception("HTTP ${resp.status}: ${resp.bodyAsText()}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 上传（覆盖）仓库里某文件（编辑 yml 后上传） */
    suspend fun putFileContent(pat: String, repo: String, path: String,
                                content: String, sha: String, message: String): Result<Unit> = try {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val body = buildJsonObject {
            put("message", message)
            put("content", b64)
            if (sha.isNotBlank()) put("sha", sha)
        }
        val resp = client.put(Config.apiRepoContents(repo, path)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (resp.status.isSuccess()) Result.success(Unit)
        else Result.failure(Exception("HTTP ${resp.status}: ${resp.bodyAsText()}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 上传二进制文件（banner 图片，content 已是 base64）。会先查已有 sha */
    suspend fun putFileContentRaw(pat: String, repo: String, path: String,
                                   base64: String, shaHint: String, message: String): Result<Unit> = try {
        // 若没传 sha，先尝试读现有文件的 sha（覆盖需要）
        var sha = shaHint
        if (sha.isBlank()) {
            fetchFileContent(pat, repo, path).onSuccess { sha = it.second }
        }
        val body = buildJsonObject {
            put("message", message)
            put("content", base64)
            if (sha.isNotBlank()) put("sha", sha)
        }
        val resp = client.put(Config.apiRepoContents(repo, path)) {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (resp.status.isSuccess()) Result.success(Unit)
        else Result.failure(Exception("HTTP ${resp.status}: ${resp.bodyAsText()}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 查询某仓库最近的工作流运行（管理版监控用） */
    suspend fun fetchWorkflowRuns(pat: String, repo: String): Result<List<WorkflowRun>> {
        return try {
            val resp = client.get(Config.apiRepoRuns(repo)) {
                header("Authorization", "Bearer $pat")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            if (resp.status.isSuccess()) {
                val body = json.decodeFromString<WorkflowRunsResponse>(resp.bodyAsText())
                Result.success(body.workflowRuns)
            } else {
                Result.failure(Exception("HTTP ${resp.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun dispatchWorkflow(
        pat: String,
        repo: String,
        workflow: String,
        inputs: Map<String, String> = emptyMap()
    ): Result<Unit> {
        return try {
            val bodyObj = buildJsonObject {
                put("ref", "main")
                if (inputs.isNotEmpty()) {
                    put("inputs", buildJsonObject {
                        inputs.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            val resp = client.post(Config.apiWorkflowDispatch(repo, workflow)) {
                header("Authorization", "Bearer $pat")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                contentType(ContentType.Application.Json)
                setBody(bodyObj)
            }
            // workflow dispatch 成功返回 204
            if (resp.status == HttpStatusCode.NoContent || resp.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${resp.status}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 读取仓库某文件的 sha（用于更新文件时提供 sha） */
    suspend fun getFileSha(pat: String, repo: String, path: String): String? {
        return try {
            val resp = client.get(Config.apiRepoContents(repo, path)) {
                header("Authorization", "Bearer $pat")
                header("Accept", "application/vnd.github+json")
            }
            if (resp.status.isSuccess()) resp.body<GitHubContent>().sha else null
        } catch (e: Exception) {
            null
        }
    }

    /** 上传/更新仓库文件（base64 内容）。用于传 banner 等 */

    // ---------- 申请存档（Issues）----------

    /** 访客提交存档申请（用受限 token 创建 Issue） */
    suspend fun createArchiveRequest(
        token: String,
        requestedAccount: String,
        note: String
    ): Result<Unit> {
        return try {
            val bodyObj = buildJsonObject {
                put("title", "存档申请：$requestedAccount")
                put("body", "申请存档账号：@$requestedAccount\n\n备注：$note")
            }
            val resp = client.post(Config.apiRequestIssues()) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                contentType(ContentType.Application.Json)
                setBody(bodyObj)
            }
            if (resp.status.isSuccess()) Result.success(Unit)
            else Result.failure(Exception("HTTP ${resp.status}: ${resp.bodyAsText()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 关闭申请 Issue（处理完/拒绝） */
    suspend fun closeIssue(pat: String, number: Int, comment: String? = null): Result<Unit> = try {
        // 可选先评论
        if (!comment.isNullOrBlank()) {
            client.post("${Config.apiRequestIssues()}/$number/comments") {
                header("Authorization", "Bearer $pat")
                header("Accept", "application/vnd.github+json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("body", comment) })
            }
        }
        val resp = client.patch("${Config.apiRequestIssues()}/$number") {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("state", "closed") })
        }
        if (resp.status.isSuccess()) Result.success(Unit)
        else Result.failure(Exception("HTTP ${resp.status}"))
    } catch (e: Exception) { Result.failure(e) }

    /** 管理员拉取所有存档申请（open 状态的 Issue） */
    suspend fun fetchArchiveRequests(pat: String): List<GitHubIssue> {
        return try {
            client.get("${Config.apiRequestIssues()}?state=open&per_page=100") {
                header("Authorization", "Bearer $pat")
                header("Accept", "application/vnd.github+json")
            }.body()
        } catch (e: Exception) {
            emptyList()
        }
    }

}

/** JsonReader 遇到 null 时安全取字符串 */
private fun android.util.JsonReader.nextStringSafe(): String {
    return if (peek() == android.util.JsonToken.NULL) { nextNull(); "" } else nextString()
}
/** JsonReader 遇到 null 时安全取 int */
private fun android.util.JsonReader.nextIntSafe(): Int {
    return if (peek() == android.util.JsonToken.NULL) { nextNull(); 0 } else nextInt()
}
