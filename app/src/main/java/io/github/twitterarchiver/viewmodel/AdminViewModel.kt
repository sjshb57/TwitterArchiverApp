package io.github.twitterarchiver.viewmodel

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.ArchiveRepo
import io.github.twitterarchiver.data.GitHubIssue
import io.github.twitterarchiver.data.HealthSort
import io.github.twitterarchiver.data.RepoHealth
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.data.RotationConfig
import io.github.twitterarchiver.data.SecureStore
import io.github.twitterarchiver.data.WorkflowRun
import io.github.twitterarchiver.util.DateUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.milliseconds

/** 4 个仪表盘之一 */
enum class DashRepo(val repo: String, @androidx.annotation.StringRes val titleRes: Int) {
    HOME("home", R.string.dash_home),
    DISPATCHER("Dispatcher", R.string.dash_dispatcher),
    HEALTH("", R.string.dash_health),
    STARTER("project-starter", R.string.dash_starter),
    ALL_ARCHIVES("", R.string.dash_all_archives)
}

data class AdminState(
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

data class PendingSetup(
    val repo: String,
    val since: String,
    val createdAt: Long
)

private fun List<MissingItem>.uniqueSorted(): List<MissingItem> =
    distinctBy { it.repo to it.account }.sortedBy { it.displayName }

data class MissingItem(
    val repo: String,
    val account: String,
    val displayName: String,
    val avatarName: String = ""
)

class AdminViewModel(app: Application) : AndroidViewModel(app) {

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any) =
        if (args.isEmpty()) getApplication<Application>().getString(id)
        else getApplication<Application>().getString(id, *args)

    private val repo = Repository.shared

    @Volatile
    private var patValue: String? = null
    private val store = SecureStore(app)

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state.asStateFlow()

    fun clearMessage() { _state.update { it.copy(message = null) } }

    fun checkPat() {
        viewModelScope.launch {
            val pat = store.getPat()
            patValue = pat
            _state.update { it.copy(hasPat = !pat.isNullOrBlank()) }
        }
    }
    fun savePat(pat: String) {
        viewModelScope.launch {
            _state.update { it.copy(patVerifying = true, message = null) }
            val user = repo.verifyToken(pat).getOrElse { err ->
                _state.update { it.copy(patVerifying = false, message = err.message ?: str(R.string.admin_verify_failed)) }
                return@launch
            }
            store.savePat(pat)
            patValue = pat
            _state.update { it.copy(
                patVerifying = false,
                hasPat = pat.isNotBlank(),
                message = str(R.string.admin_token_saved, user.login)
            ) }
        }
    }
    fun clearPat() {
        viewModelScope.launch {
            store.clearPat()
            patValue = null
            _state.update { it.copy(hasPat = false, message = str(R.string.admin_token_cleared)) }
        }
    }

    fun loadRuns(repoName: String, silent: Boolean = false) {
        val pat = patValue ?: return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loadingRepo = repoName) }
            repo.fetchWorkflowRuns(pat, repoName)
                .onSuccess { runs ->
                    _state.update { it.copy(
                        runsByRepo = it.runsByRepo + (repoName to runs),
                        loadingRepo = null
                    ) }
                }
                .onFailure { e ->
                    _state.update { st ->
                        if (silent) st.copy(loadingRepo = null)
                        else st.copy(loadingRepo = null,
                            message = str(R.string.admin_load_failed, e.message.orEmpty()))
                    }
                }
        }
    }

    suspend fun checkRepoExists(name: String): Boolean? {
        val pat = patValue ?: return null
        return repo.repoExists(pat, name)
    }

    fun refreshNewlyCreatedStatus() {
        val pat = patValue ?: return
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
            _state.update { it.copy(repoStatus = it.repoStatus + result) }
        }
    }

    private val nonArchiveRepos = setOf("home", "Dispatcher", ".github", "project-starter")

    fun setHealthSort(sort: HealthSort) {
        _state.update { it.copy(healthSort = sort) }
    }

    fun loadHealth() {
        val pat = patValue ?: return
        if (_state.value.healthLoading) return
        viewModelScope.launch {
            _state.update { it.copy(healthLoading = true, healthError = null) }
            val repos = repo.fetchOrgRepos(pat).getOrElse {
                _state.update { it.copy(
                    healthLoading = false,
                    healthError = str(R.string.admin_repo_list_failed, it.message.orEmpty())) }
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

            _state.update { it.copy(
                healthLoading = false,
                health = list,
                rotation = rotation,
                healthError = if (rotation == null) str(R.string.admin_rotation_unreadable) else null
            ) }
        }
    }

    fun refreshRunningStatus() = refreshStatus(onlyRunning = true)
    fun refreshAllStatus() = refreshStatus(onlyRunning = false)

    private fun refreshStatus(onlyRunning: Boolean) {
        val pat = patValue ?: return
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
            _state.update { it.copy(repoStatus = it.repoStatus + result) }
        }
    }

    fun loadAllArchives() {
        viewModelScope.launch {
            _state.update { it.copy(archivesLoading = true) }
            try {
                val repos = repo.getRepos()
                _state.update { it.copy(allArchives = repos, archivesLoading = false) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(archivesLoading = false,
                    message = str(R.string.admin_accounts_failed, e.message.orEmpty())) }
            }
        }
    }

    private var integrityJob: Job? = null

    fun runIntegrityCheck() {
        if (integrityJob?.isActive == true) return
        integrityJob = viewModelScope.launch {
            val archives = _state.value.allArchives.ifEmpty {
                try { repo.getRepos() } catch (e: Exception) { emptyList() }
            }
            if (archives.isEmpty()) {
                _state.update { it.copy(message = str(R.string.admin_no_accounts)) }
                return@launch
            }
            _state.update { it.copy(
                checking = true, checkDone = false, checkProgress = 0,
                checkTotal = archives.size, missingBanner = emptyList(),
                missingPinned = emptyList(), missingAvatar = emptyList()) }

            val latestAvatar: Map<String, String> = try {
                repo.getRecentTimelineAccounts().associate { "${it.r}/${it.a}" to it.av }
            } catch (e: Exception) { emptyMap() }

            val noBanner = java.util.concurrent.CopyOnWriteArrayList<MissingItem>()
            val noPinned = java.util.concurrent.CopyOnWriteArrayList<MissingItem>()
            val noAvatar = java.util.concurrent.CopyOnWriteArrayList<MissingItem>()
            var done = 0

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

                                val bannerJob = async { repo.bannerExists(arc.repoName, arc.account, prof.banner) }
                                val avatarJob = async {
                                    needAvatarCheck &&
                                        !repo.snapshotFileExists(arc.repoName, arc.account, "avatar/$av")
                                }
                                if (!bannerJob.await()) noBanner.add(item)
                                if (avatarJob.await()) noAvatar.add(item.copy(avatarName = av))
                            } catch (e: Exception) {
                                currentCoroutineContext().ensureActive()
                                noBanner.add(MissingItem(arc.repoName, arc.account, str(R.string.admin_read_failed_suffix, arc.displayName)))
                            }
                        }
                        lock.withLock {
                            done++
                            _state.update { it.copy(
                                checkProgress = done,
                                missingBanner = noBanner.uniqueSorted(),
                                missingPinned = noPinned.uniqueSorted(),
                                missingAvatar = noAvatar.uniqueSorted()) }
                        }
                    }
                }.awaitAll()
            }
            val mb = noBanner.uniqueSorted()
            val mp = noPinned.uniqueSorted()
            val ma = noAvatar.uniqueSorted()
            _state.update { it.copy(
                checking = false, checkDone = true, hasCheckedOnce = true,
                missingBanner = mb, missingPinned = mp, missingAvatar = ma) }
            saveMissingCache(mb, mp, ma)
        }
    }

    fun addNewlyCreated(repoName: String) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val cur = (prefs.getString("newly_created", "") ?: "").split("\n").filter { it.isNotBlank() }
        if (repoName in cur) return
        val updated = listOf(repoName) + cur
        prefs.edit { putString("newly_created", updated.joinToString("\n")) }
        _state.update { it.copy(newlyCreated = updated) }
    }

    fun removeNewlyCreated(repoName: String) {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val cur = (prefs.getString("newly_created", "") ?: "").split("\n")
            .filter { it.isNotBlank() && it != repoName }
        prefs.edit { putString("newly_created", cur.joinToString("\n")) }
        _state.update { it.copy(newlyCreated = cur) }
    }

    fun loadNewlyCreated() {
        val prefs = getApplication<Application>()
            .getSharedPreferences("integrity_check", android.content.Context.MODE_PRIVATE)
        val cur = (prefs.getString("newly_created", "") ?: "").split("\n").filter { it.isNotBlank() }
        _state.update { it.copy(newlyCreated = cur, pendingSetup = readPendingSetup()) }
    }

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
        _state.update { it.copy(pendingSetup = map) }
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
            }.distinctBy { it.repo to it.account }
        }
        _state.update { it.copy(
            missingBanner = parse("missing_banner"),
            missingPinned = parse("missing_pinned"),
            missingAvatar = parse("missing_avatar"),
            hasCheckedOnce = true) }
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

    fun markBannerDone(repoName: String) {
        val mb = _state.value.missingBanner.filterNot { it.repo == repoName }
        _state.update { it.copy(missingBanner = mb) }
        saveMissingCache(mb, _state.value.missingPinned, _state.value.missingAvatar)
    }

    fun clearCheck() {
        _state.update { it.copy(checkDone = false) }
    }

    fun cancelRun(repoName: String, runId: Long) = op(str(R.string.admin_pause_requested)) {
        repo.cancelRun(it, repoName, runId)
    }
    fun rerunRun(repoName: String, runId: Long) = op(str(R.string.admin_retry_requested)) {
        repo.rerunRun(it, repoName, runId)
    }
    fun triggerWorkflow(repoName: String, workflow: String, inputs: Map<String, String> = emptyMap()) {
        pinRepo(repoName)
        op(str(R.string.admin_workflow_triggered, workflow)) { repo.dispatchWorkflow(it, repoName, workflow, inputs) }
    }
    fun pinRepo(repoName: String) {
        val newPinned = listOf(repoName) + _state.value.pinnedRepos.filter { it != repoName }
        _state.update { it.copy(
            pinnedRepos = newPinned.take(20),
            repoStatus = it.repoStatus + (repoName to "running")
        ) }
    }

    private fun op(successMsg: String, action: suspend (String) -> Result<Unit>) {
        val pat = patValue ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            action(pat)
                .onSuccess { _state.update { it.copy(busy = false, message = successMsg) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = str(R.string.admin_action_failed, e.message.orEmpty())
                        )
                    }
                }
        }
    }

    fun loadRequests() {
        val pat = patValue ?: return
        viewModelScope.launch {
            _state.update { it.copy(requestsLoading = true) }
            try {
                val reqs = repo.getRequests(pat)
                _state.update { it.copy(requests = reqs, requestsLoading = false) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(requestsLoading = false, message = str(R.string.admin_requests_failed, e.message.orEmpty())) }
            }
        }
    }

    fun approveRequest(number: Int, rawAccount: String) {
        val pat = patValue ?: return
        val account = io.github.twitterarchiver.util.AccountUtil.normalize(rawAccount)
        if (account.isBlank()) {
            _state.update { it.copy(message = str(R.string.admin_account_empty)) }
            return
        }
        pinRepo(account)
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (repo.repoExists(pat, account) == true) {
                _state.update {
                    it.copy(
                        busy = false, message = str(R.string.admin_approve_exists, account)
                    )
                }
                return@launch
            }
            repo.generateRepo(pat, account)
                .onSuccess {
                    addNewlyCreated(account)
                    markPendingSetup(account, "")
                    val ok = dispatchSetup(pat, account, "", fromUser = true)
                    if (ok) {
                        repo.closeIssue(pat, number)
                        _state.update { it.copy(
                            busy = false, message = str(R.string.admin_approved, account)) }
                    }
                    loadRequests()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = str(R.string.admin_create_failed, e.message.orEmpty())
                        )
                    }
                }
        }
    }

    fun rejectRequest(number: Int) {
        val pat = patValue ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            repo.closeIssue(pat, number)
                .onSuccess { _state.update { it.copy(busy = false, message = str(R.string.admin_request_rejected)) }; loadRequests() }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = str(R.string.admin_action_failed, e.message.orEmpty())
                        )
                    }
                }
        }
    }

    fun createArchive(rawName: String, since: String) {
        val pat = patValue ?: return
        val name = io.github.twitterarchiver.util.AccountUtil.normalize(rawName)
        if (name.isBlank()) {
            _state.update { it.copy(message = str(R.string.admin_account_empty)) }
            return
        }
        pinRepo(name)  // 新建的置顶
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = str(R.string.admin_creating_repo)) }
            repo.generateRepo(pat, name)
                .onSuccess {
                    addNewlyCreated(name)
                    markPendingSetup(name, since)
                    _state.update { it.copy(message = str(R.string.admin_repo_created)) }
                    dispatchSetup(pat, name, since, fromUser = true)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = str(R.string.admin_create_repo_failed, e.message.orEmpty())
                        )
                    }
                }
        }
    }

    private suspend fun awaitSetupReady(pat: String, name: String): Boolean {
        repeat(27) {
            if (repo.workflowExists(pat, name, "setup.yml")) return true
            kotlinx.coroutines.delay(1500.milliseconds)
        }
        return false
    }

    private suspend fun dispatchSetup(
        pat: String, name: String, since: String, fromUser: Boolean
    ): Boolean {
        if (!awaitSetupReady(pat, name)) {
            _state.update { it.copy(busy = false,
                message = str(R.string.admin_workflow_not_ready, name)) }
            return false
        }
        var ok = false
        repo.dispatchWorkflow(pat, name, "setup.yml", mapOf("since" to since))
            .onSuccess {
                ok = true
                clearPendingSetup(name)
                _state.update { it.copy(busy = false, message = str(R.string.admin_setup_triggered, name)) }
            }
            .onFailure { e ->
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (fromUser) str(
                            R.string.admin_setup_failed,
                            name,
                            e.message.orEmpty()
                        )
                        else str(R.string.admin_retry_setup_failed, name, e.message.orEmpty())
                    )
                }
            }
        return ok
    }

    fun resumePendingSetups() {
        val pat = patValue ?: return
        val pending = _state.value.pendingSetup
        if (pending.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            for ((name, item) in pending) {
                if (now - item.createdAt < 120_000) continue
                val hasRun = repo.fetchWorkflowRuns(pat, name).getOrNull()
                    ?.any { !(it.name ?: "").contains("pages", ignoreCase = true) } ?: false
                if (hasRun) { clearPendingSetup(name); continue }
                dispatchSetup(pat, name, item.since, fromUser = false)
            }
        }
    }

    suspend fun readFile(repoName: String, path: String): Result<Pair<String, String>> {
        val pat = patValue ?: return Result.failure(Exception(str(R.string.admin_no_token)))
        return repo.fetchFileContent(pat, repoName, path)
    }
    /** 读取 profile.json */
    suspend fun readProfile(repoName: String, account: String): Result<Pair<String, String>> {
        val pat = patValue ?: return Result.failure(Exception(str(R.string.admin_no_token)))
        return repo.fetchFileContent(pat, repoName, "accounts/$account/wayback_snapshots/profile.json")
    }
    /** 写 profile.json */
    fun writeProfile(repoName: String, account: String, content: String, sha: String) =
        op(str(R.string.admin_profile_updated)) {
            repo.putFileContent(it, repoName, "accounts/$account/wayback_snapshots/profile.json",
                content, sha, str(R.string.admin_profile_commit, account))
        }
    /** 上传 banner（图片 base64） */
    fun uploadBanner(repoName: String, account: String, base64NoWrap: String, sha: String) =
        op(str(R.string.admin_banner_uploaded)) {
            val r = repo.putFileContentRaw(it, repoName,
                "accounts/$account/wayback_snapshots/avatar/1500x500.jpg",
                base64NoWrap, sha, str(R.string.admin_banner_commit, account))
            r.onSuccess { markBannerDone(repoName) }
            r
        }

    /**
     * 修复最新推文头像：把 profile.json 指向的主头像复制成最新推文引用的文件名。
     * 提示文案随结果变化，故不复用 op() 的固定文案。
     */
    fun fixLatestAvatar(repoName: String, account: String, knownTarget: String? = null) {
        val pat = patValue ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            repo.fixLatestAvatar(pat, repoName, account, knownTarget)
                .onSuccess { msg ->
                    _state.update { st -> st.copy(
                        busy = false, message = msg,
                        missingAvatar = st.missingAvatar.filterNot { m -> m.repo == repoName }) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = str(R.string.admin_fix_failed, e.message.orEmpty())
                        )
                    }
                }
        }
    }

    fun writeFile(repoName: String, path: String, content: String, sha: String) =
        op(str(R.string.admin_file_uploaded, path)) { repo.putFileContent(it, repoName, path, content, sha, str(R.string.admin_file_commit, path)) }
}
