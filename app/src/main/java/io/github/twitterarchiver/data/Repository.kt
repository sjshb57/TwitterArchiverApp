package io.github.twitterarchiver.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 数据仓库：统一入口，带简单内存缓存 */
class Repository(private val api: GitHubApi = GitHubApi()) {

    private val offline = OfflineIndexStore(api)
    private val diskJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false
    }
    private fun metaFile(name: String) =
        AppDirs.root?.let { java.io.File(it, "index_cache/_meta").apply { mkdirs() } }
            ?.let { java.io.File(it, name) }

    private var reposCache: List<ArchiveRepo>? = null
    private val tweetsCache = mutableMapOf<String, List<Tweet>>()
    private val profileCache = mutableMapOf<String, Profile>()

    /** 所有存档账号（排除非账号仓库） */
    suspend fun getRepos(forceRefresh: Boolean = false): List<ArchiveRepo> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) reposCache?.let { return@withContext it }
            val list = try {
                api.fetchRepos().filter { it.repoName.isNotBlank() }.also { fresh ->
                    // 成功即落盘，断网时兜底
                    metaFile("repos.json")?.let { f ->
                        try { f.writeText(diskJson.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(ArchiveRepo.serializer()), fresh)) }
                        catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                metaFile("repos.json")?.takeIf { it.isFile }?.let { f ->
                    try { diskJson.decodeFromString<List<ArchiveRepo>>(f.readText()) }
                    catch (e2: Exception) { null }
                } ?: throw e
            }
            reposCache = list
            list
        }

    /** 某账号的推文（过滤掉无文件的虚拟条目由 UI 决定是否显示） */
    suspend fun getTweets(
        repo: String,
        account: String,
        forceRefresh: Boolean = false
    ): List<Tweet> = withContext(Dispatchers.IO) {
        val key = "$repo/$account"
        if (!forceRefresh) tweetsCache[key]?.let { return@withContext it }
        // 离线增量层（本地按月缓存 + 清单比对 + Range 补差）；不可用时退直连
        val list = offline.load(repo, account) ?: api.fetchTweets(repo, account)
        tweetsCache[key] = list
        list
    }

    suspend fun getProfile(repo: String, account: String): Profile =
        withContext(Dispatchers.IO) {
            val key = "$repo/$account"
            profileCache[key]?.let { return@withContext it }
            val p = try {
                api.fetchProfile(repo, account).also { fresh ->
                    metaFile("profile_$repo.json")?.let { f ->
                        try { f.writeText(diskJson.encodeToString(Profile.serializer(), fresh)) }
                        catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                metaFile("profile_$repo.json")?.takeIf { it.isFile }?.let { f ->
                    try { diskJson.decodeFromString<Profile>(f.readText()) }
                    catch (e2: Exception) { null }
                } ?: Profile()
            }
            profileCache[key] = p
            p
        }

    /** 检测 banner 图是否真实存在（传入 profile 的 banner 字段路径，避免重复读） */
    suspend fun bannerExists(repo: String, account: String, bannerPath: String): Boolean =
        withContext(Dispatchers.IO) { api.bannerExists(repo, account, bannerPath) }

    /** 聚合的账号表（含最新推文头像文件名 av）。用 timeline-recent，比全站索引小得多 */
    suspend fun getRecentTimelineAccounts(): List<IndexAccount> =
        withContext(Dispatchers.IO) { api.fetchRecentTimeline().first }

    suspend fun snapshotFileExists(repo: String, account: String, relPath: String): Boolean =
        withContext(Dispatchers.IO) { api.snapshotFileExists(repo, account, relPath) }

    suspend fun verifyToken(pat: String): AuthUser? =
        withContext(Dispatchers.IO) { api.verifyToken(pat) }

    // 管理操作
    suspend fun cancelRun(pat: String, repo: String, runId: Long) = api.cancelRun(pat, repo, runId)
    suspend fun rerunRun(pat: String, repo: String, runId: Long) = api.rerunRun(pat, repo, runId)
    suspend fun fetchFileContent(pat: String, repo: String, path: String) = api.fetchFileContent(pat, repo, path)
    suspend fun putFileContent(pat: String, repo: String, path: String, content: String, sha: String, message: String) =
        api.putFileContent(pat, repo, path, content, sha, message)
    suspend fun putFileContentRaw(pat: String, repo: String, path: String, base64: String, sha: String, message: String) =
        api.putFileContentRaw(pat, repo, path, base64, sha, message)
    suspend fun generateRepo(pat: String, name: String) = api.generateRepo(pat, name)
    suspend fun dispatchWorkflow(pat: String, repo: String, workflow: String, inputs: Map<String,String> = emptyMap()) =
        api.dispatchWorkflow(pat, repo, workflow, inputs)

    suspend fun fetchWorkflowRuns(pat: String, repo: String) =
        api.fetchWorkflowRuns(pat, repo)

    /**
     * 修复"最新推文头像"缺失：把 profile.json 指向的主头像复制一份，
     * 改名成最新推文引用的头像文件名（avatar_<pid>.jpg 之类）。
     *
     * 背景：主头像 avatar.jpg 基本都抓得到，但最新推文引用的按 pid 命名的
     * 那个经常没抓到，导致 Feed / Reader 里最新推文头像破图。同一个人的头像，
     * 用主头像顶上即可（可能不是同一版本，但远好过破图）。
     *
     * 不改任何 json：index.json 里已经写着那个文件名，我们只是把文件补上。
     * 也不会被 archive.py 的 dedup 误删——去重按文件名里的 pid 分组，
     * 主头像与 avatar_<pid> 不同组；孤儿清理只删没被 HTML 引用的，而它正被引用。
     *
     * 返回 Result<String>：成功时是提示文案，失败时带原因。
     */
    suspend fun fixLatestAvatar(
        pat: String, repo: String, account: String, knownTarget: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val base = "accounts/$account/wayback_snapshots"

                val targetName = knownTarget?.trim()?.takeIf { it.isNotBlank() } ?: run {
                    val accts = try { api.fetchRecentTimeline().first } catch (e: Exception) { emptyList() }
                    accts.firstOrNull { it.r == repo }?.av?.trim().orEmpty()
                }
                if (targetName.isBlank())
                    return@withContext Result.failure(Exception("聚合数据里没有该账号的头像文件名"))

                val srcName = (
                    try { getRepos().firstOrNull { it.repoName == repo }?.avatar } catch (e: Exception) { null }
                ).orEmpty().substringAfterLast('/').trim().ifBlank { "avatar.jpg" }

                if (srcName == targetName)
                    return@withContext Result.success("最新推文用的就是主头像，无需修复")

                // 3. 已存在则跳过，不产生无谓 commit
                val targetPath = "$base/avatar/$targetName"
                if (api.getFileSha(pat, repo, targetPath) != null)
                    return@withContext Result.success("头像已存在，无需修复：$targetName")

                // 4. 读源文件原始 base64，不解码以免二进制损坏
                val srcPath = "$base/avatar/$srcName"
                val (b64, _) = api.fetchFileBase64(pat, repo, srcPath)
                    ?: return@withContext Result.failure(Exception("读不到主头像 $srcName"))

                // 5. 以目标名新建
                api.putNewFile(pat, repo, targetPath, b64,
                    "补最新推文头像 $targetName [skip ci]")
                    .map { "已复制 $srcName → $targetName" }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // 申请存档
    suspend fun submitRequest(token: String, account: String, note: String) =
        withContext(Dispatchers.IO) {
            api.createArchiveRequest(token, account, note)
        }

    suspend fun closeIssue(pat: String, number: Int) = api.closeIssue(pat, number)
    suspend fun getRequests(pat: String): List<GitHubIssue> =
        withContext(Dispatchers.IO) { api.fetchArchiveRequests(pat) }
}
