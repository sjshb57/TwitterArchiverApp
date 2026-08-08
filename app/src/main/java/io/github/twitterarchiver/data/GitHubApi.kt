package io.github.twitterarchiver.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.head
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        engine {
            // 默认 maxRequestsPerHost = 5，而本应用几乎所有请求都指向同一域名
            config {
                dispatcher(okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                })
            }
        }
        install(ContentNegotiation) { json(this@GitHubApi.json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    // ---------- 公开数据（无需 PAT）----------

    /** 拉取所有存档账号列表 */
    suspend fun fetchRepos(): List<ArchiveRepo> =
        client.get(Config.reposJsonUrl()).body()

    /** 近期时间线(timeline-recent.json)。成功则落盘，断网时回读本地副本 */
    suspend fun fetchRecentTimeline(): Pair<List<IndexAccount>, List<GlobalPost>> =
        withContext(Dispatchers.IO) {
            val cache = AppDirs.root?.let { java.io.File(it, "index_cache/_meta/timeline-recent.json") }
            try {
                val bytes = client.get(Config.timelineRecentUrl()).body<ByteArray>()
                val parsed = parseIndexStream(bytes.inputStream())
                cache?.let { runCatching { it.parentFile?.mkdirs(); it.writeBytes(bytes) } }
                parsed
            } catch (e: Exception) {
                val local = cache?.takeIf { it.isFile }
                    ?.let { f -> runCatching { parseIndexStream(f.inputStream()) }.getOrNull() }
                local ?: throw e
            }
        }

    // ---------- 全站索引分片 ----------

    /**
     * 取分片清单。网络失败时回退到本地副本，冷启动断网也能进全站页。
     */
    suspend fun fetchGlobalMeta(): GlobalIndexMeta = withContext(Dispatchers.IO) {
        val text = try {
            client.get(Config.globalIndexMetaUrl()).bodyAsText().also {
                // 写盘只是缓存，失败不应该让整次拉取作废
                try { GlobalIndexStore.writeMetaRaw(it) } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            GlobalIndexStore.readMetaRaw() ?: throw e
        }
        parseGlobalMeta(text)
    }

    /**
     * 取一个月度分片。本地副本哈希对得上就直接读盘，不发请求。
     */
    suspend fun fetchGlobalShard(
        shard: GlobalShard,
        accounts: List<IndexAccount>,
        onProgress: ((Long) -> Unit)? = null
    ): List<GlobalPost> = withContext(Dispatchers.IO) {
        val cached = if (GlobalIndexStore.isFresh(shard)) GlobalIndexStore.shardBytes(shard.month) else null
        val bytes = cached ?: run {
            val stream = client.get(Config.globalIndexShardUrl(shard.month))
                .bodyAsChannel().toInputStream()
            val out = java.io.ByteArrayOutputStream(shard.bytes.toInt().coerceAtLeast(8192))
            val buf = ByteArray(64 * 1024)
            var read = stream.read(buf)
            while (read >= 0) {
                out.write(buf, 0, read)
                onProgress?.invoke(out.size().toLong())
                read = stream.read(buf)
            }
            out.toByteArray().also {
                try { GlobalIndexStore.writeShard(shard.month, it) } catch (e: Exception) { }
            }
        }
        onProgress?.invoke(bytes.size.toLong())
        parsePostsStream(bytes.inputStream(), accounts)
    }

    /**
     * 流式解析 search-index / timeline-recent。
     * 用 android.util.JsonReader 从字节流边读边解析，避免把 25MB 文本 + JSON 树
     * 同时留在内存导致 OOM（旧实现 parseToJsonElement 会一次性建整棵树）。
     */
    private fun parseIndexStream(input: java.io.InputStream): Pair<List<IndexAccount>, List<GlobalPost>> {
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
                        while (reader.hasNext()) readPost(reader, accts)?.let { posts.add(it) }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return accts to posts
    }

    /** 一条 post：[idx, text, tweetId, time, [media], replyCount, hasQuote] */
    private fun readPost(reader: android.util.JsonReader, accts: List<IndexAccount>): GlobalPost? {
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
        val acct = accts.getOrNull(idx) ?: return null
        return GlobalPost(idx, text, tweetId, time, media, replyCount, hasQuote, acct)
    }

    /** 月度分片：{"posts": [...]}，账号下标指向 meta.json 的 accts */
    private fun parsePostsStream(
        input: java.io.InputStream,
        accounts: List<IndexAccount>
    ): List<GlobalPost> {
        val posts = ArrayList<GlobalPost>()
        android.util.JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "posts") {
                    reader.beginArray()
                    while (reader.hasNext()) readPost(reader, accounts)?.let { posts.add(it) }
                    reader.endArray()
                } else reader.skipValue()
            }
            reader.endObject()
        }
        return posts
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
                .replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), "")
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
    suspend fun fetchCrossReplies(): Map<String, List<CrossReply>> = withContext(Dispatchers.IO) {
        val result = HashMap<String, List<CrossReply>>()
        val stream = client.get(Config.crossRepliesUrl()).bodyAsChannel().toInputStream()
        android.util.JsonReader(stream.reader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val convId = reader.nextName()
                val list = ArrayList<CrossReply>()
                reader.beginArray()
                while (reader.hasNext()) {
                    val f = ArrayList<String?>(5)
                    reader.beginArray()
                    while (reader.hasNext()) {
                        f += if (reader.peek() == android.util.JsonToken.NULL) {
                            reader.nextNull(); null
                        } else reader.nextString()
                    }
                    reader.endArray()
                    list += CrossReply(
                        acctIndex = f.getOrNull(0)?.toIntOrNull() ?: 0,
                        tweetId = f.getOrNull(1) ?: "",
                        text = f.getOrNull(2) ?: "",
                        time = f.getOrNull(3) ?: "",
                        replyToId = f.getOrNull(4) ?: ""
                    )
                }
                reader.endArray()
                result[convId] = list
            }
            reader.endObject()
        }
        result
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
            client.head("$base/avatar/$fileName").status.isSuccess()
        } catch (e: Exception) { false }
    }

    /** 存档目录下某文件是否存在（Pages 直连，不耗 API 配额） */
    suspend fun snapshotFileExists(repo: String, account: String, relPath: String): Boolean = try {
        val p = relPath.removePrefix("../").trim()
        if (p.isBlank()) false
        else client.head("${Config.snapshotsBase(repo, account)}/$p").status.isSuccess()
    } catch (e: Exception) { false }

    /** 组织下是否已有该仓库。true=已存在，false=可用，null=查不到（网络/权限问题） */
    suspend fun repoExists(pat: String, name: String): Boolean? = try {
        val resp = client.get("${Config.API_BASE}/repos/${Config.ORG}/$name") {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        when (resp.status.value) {
            200 -> true
            404 -> false
            else -> null
        }
    } catch (e: Exception) { null }

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
    suspend fun fetchOrgRepos(pat: String): Result<List<OrgRepo>> = withContext(Dispatchers.IO) {
        try {
            val all = mutableListOf<OrgRepo>()
            var page = 1
            while (page <= 20) {
                val resp = client.get(Config.apiOrgRepos(page)) {
                    header("Authorization", "Bearer $pat")
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                }
                if (!resp.status.isSuccess()) {
                    return@withContext if (all.isEmpty()) Result.failure(Exception("HTTP ${resp.status}"))
                    else Result.success(all)
                }
                val batch = json.decodeFromString<List<OrgRepo>>(resp.bodyAsText())
                all += batch
                if (batch.size < 100) break
                page++
            }
            Result.success(all)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读 Dispatcher 的 dispatch.yml，解析每次触发几个仓库、每天触发几次。
     * 轮转周期是算超期阈值的依据，这两个值以后可能被改，所以动态读而不是写死。
     */
    suspend fun fetchRotationConfig(pat: String, repoCount: Int): RotationConfig? {
        val (content, _) = fetchFileContent(pat, "Dispatcher", ".github/workflows/dispatch.yml")
            .getOrNull() ?: return null
        val batch = Regex("""BATCH:\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""batch:\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val cronCount = Regex("""^\s*-\s*cron:""", RegexOption.MULTILINE)
            .findAll(content).count().coerceAtLeast(1)
        return RotationConfig(batch = batch, runsPerDay = cronCount, repoCount = repoCount)
    }

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
                Result.failure(Exception(explainDispatchError(resp.status, resp.bodyAsText(), workflow, inputs)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * GitHub 对 workflow_dispatch 的报错只有原始 JSON，定位困难。
     * 这里把实际遇到过的几种翻译成能直接照做的说明。
     */
    private fun explainDispatchError(
        status: HttpStatusCode,
        body: String,
        workflow: String,
        inputs: Map<String, String>
    ): String = when {
        status.value == 422 && body.contains("Unexpected inputs", ignoreCase = true) -> {
            val names = inputs.keys.joinToString("、")
            "该仓库的 $workflow 是旧版本，不接受参数「$names」。" +
                "请先把仓库里的 $workflow 更新到模板最新版再试。"
        }
        status.value == 422 && body.contains("Required input", ignoreCase = true) ->
            "$workflow 需要必填参数但本次没有提供，请检查该仓库的工作流定义。"
        status.value == 422 ->
            "$workflow 拒绝了这次触发（422）。多半是工作流文件与 App 传的参数对不上：$body"
        status.value == 404 ->
            "找不到 $workflow，可能该仓库还没有这个工作流文件，或令牌无权访问该仓库。"
        status.value == 403 ->
            "没有权限触发 $workflow。请确认令牌具备该仓库的 Actions 写权限。"
        status.value == 401 ->
            "令牌无效或已过期，请在设置里重新填写。"
        else -> "HTTP $status: $body"
    }

    /**
     * 模板仓库刚生成时 Actions 尚未注册工作流，直接触发会 404。
     * 轮询到出现为止，比固定 sleep 更快也更稳。
     */
    suspend fun workflowExists(pat: String, repo: String, workflow: String): Boolean = try {
        val resp = client.get("${Config.API_BASE}/repos/${Config.ORG}/$repo/actions/workflows/$workflow") {
            header("Authorization", "Bearer $pat")
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        resp.status.isSuccess()
    } catch (e: Exception) {
        false
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
    suspend fun closeIssue(pat: String, number: Int): Result<Unit> = try {
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

    companion object {
        private val metaJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 解析 search-index/meta.json。设置页只为读清单也 new 一个 GitHubApi 会顺带建 OkHttp 客户端，故放在伴生对象 */
        fun parseGlobalMeta(text: String): GlobalIndexMeta {
            val root = metaJson.parseToJsonElement(text).jsonObject
            fun str(o: kotlinx.serialization.json.JsonObject, k: String) =
                (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            fun num(o: kotlinx.serialization.json.JsonObject, k: String) =
                (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

            // 逐条容错：单个坏条目跳过即可，不该让整份清单作废
            val accts = (root["accts"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                IndexAccount(str(o, "r"), str(o, "a"), str(o, "u"), str(o, "n"), str(o, "av"))
            } ?: emptyList()

            val shards = (root["shards"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val m = str(o, "month")
                if (m.isBlank()) null
                else GlobalShard(m, num(o, "count").toInt(), num(o, "bytes"), str(o, "hash"))
            }?.sortedByDescending { it.month } ?: emptyList()

            return GlobalIndexMeta(
                total = num(root, "total").toInt(),
                generated = str(root, "generated"),
                accounts = accts,
                shards = shards
            )
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

