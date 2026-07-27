package io.github.twitterarchiver.viewmodel

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
import kotlinx.coroutines.launch
import androidx.core.content.edit

/** 4 个仪表盘之一 */
enum class DashRepo(val repo: String, val title: String) {
    HOME("home", "Home 聚合"),
    DISPATCHER("Dispatcher", "调度中心"),
    STARTER("project-starter", "存档模板"),
    ALL_ARCHIVES("", "所有存档")   // 特殊：所有账号仓库
}

data class AdminState(
    val pat: String? = null,
    val hasPat: Boolean = false,
    val message: String? = null,
    // 各仪表盘的运行状态缓存
    val runsByRepo: Map<String, List<WorkflowRun>> = emptyMap(),
    val loadingRepo: String? = null,
    // 所有存档仓库列表
    val allArchives: List<ArchiveRepo> = emptyList(),
    val archivesLoading: Boolean = false,
    val pinnedRepos: List<String> = emptyList(),      // 最近操作的仓库（置顶）
    val repoStatus: Map<String, String> = emptyMap(), // repo -> running/success/failure
    // 申请
    val requests: List<GitHubIssue> = emptyList(),
    val requestsLoading: Boolean = false,
    // 完整性检测（缺 banner / 缺置顶）
    val checking: Boolean = false,
    val checkProgress: Int = 0,
    val checkTotal: Int = 0,
    val missingBanner: List<MissingItem> = emptyList(),
    val missingPinned: List<MissingItem> = emptyList(),
    val missingAvatar: List<MissingItem> = emptyList(),
    val newlyCreated: List<String> = emptyList(),   // 新建记录（批准/手动新建的仓库，存本地）
    val checkDone: Boolean = false,
    val hasCheckedOnce: Boolean = false,   // 是否检测过（区分"无缓存"和"检测完无缺失"）
    val busy: Boolean = false
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
    fun savePat(pat: String) {
        viewModelScope.launch {
            store.savePat(pat)
            _state.value = _state.value.copy(pat = pat, hasPat = pat.isNotBlank(), message = "已保存令牌")
        }
    }
    fun clearPat() {
        viewModelScope.launch {
            store.clearPat()
            _state.value = _state.value.copy(pat = null, hasPat = false, message = "已清除令牌")
        }
    }

    // ---------- 工作流运行状态 ----------
    fun loadRuns(repoName: String) {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingRepo = repoName)
            repo.fetchWorkflowRuns(pat, repoName)
                .onSuccess { runs ->
                    _state.value = _state.value.copy(
                        runsByRepo = _state.value.runsByRepo + (repoName to runs),
                        loadingRepo = null
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(loadingRepo = null,
                        message = "加载失败：${it.message}")
                }
        }
    }

    /** 加载所有存档账号（用 repos.json，含小号，共 141 个账号） */
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

            // 最新推文引用的头像文件名，取自聚合数据（timeline-recent 只有几百 KB，
            // 逐个读各仓库 index.json 动辄十几 MB，不现实）
            val latestAvatar: Map<String, String> = try {
                repo.getRecentTimelineAccounts().associate { it.r to it.av }
            } catch (e: Exception) { emptyMap() }

            val noBanner = java.util.Collections.synchronizedList(mutableListOf<MissingItem>())
            val noPinned = java.util.Collections.synchronizedList(mutableListOf<MissingItem>())
            val noAvatar = java.util.Collections.synchronizedList(mutableListOf<MissingItem>())
            var done = 0

            kotlinx.coroutines.coroutineScope {
                archives.chunked(8).forEach { batch ->
                    batch.map { arc ->
                        async {
                            try {
                                val prof = repo.getProfile(arc.repoName, arc.account)
                                val item = MissingItem(arc.repoName, arc.account, arc.displayName)
                                // 用已读的 profile.banner 判断，避免重复读 profile.json
                                val hasBanner = repo.bannerExists(arc.repoName, arc.account, prof.banner)
                                if (!hasBanner) noBanner.add(item)
                                if (prof.pinned.isBlank()) noPinned.add(item)
                                // 最新推文头像：文件名与主头像不同且文件不存在才算缺
                                val av = latestAvatar[arc.repoName].orEmpty()
                                if (av.isNotBlank() && av != prof.avatar.substringAfterLast('/') &&
                                    !repo.snapshotFileExists(arc.repoName, arc.account, "avatar/$av")
                                ) noAvatar.add(item.copy(avatarName = av))
                            } catch (e: Exception) {
                                noBanner.add(MissingItem(arc.repoName, arc.account, "${arc.displayName} [读取失败]"))
                            }
                        }
                    }.forEach { it.await(); done++
                        // 动态更新：每检测完一个就刷新进度和已发现的缺失（有就显示，不等全部完成）
                        _state.value = _state.value.copy(
                            checkProgress = done,
                            missingBanner = noBanner.sortedBy { m -> m.displayName },
                            missingPinned = noPinned.sortedBy { m -> m.displayName },
                            missingAvatar = noAvatar.sortedBy { m -> m.displayName })
                    }
                }
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
        _state.value = _state.value.copy(newlyCreated = cur)
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
                    kotlinx.coroutines.delay(6000)
                    repo.dispatchWorkflow(pat, account, "setup.yml", mapOf("since" to ""))
                    repo.closeIssue(pat, number, "已建档：$account，感谢申请！")
                    addNewlyCreated(account)   // 加入新建记录
                    _state.value = _state.value.copy(busy = false, message = "已批准并建档：$account")
                    loadRequests()
                }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "建档失败：${it.message}") }
        }
    }

    /** 拒绝申请：关闭 Issue */
    fun rejectRequest(number: Int, reason: String) {
        val pat = _state.value.pat ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repo.closeIssue(pat, number, reason.ifBlank { "抱歉，此申请未通过。" })
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
                    _state.value = _state.value.copy(message = "仓库已创建，等待初始化…")
                    // 模板仓库生成后，workflow 需要几秒才可用，稍等再触发 setup
                    kotlinx.coroutines.delay(6000)
                    repo.dispatchWorkflow(pat, name, "setup.yml", mapOf("since" to since))
                        .onSuccess {
                            addNewlyCreated(name)   // 加入新建记录
                            _state.value = _state.value.copy(busy = false, message = "已触发建档：$name")
                        }
                        .onFailure { _state.value = _state.value.copy(busy = false,
                            message = "仓库已建，但触发 setup 失败：${it.message}（可稍后手动触发）") }
                }
                .onFailure { _state.value = _state.value.copy(busy = false,
                    message = "创建仓库失败：${it.message}") }
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
