package io.github.twitterarchiver.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.twitterarchiver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** 数据仓库：统一入口，带简单内存缓存 */
class Repository(private val api: GitHubApi = GitHubApi.shared) {

    companion object {
        val shared: Repository by lazy { Repository() }
    }


    private val offline = OfflineIndexStore(api)
    private val diskJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false
    }
    private fun metaFile(name: String) =
        AppDirs.root?.let { java.io.File(it, "index_cache/_meta").apply { mkdirs() } }
            ?.let { java.io.File(it, name) }

    @Volatile
    private var reposCache: List<ArchiveRepo>? = null

    private class Lru<V>(private val max: Int) {
        private val map = object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>) = size > max
        }
        @Synchronized fun get(key: String): V? = map[key]
        @Synchronized fun put(key: String, value: V) { map[key] = value }
        @Synchronized fun clear() = map.clear()
    }

    private val tweetsCache = Lru<List<Tweet>>(4)
    private val profileCache = Lru<Profile>(64)

    fun clearMemoryCaches() {
        reposCache = null
        tweetsCache.clear()
        profileCache.clear()
    }

    /** 所有存档账号（排除非账号仓库） */
    suspend fun getRepos(forceRefresh: Boolean = false): List<ArchiveRepo> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) reposCache?.let { return@withContext it }
            val list = try {
                api.fetchRepos()
                    .filter { it.repoName.isNotBlank() }
                    .distinctBy { it.repoName to it.account }
                    .also { fresh ->
                    metaFile("repos.json")?.let { f ->
                        try { f.writeText(diskJson.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(ArchiveRepo.serializer()), fresh)) }
                        catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
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
        if (!forceRefresh) tweetsCache.get(key)?.let { return@withContext it }

        val deferred = inFlight.computeIfAbsent(key) { k ->
            scope.async {
                (offline.load(repo, account, forceRefresh) ?: api.fetchTweets(repo, account))
                    .distinctBy { it.tweetId }
            }.also { d ->
                d.invokeOnCompletion { inFlight.remove(k, d) }
            }
        }
        val list = deferred.await()
        tweetsCache.put(key, list)
        list
    }

    /** 进行中的账号加载。SupervisorJob：一个账号失败不牵连其它 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<List<Tweet>>>()

    suspend fun getProfile(repo: String, account: String, forceRefresh: Boolean = false): Profile =
        withContext(Dispatchers.IO) {
            val key = "$repo/$account"
            if (!forceRefresh) profileCache.get(key)?.let { return@withContext it }
            var cacheable = true
            val p = try {
                api.fetchProfile(repo, account).also { fresh ->
                    metaFile("profile_${repo}_$account.json")?.let { f ->
                        try { f.writeText(diskJson.encodeToString(Profile.serializer(), fresh)) }
                        catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                metaFile("profile_${repo}_$account.json")?.takeIf { it.isFile }?.let { f ->
                    try { diskJson.decodeFromString<Profile>(f.readText()) }
                    catch (e2: Exception) { null }
                } ?: Profile().also {
                    cacheable = false
                }
            }
            if (cacheable) profileCache.put(key, p)
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

    /** 组织下仓库名是否已被占用（直查 GitHub，不受 repos.json 聚合滞后影响） */
    suspend fun repoExists(pat: String, name: String): Boolean? =
        withContext(Dispatchers.IO) { api.repoExists(pat, name) }

    suspend fun verifyToken(pat: String): Result<AuthUser> =
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
    suspend fun workflowExists(pat: String, repo: String, workflow: String) =
        api.workflowExists(pat, repo, workflow)
    suspend fun dispatchWorkflow(pat: String, repo: String, workflow: String, inputs: Map<String,String> = emptyMap()) =
        api.dispatchWorkflow(pat, repo, workflow, inputs)
    suspend fun fetchOrgRepos(pat: String) = api.fetchOrgRepos(pat)
    suspend fun fetchRotationConfig(pat: String, repoCount: Int) =
        api.fetchRotationConfig(pat, repoCount)
    suspend fun fetchWorkflowRuns(pat: String, repo: String) =
        api.fetchWorkflowRuns(pat, repo)

    suspend fun fixLatestAvatar(
        pat: String, repo: String, account: String, knownTarget: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val base = "accounts/$account/wayback_snapshots"

                val targetName = knownTarget?.trim()?.takeIf { it.isNotBlank() } ?: run {
                    val accts = try { api.fetchRecentTimeline().first } catch (e: Exception) { emptyList() }
                    accts.firstOrNull { it.r == repo && it.a == account }?.av?.trim().orEmpty()
                }
                if (targetName.isBlank())
                    return@withContext Result.failure(Exception(AppStrings[R.string.avatar_no_filename]))

                val srcName = (
                    try { getRepos().firstOrNull { it.repoName == repo }?.avatar } catch (e: Exception) { null }
                ).orEmpty().substringAfterLast('/').trim().ifBlank { "avatar.jpg" }

                if (srcName == targetName)
                    return@withContext Result.success(AppStrings[R.string.avatar_same_as_main])

                val targetPath = "$base/avatar/$targetName"
                if (api.getFileSha(pat, repo, targetPath) != null)
                    return@withContext Result.success(AppStrings.get(R.string.avatar_exists, targetName))

                val srcPath = "$base/avatar/$srcName"
                val (b64, _) = api.fetchFileBase64(pat, repo, srcPath)
                    ?: return@withContext Result.failure(Exception(AppStrings.get(R.string.avatar_src_unreadable, srcName)))

                api.putNewFile(pat, repo, targetPath, b64,
                    AppStrings.get(R.string.avatar_commit_msg, targetName))
                    .map { AppStrings.get(R.string.avatar_copied, srcName, targetName) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Result.failure(e)
            }
        }

    suspend fun submitRequest(token: String, account: String, note: String) =
        withContext(Dispatchers.IO) {
            api.createArchiveRequest(token, account, note)
        }

    suspend fun closeIssue(pat: String, number: Int) = api.closeIssue(pat, number)
    suspend fun getRequests(pat: String): List<GitHubIssue> =
        withContext(Dispatchers.IO) { api.fetchArchiveRequests(pat) }
}
