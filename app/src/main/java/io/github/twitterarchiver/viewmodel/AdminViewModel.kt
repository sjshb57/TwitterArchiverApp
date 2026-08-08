package io.github.twitterarchiver.viewmodel

import io.github.twitterarchiver.data.HealthSort
import io.github.twitterarchiver.data.NetworkState
import io.github.twitterarchiver.data.RepoHealth
import io.github.twitterarchiver.data.RotationConfig
import io.github.twitterarchiver.util.DateUtil
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.GitHubIssue
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.SecureStore
import io.github.twitterarchiver.data.WorkflowRun
import io.github.twitterarchiver.data.ArchiveRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 4 个仪表盘之一 */
enum class DashRepo(val repo: String, val title: String) {
    HOME("home", "Home 聚合"),
    DISPATCHER("Dispatcher", "调度中心"),
    HEALTH("", "仓库健康"),
    STARTER("project-starter", "存档模板"),
    ALL_ARCHIVES("", "所有存档")
}

data class AdminState(
    val pat: String? = null,
    val hasPat: Boolean = false,
    val message: String? = null,
    val runsByRepo: Map<String, List<WorkflowRun>> = emptyMap(),
    val loadingRepo: String? = null,
    val allArchives: List<ArchiveRepo> = emptyList(),
    val archivesLoading: Boolean = false,
    val pinnedRepos: List<String> = emptyList(),
    val repoStatus: Map<String, String> = emptyMap(),
    val requests: List<GitHubIssue> = emptyList(),
    val requestsLoading: Boolean = false,
    val checking: Boolean = false,
    val checkProgress: Int = 0,
    val checkTotal: Int = 0,
    val missingBanner: List<MissingItem> = emptyList(),
    val missingPinned: List<MissingItem> = emptyList(),
    val missingAvatar: List<MissingItem> = emptyList(),
    val newlyCreated: List<String> = emptyList(),
    val pendingSetup: Map<String, PendingSetup> = emptyMap(),
    val checkDone: Boolean = false,
    val hasCheckedOnce: Boolean = false,
    val busy: Boolean = false,
    val patVerifying: Boolean = false,
    val health: List<RepoHealth> = emptyList(),
    val healthLoading: Boolean = false,
    val healthError: String? = null,
    val rotation: RotationConfig? = null,
    val healthSort: HealthSort = HealthSort.STALE
)

/** 仓库已创建、setup.yml 尚未确认触发成功的条目 */
data class PendingSetup(
    val repo: String,
    val since: String,
    val createdAt: Long
)

/** 待完善项：某仓库缺 banner / 置顶 / 最新推文头像 */
data class MissingItem(
    val repo: String,
    val account: String,
    val displayName: String,
    /** 缺失的头像文件名，仅头像项使用 */
    val avatarName: String = ""
)

class AdminViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository()
    private val store = SecureStore(app)

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    // ---------- PAT ----------
    fun checkPat() {
        viewModelScope.launch {
            val pat = store.getPat()
            _state.value = _state.value.copy(pat = pat, hasPat = !pat.isNullOrBlank())
        }
    }
    /**
     * 先验证再保存。不验证的话错误的 PAT 会被原样存下来，
     * 直到第一次触发工作流才报错，排查起来绕。
     */
    fun savePat(pat: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(patVerifying = true, message = null)
            val user = repo.verifyToken(pat)
            if (user == null) {
                _state.value = _state.value.copy(
                    patVerifying = false,
                    message = if (!NetworkState.online) "当前离线，无法验证令牌，请联网后重试"
                    else "令牌无效或权限不足，请确认已勾选 repo 与 workflow"
                )
                return@launch
            }
            store.savePat(pat)
            _state.value = _state.value.copy(
                patVerifying = false,
                pat = pat,
                hasPat = pat.isNotBlank(),
                message = "已保存令牌（${user.login}）"
            )
        }
    }
    fun clearPat() {
        viewModelScope.launch {
            store.clearPat()
            _state.value = _state.value.copy(pat = null, hasPat = false, message = "已清除令牌")
        }
    }

    // ---------- 工作流运行状态 ----------
    /** silent=true 用于后台轮询：不显示加载态，数据回来直接替换 */
    fun loadRuns(repoName: String, silent: Boolean = false) {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(loadingRepo = repoName)
            repo.fetchWorkflowRuns(pat, repoName)
                .onSuccess { runs ->
                    _state.value = _state.value.copy(
                        runsByRepo = _state.value.runsByRepo + (repoName to runs),
                        loadingRepo = null
                    )
                }
                .onFailure {
                    // 轮询失败不打扰：保留原有数据，不弹提示
                    _state.value = if (silent) _state.value.copy(loadingRepo = null)
                    else _state.value.copy(loadingRepo = null, message = "加载失败：${it.message}")
                }
        }
    }

    /** 加载所有存档账号（用 repos.json，含小号，共 141 个账号） */
    /** 仓库名是否已被占用。null=查不到（未登录/网络问题），此时不提示占用 */
    suspend fun checkRepoExists(name: String): Boolean? {
        val pat = _state.value.pat ?: return null
        return repo.repoExists(pat, name)
    }

    /**
     * 刷新"新建记录"的真实状态。repoStatus 原先只在建档瞬间写入内存，
     * 重启后即丢失，这里按各仓库最近一次运行结果重建。
     */
    fun refreshNewlyCreatedStatus() {
        val pat = _state.value.pat ?: return
        val names = _state.value.newlyCreated
        if (names.isEmpty()) return
        viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            val result = java.util.concurrent.ConcurrentHashMap<String, String>()
            kotlinx.coroutines.coroutineScope {
                names.map { name ->
                    async {
                        gate.withPermit {
                            repo.fetchWorkflowRuns(pat, name).onSuccess { runs ->
                                val own = runs.filterNot {
                                    (it.name ?: "").contains("pages", ignoreCase = true)
                                }
                                val justCreated = _state.value.pendingSetup[name]?.let {
                                    System.currentTimeMillis() - it.createdAt < 120_000
                                } ?: false
                                result[name] = when {
                                    // 刚建完的仓库，run 要几秒才出现在列表里，这段时间不算失败
                                    own.isEmpty() && justCreated -> "running"
                                    own.isEmpty() -> "unknown"
                                    own.any { it.status == "queued" || it.status == "in_progress" } -> "running"
                                    own.first().conclusion == "success" -> "success"
                                    else -> "failure"
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            _state.value = _state.value.copy(repoStatus = _state.value.repoStatus + result)
        }
    }

    /** 只刷新当前标记为运行中的仓库状态，供"所有存档"页轮询使用 */
    private val nonArchiveRepos = setOf("home", "Dispatcher", ".github", "project-starter")

    fun setHealthSort(sort: HealthSort) {
        _state.value = _state.value.copy(healthSort = sort)
    }

    fun loadHealth() {
        val pat = _state.value.pat ?: return
        if (_state.value.healthLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(healthLoading = true, healthError = null)
            val repos = repo.fetchOrgRepos(pat).getOrElse {
                _state.value = _state.value.copy(
                    healthLoading = false,
                    healthError = "读取仓库列表失败：${it.message}")
                return@launch
            }.filter { it.name !in nonArchiveRepos && !it.archived }

            val rotation = repo.fetchRotationConfig(pat, repos.size)
            val now = System.currentTimeMillis()
            val list = repos.map { r ->
                val days = r.pushedAt?.let {
                    val t = DateUtil.epochMillis(it)
                    if (t > 0) ((now - t) / 86_400_000L).toInt() else null
                }
                RepoHealth(
                    name = r.name,
                    sizeKb = r.size,
                    daysSincePush = days,
                    overdue = rotation != null && days != null && days > rotation.overdueDays,
                    private = r.private
                )
            }

            _state.value = _state.value.copy(
                healthLoading = false,
                health = list,
                rotation = rotation,
                healthError = if (rotation == null) "未能读取轮转配置，已跳过超期判定" else null
            )
        }
    }

    /** 自动轮询用：只查运行中的，避免 500 多个仓库把接口打爆 */
    fun refreshRunningStatus() = refreshStatus(onlyRunning = true)

    /** 手动刷新用：查所有已有状态标记的仓库 + 新建后尚未被 repos.json 收录的 */
    fun refreshAllStatus() = refreshStatus(onlyRunning = false)

    private fun refreshStatus(onlyRunning: Boolean) {
        val pat = _state.value.pat ?: return
        val st = _state.value
        val names = if (onlyRunning) {
            st.repoStatus.filterValues { it == "running" }.keys.toList()
        } else {
            (st.repoStatus.keys + st.newlyCreated).distinct()
        }
        if (names.isEmpty()) return
        viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            val result = java.util.concurrent.ConcurrentHashMap<String, String>()
            kotlinx.coroutines.coroutineScope {
                names.map { name ->
                    async {
                        gate.withPermit {
                            repo.fetchWorkflowRuns(pat, name).onSuccess { runs ->
                                val own = runs.filterNot {
                                    (it.name ?: "").contains("pages", ignoreCase = true)
                                }
                                if (own.isNotEmpty()) {
                                    result[name] = when {
                                        own.any { it.status == "queued" || it.status == "in_progress" } -> "running"
                                        own.first().conclusion == "success" -> "success"
                                        else -> "failure"
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            _state.value = _state.value.copy(repoStatus = _state.value.repoStatus + result)
        }
    }

    fun loadAllArchives() {
        viewModelScope.launch {
            _state.value = _state.value.copy(archivesLoading = true)
            try {
                val repos = repo.getRepos()
                _state.value = _state.value.copy(allArchives = repos, archivesLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(archivesLoading = false,
                    message = "加载账号列表失败：${e.message}")
            }
        }
    }

    /**
     * 完整性检测：并发读每个仓库的 profile.json，找出缺 banner 和缺置顶的仓库。
     * banner 判断：profile.banner 字段为空；置顶判断：profile.pinned 字段为空。
     */
    fun runIntegrityCheck() {
        viewModelScope.launch {
            val archives = _state.value.allArchives.ifEmpty {
                try { repo.getRepos() } catch (e: Exception) { emptyList() }
            }
            if (archives.isEmpty()) {
                _state.value = _state.value.copy(message = "没有账号可检测")
                return@launch
            }
            _state.value = _state.value.copy(
                checking = true, checkDone = false, checkProgress = 0,
                checkTotal = archives.size, missingBanner = emptyList(),
                missingPinned = emptyList(), missingAvatar = emptyList())

            val latestAvatar: Map<String, String> = try {
                repo.getRecentTimelineAccounts().associate { "${it.r}/${it.a}" to it.av }
            } catch (e: Exception) { emptyMap() }

            val noBanner = java.util.concurrent.CopyOnWriteArrayList<MissingItem>()
            val noPinned = java.util.concurrent.CopyOnWriteArrayList<MissingItem>()
            val noAvatar = java.util.concurrent.CopyOnWriteArrayList<MissingItem>()
            var done = 0

            // 信号量限流而非分批：分批时一批里最慢的会拖住整批（队头阻塞）
            val gate = kotlinx.coroutines.sync.Semaphore(16)
            val lock = kotlinx.coroutines.sync.Mutex()
            kotlinx.coroutines.coroutineScope {
                archives.map { arc ->
                    async {
                        gate.withPermit {
                            try {
                                val prof = repo.getProfile(arc.repoName, arc.account)
                                val item = MissingItem(arc.repoName, arc.account, arc.displayName)
                                if (prof.pinned.isBlank()) noPinned.add(item)

                                val av = latestAvatar["${arc.repoName}/${arc.account}"].orEmpty()
                                val listAv = arc.avatar.orEmpty().substringAfterLast('/')
                                    .ifBlank { "avatar.jpg" }
                                val needAvatarCheck = av.isNotBlank() && av != listAv

                                // 两个存在性检查互不依赖，并行发出
                                val bannerJob = async { repo.bannerExists(arc.repoName, arc.account, prof.banner) }
                                val avatarJob = async {
                                    needAvatarCheck &&
                                        !repo.snapshotFileExists(arc.repoName, arc.account, "avatar/$av")
                                }
                                if (!bannerJob.await()) noBanner.add(item)
                                if (avatarJob.await()) noAvatar.add(item.copy(avatarName = av))
                            } catch (e: Exception) {
                                noBanner.add(MissingItem(arc.repoName, arc.account, "${arc.displayName} [读取失败]"))
                            }
                        }
                        lock.withLock {
                            done++
                            _state.value = _state.value.copy(
                                checkProgress = done,
                                missingBanner = noBanner.sortedBy { m -> m.displayName },
                                missingPinned = noPinned.sortedBy { m -> m.displayName },
                                missingAvatar = noAvatar.sortedBy { m -> m.displayName })
                        }
                    }
                }.awaitAll()
            }
            val mb = noBanner.sortedBy { it.displayName }
            val mp = noPinned.sortedBy { it.displayName }
            val ma = noAvatar.sortedBy { it.displayName }
            _state.value = _state.value.copy(
                checking = false, checkDone = true, hasCheckedOnce = true,
                missingBanner = mb, missingPinned = mp, missingAvatar = ma)
            saveMissingCache(mb, mp, ma)
        }
    }

    /** 加入"新建记录"（批准/手动新建后调用，存本地）*/
    fun addNewlyCreated(repoName: String) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val cur = (prefs.getString("newly_created", "") ?: "").split("\n").filter { it.isNotBlank() }
        if (repoName in cur) return
        val updated = listOf(repoName) + cur
        prefs.edit { putString("newly_created", updated.joinToString("\n")) }
        _state.value = _state.value.copy(newlyCreated = updated)
    }

    /** 从"新建记录"移除（完成整个流程后手动移除）*/
    fun removeNewlyCreated(repoName: String) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val cur = (prefs.getString("newly_created", "") ?: "").split("\n")
            .filter { it.isNotBlank() && it != repoName }
        prefs.edit { putString("newly_created", cur.joinToString("\n")) }
        _state.value = _state.value.copy(newlyCreated = cur)
    }

    fun loadNewlyCreated() {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val cur = (prefs.getString("newly_created", "") ?: "").split("\n").filter { it.isNotBlank() }
        _state.value = _state.value.copy(newlyCreated = cur, pendingSetup = readPendingSetup())
    }

    // ---------- 建档中途中断的恢复记录 ----------

    private fun readPendingSetup(): Map<String, PendingSetup> {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        return (prefs.getString("pending_setup", "") ?: "").split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split("\t")
                if (p.size < 3) null
                else PendingSetup(p[0], p[1], p[2].toLongOrNull() ?: 0L)
            }
            .associateBy { it.repo }
    }

    private fun writePendingSetup(map: Map<String, PendingSetup>) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val text = map.values.joinToString("\n") { "${it.repo}\t${it.since}\t${it.createdAt}" }
        prefs.edit { putString("pending_setup", text) }
        _state.value = _state.value.copy(pendingSetup = map)
    }

    private fun markPendingSetup(name: String, since: String) {
        val cur = readPendingSetup().toMutableMap()
        cur[name] = PendingSetup(name, since, System.currentTimeMillis())
        writePendingSetup(cur)
    }

    private fun clearPendingSetup(name: String) {
        val cur = readPendingSetup().toMutableMap()
        if (cur.remove(name) != null) writePendingSetup(cur)
    }

    /** 从缓存加载待完善列表（进管理页时调用，秒开不检测） */
    fun loadMissingCache() {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val checked = prefs.getBoolean("checked_once", false)
        if (!checked) return
        fun parse(key: String): List<MissingItem> {
            val raw = prefs.getString(key, "") ?: ""
            if (raw.isBlank()) return emptyList()
            return raw.split("\n").mapNotNull { line ->
                val p = line.split("\t")
                if (p.size >= 3) MissingItem(p[0], p[1], p[2], p.getOrElse(3) { "" }) else null
            }
        }
        _state.value = _state.value.copy(
            missingBanner = parse("missing_banner"),
            missingPinned = parse("missing_pinned"),
            missingAvatar = parse("missing_avatar"),
            hasCheckedOnce = true)
    }

    private fun saveMissingCache(
        banner: List<MissingItem>, pinned: List<MissingItem>, avatar: List<MissingItem>
    ) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        fun ser(list: List<MissingItem>) =
            list.joinToString("\n") { "${it.repo}\t${it.account}\t${it.displayName}\t${it.avatarName}" }
        prefs.edit {
            putBoolean("checked_once", true)
                .putString("missing_banner", ser(banner))
                .putString("missing_pinned", ser(pinned))
                .putString("missing_avatar", ser(avatar))
        }
    }

    /** App 内传完 banner 后，从待完善列表移除该仓库（即时更新角标） */
    fun markBannerDone(repoName: String) {
        val mb = _state.value.missingBanner.filterNot { it.repo == repoName }
        _state.value = _state.value.copy(missingBanner = mb)
        saveMissingCache(mb, _state.value.missingPinned, _state.value.missingAvatar)
    }

    fun clearCheck() {
        _state.value = _state.value.copy(checkDone = false)
    }

    // ---------- 运行操作 ----------
    fun cancelRun(repoName: String, runId: Long) = op("已请求暂停") {
        repo.cancelRun(it, repoName, runId)
    }
    fun rerunRun(repoName: String, runId: Long) = op("已请求重试") {
        repo.rerunRun(it, repoName, runId)
    }
    fun triggerWorkflow(repoName: String, workflow: String, inputs: Map<String, String> = emptyMap()) {
        // 触发后把该仓库置顶（放到最前），并标记运行中
        pinRepo(repoName)
        op("已触发 $workflow") { repo.dispatchWorkflow(it, repoName, workflow, inputs) }
    }

    /** 把仓库置顶（最近操作的在最前） */
    fun pinRepo(repoName: String) {
        val newPinned = listOf(repoName) + _state.value.pinnedRepos.filter { it != repoName }
        _state.value = _state.value.copy(
            pinnedRepos = newPinned.take(20),
            repoStatus = _state.value.repoStatus + (repoName to "running")
        )
    }

    private fun op(successMsg: String, action: suspend (String) -> Result<Unit>) {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            action(pat)
                .onSuccess { _state.value = _state.value.copy(busy = false, message = successMsg) }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "操作失败：${it.message}") }
        }
    }

    // ---------- 申请处理 ----------
    fun loadRequests() {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(requestsLoading = true)
            try {
                val reqs = repo.getRequests(pat)
                _state.value = _state.value.copy(requests = reqs, requestsLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(requestsLoading = false, message = "加载申请失败：${e.message}")
            }
        }
    }

    /** 批准申请：建档 + 关闭 Issue */
    fun approveRequest(number: Int, rawAccount: String) {
        val pat = _state.value.pat ?: return
        // 兜底规范化：带 @ 或空白的账号名会让 generateRepo 直接 422
        val account = io.github.twitterarchiver.util.AccountUtil.normalize(rawAccount)
        if (account.isBlank()) {
            _state.value = _state.value.copy(message = "账号名为空，无法建档")
            return
        }
        pinRepo(account)
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.generateRepo(pat, account)
                .onSuccess {
                    kotlinx.coroutines.delay(6.seconds)
                    repo.dispatchWorkflow(pat, account, "setup.yml", mapOf("since" to ""))
                    repo.closeIssue(pat, number)
                    addNewlyCreated(account)   // 加入新建记录
                    _state.value = _state.value.copy(busy = false, message = "已批准并建档：$account")
                    loadRequests()
                }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "建档失败：${it.message}") }
        }
    }

    /** 拒绝申请：关闭 Issue */
    fun rejectRequest(number: Int) {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.closeIssue(pat, number)
                .onSuccess { _state.value = _state.value.copy(busy = false, message = "已拒绝申请"); loadRequests() }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "操作失败：${it.message}") }
        }
    }

    // ---------- 建档 ----------
    /** 完整建档：1.从模板创建仓库 2.触发 setup.yml */
    fun createArchive(rawName: String, since: String) {
        val pat = _state.value.pat ?: return
        val name = io.github.twitterarchiver.util.AccountUtil.normalize(rawName)
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "账号名为空，无法建档")
            return
        }
        pinRepo(name)  // 新建的置顶
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = "正在创建仓库…")
            repo.generateRepo(pat, name)
                .onSuccess {
                    // 仓库一旦存在就先落盘。晚于此处记录的话，进程在下面任何一步被系统
                    // 回收（切后台时很常见）都会导致仓库已建、App 里却查无此条。
                    addNewlyCreated(name)
                    markPendingSetup(name, since)
                    _state.value = _state.value.copy(message = "仓库已创建，等待工作流就绪…")
                    dispatchSetup(pat, name, since, fromUser = true)
                }
                .onFailure { _state.value = _state.value.copy(busy = false,
                    message = "创建仓库失败：${it.message}") }
        }
    }

    /** 等 setup.yml 在 Actions 里注册出来再触发，最多等 40 秒 */
    private suspend fun awaitSetupReady(pat: String, name: String): Boolean {
        repeat(27) {
            if (repo.workflowExists(pat, name, "setup.yml")) return true
            kotlinx.coroutines.delay(1500.milliseconds)
        }
        return false
    }

    private suspend fun dispatchSetup(pat: String, name: String, since: String, fromUser: Boolean) {
        if (!awaitSetupReady(pat, name)) {
            _state.value = _state.value.copy(busy = false,
                message = "「$name」仓库已建好，但模板工作流还没就绪。记录已保存，下次进入本页会自动重试。")
            return
        }
        repo.dispatchWorkflow(pat, name, "setup.yml", mapOf("since" to since))
            .onSuccess {
                clearPendingSetup(name)
                _state.value = _state.value.copy(busy = false, message = "已触发建档：$name")
            }
            .onFailure {
                _state.value = _state.value.copy(busy = false,
                    message = if (fromUser) "「$name」仓库已建好，但触发建档失败：${it.message}"
                              else "「$name」自动重试建档失败：${it.message}")
            }
    }

    /**
     * 对所有「仓库已建但 setup 没触发成功」的条目补一次触发。
     * 只在确认该仓库确实没有任何运行记录时才补，避免重复建档。
     */
    fun resumePendingSetups() {
        val pat = _state.value.pat ?: return
        val pending = _state.value.pendingSetup
        if (pending.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            for ((name, item) in pending) {
                // 刚建的仓库多半是 createArchive 自己还在跑（它要轮询工作流就绪，
                // 最长 40 秒）。这时插一脚会把 setup.yml 触发两次。
                if (now - item.createdAt < 120_000) continue
                val hasRun = repo.fetchWorkflowRuns(pat, name).getOrNull()
                    ?.any { !(it.name ?: "").contains("pages", ignoreCase = true) } ?: false
                if (hasRun) { clearPendingSetup(name); continue }
                dispatchSetup(pat, name, item.since, fromUser = false)
            }
        }
    }

    // ---------- 文件编辑 ----------
    suspend fun readFile(repoName: String, path: String): Result<Pair<String, String>> {
        val pat = _state.value.pat ?: return Result.failure(Exception("无令牌"))
        return repo.fetchFileContent(pat, repoName, path)
    }
    /** 读取 profile.json */
    suspend fun readProfile(repoName: String, account: String): Result<Pair<String, String>> {
        val pat = _state.value.pat ?: return Result.failure(Exception("无令牌"))
        return repo.fetchFileContent(pat, repoName, "accounts/$account/wayback_snapshots/profile.json")
    }
    /** 写 profile.json */
    fun writeProfile(repoName: String, account: String, content: String, sha: String) =
        op("已更新资料") {
            repo.putFileContent(it, repoName, "accounts/$account/wayback_snapshots/profile.json",
                content, sha, "更新 $account 资料 [skip ci]")
        }
    /** 上传 banner（图片 base64） */
    fun uploadBanner(repoName: String, account: String, base64NoWrap: String, sha: String) =
        op("已上传 Banner") {
            val r = repo.putFileContentRaw(it, repoName,
                "accounts/$account/wayback_snapshots/avatar/1500x500.jpg",
                base64NoWrap, sha, "更新 $account banner [skip ci]")
            markBannerDone(repoName)   // 传成功 → 从待完善移除，即时更新角标
            r
        }

    /**
     * 修复最新推文头像：把 profile.json 指向的主头像复制成最新推文引用的文件名。
     * 提示文案随结果变化，故不复用 op() 的固定文案。
     */
    fun fixLatestAvatar(repoName: String, account: String, knownTarget: String? = null) {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.fixLatestAvatar(pat, repoName, account, knownTarget)
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false, message = it,
                        missingAvatar = _state.value.missingAvatar.filterNot { m -> m.repo == repoName })
                }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "修复失败：${it.message}") }
        }
    }

    fun writeFile(repoName: String, path: String, content: String, sha: String) =
        op("已上传 $path") { repo.putFileContent(it, repoName, path, content, sha, "更新 $path [skip ci]") }
}
