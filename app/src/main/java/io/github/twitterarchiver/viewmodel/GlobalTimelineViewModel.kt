package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.GitHubApi
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.data.CrossReply
import io.github.twitterarchiver.data.GlobalIndexStore
import io.github.twitterarchiver.data.GlobalShard
import io.github.twitterarchiver.util.SearchUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.twitterarchiver.util.MediaUtil
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings
import kotlinx.coroutines.flow.update
import io.github.twitterarchiver.data.Repository

data class GlobalState(
    val loading: Boolean = true,
    val visible: List<GlobalPost> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false,
    val loadingFull: Boolean = false,  // 后台在加载分片
    val globalTotal: Int = 0,          // 全站推文总数（含尚未下载的月份）
    val shards: List<GlobalShard> = emptyList(),
    val downloadedMonths: Set<String> = emptySet(),
    /** 正在下载的月份 → 已完成比例 0..1 */
    val monthProgress: Map<String, Float> = emptyMap(),
    val indexError: String? = null,
    /** 已加载进内存的每日条数：yyyy-MM-dd → 数量 */
    val dayCounts: Map<String, Int> = emptyMap(),
    /** 当前日期筛选，yyyy-MM（整月）或 yyyy-MM-dd（某天） */
    val activeDate: String? = null,
    val filterAccounts: Set<Pair<String, String>> = emptySet(),
    val accounts: List<IndexAccount> = emptyList(),
    /** 当前搜索命中的总条数（0 表示没有在搜索） */
    val searchTotal: Int = 0,
    /** 搜索命中的推文所在月份尚未下载时，提示可以去下载它 */
    val searchMissingMonth: String? = null,
    /** 从搜索结果跳转的目标推文，列表滚动到位后清空 */
    val jumpTarget: String? = null,
    /** 正在逐月下载查找短码 */
    val fullSearchRunning: Boolean = false
)

private const val SEARCH_DEBOUNCE_MS = 200L

class GlobalTimelineViewModel(private val api: GitHubApi = GitHubApi.shared) : ViewModel() {

    private val _state = MutableStateFlow(GlobalState())
    val state: StateFlow<GlobalState> = _state.asStateFlow()

    private var allPosts: List<GlobalPost> = emptyList()
    private var filtered: List<GlobalPost> = emptyList()
    private var allAccounts: List<IndexAccount> = emptyList()
    private var currentFilters: Set<Pair<String, String>> = emptySet()
    private var crossReplies: Map<String, List<CrossReply>> = emptyMap()
    private val pageSize = 30
    private var page = 0
    private var recentLoaded = false
    private var fullLoaded = false
    private var meta: io.github.twitterarchiver.data.GlobalIndexMeta? = null
    private var recentPosts: List<GlobalPost> = emptyList()
    private val monthPosts = LinkedHashMap<String, List<GlobalPost>>()
    private var loadedMonths: Set<String> = emptySet()
    private val shardMonths: Set<String> get() = _state.value.shards.map { it.month }.toSet()
    private var activeDate: String? = null
    private val monthLock = kotlinx.coroutines.sync.Mutex()

    init { load() }

    /**
     * [force] 为 true 时忽略"已加载过"的短路，用于下拉刷新——
     * 不加这个参数的话下拉刷新是空操作：转圈几百毫秒然后收起，数据一条没变。
     */
    fun load(force: Boolean = false) {
        if (recentLoaded && !force) return
        viewModelScope.launch {
            _state.value = GlobalState(loading = true)
            try {
                val (accts, recent) = api.fetchRecentTimeline()
                val recentUnique = recent.distinctBy { it.tweetId }
                recentPosts = recentUnique
                allPosts = recentUnique
                filtered = recentUnique
                allAccounts = accts.distinctBy { it.r to it.a }
                recentLoaded = true
                page = 0
                emitPage()
                loadFull()
                loadCrossReplies()
            } catch (e: Exception) {
                loadFullAsInitial()
            }
        }
    }

    /** 只取分片清单；历史内容一律按需下载，冷启动不主动拉取任何整年。 */
    private fun loadFull() {
        if (fullLoaded) return
        viewModelScope.launch {
            _state.update { it.copy(loadingFull = true) }
            try {
                val m = api.fetchGlobalMeta()
                meta = m
                allAccounts = m.accounts.distinctBy { it.r to it.a }
                fullLoaded = true
                _state.update { it.copy(
                    loadingFull = false,
                    globalTotal = m.total,
                    shards = m.shards,
                    downloadedMonths = GlobalIndexStore.downloadedMonths(),
                    indexError = null
                ) }
                // 上次已下载到本地的月份直接合入，无需联网
                val local = GlobalIndexStore.downloadedMonths()
                m.shards.filter { it.month in local }
                    .forEach { loadShard(it, silent = true, rebuild = false) }
                monthLock.withLock { rebuildFromLoaded() }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(
                    loadingFull = false,
                    indexError = AppStrings.get(
                        R.string.index_manifest_failed,
                        "${e::class.java.simpleName} ${e.message.orEmpty()}".trim()
                    )
                ) }
            }
        }
    }

    /** 下载并合入某一年的全部月份 */
    fun downloadYear(year: String) {
        val m = meta ?: return
        viewModelScope.launch {
            // 全部月份就绪后只重建一次：逐月重建的话，一年要把几十万条重排 12 遍。
            for (shard in m.shardsOf(year).sortedByDescending { it.month })
                loadShard(shard, rebuild = false)
            monthLock.withLock { rebuildFromLoaded() }
        }
    }

    /** 下载并合入单个月份 */
    fun downloadMonth(month: String) {
        val shard = meta?.shards?.find { it.month == month } ?: return
        viewModelScope.launch { loadShard(shard) }
    }

    fun deleteMonth(month: String) {
        viewModelScope.launch {
            monthLock.withLock {
                withContext(Dispatchers.IO) { GlobalIndexStore.deleteMonth(month) }
                loadedMonths = loadedMonths - month
                monthPosts.remove(month)
                rebuildFromLoaded()
                _state.update { it.copy(downloadedMonths = GlobalIndexStore.downloadedMonths()) }
            }
        }
    }

    fun deleteYear(year: String) {
        viewModelScope.launch {
            monthLock.withLock {
                withContext(Dispatchers.IO) { GlobalIndexStore.deleteYear(year) }
                loadedMonths = loadedMonths.filterNot { it.startsWith(year) }.toSet()
                monthPosts.keys.filter { it.startsWith(year) }.forEach { monthPosts.remove(it) }
                rebuildFromLoaded()
                _state.update { it.copy(downloadedMonths = GlobalIndexStore.downloadedMonths()) }
            }
        }
    }

    private suspend fun loadShard(
        shard: GlobalShard,
        silent: Boolean = false,
        rebuild: Boolean = true
    ) = monthLock.withLock {
        if (shard.month in loadedMonths) return@withLock
        val m = meta ?: return@withLock
        if (!silent) _state.update { it.copy(
            monthProgress = it.monthProgress + (shard.month to 0f)) }
        try {
            var lastPct = -1
            val posts = api.fetchGlobalShard(shard, m.accounts) { done ->
                if (!silent && shard.bytes > 0) {
                    val p = (done.toFloat() / shard.bytes).coerceIn(0f, 1f)
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        _state.update { it.copy(
                            monthProgress = it.monthProgress + (shard.month to p)) }
                    }
                }
            }
            monthPosts[shard.month] = posts
            loadedMonths = loadedMonths + shard.month
            if (rebuild) rebuildFromLoaded()
            _state.update { it.copy(
                monthProgress = it.monthProgress - shard.month,
                downloadedMonths = GlobalIndexStore.downloadedMonths(),
                indexError = null
            ) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(
                monthProgress = it.monthProgress - shard.month,
                indexError = AppStrings.get(
                    R.string.shard_download_failed, shard.month,
                    "${e::class.java.simpleName} ${e.message.orEmpty()}".trim()
                )
            ) }
        }
    }

    /** 用「近期时间线 + 已加载的月份」重建总表 */
    private suspend fun rebuildFromLoaded() {
        val merged = withContext(Dispatchers.Default) {
            val seen = HashSet<String>()
            val out = ArrayList<GlobalPost>(recentPosts.size + monthPosts.values.sumOf { it.size })
            for (p in recentPosts) if (seen.add(p.tweetId)) out.add(p)
            for (month in monthPosts.keys.sortedDescending())
                for (p in monthPosts[month].orEmpty()) if (seen.add(p.tweetId)) out.add(p)
            // 两种时间格式并存，必须解析成时间戳再比，直接比字符串会把老格式全排到最前
            out.sortByDescending { it.epochMs }
            out
        }
        allPosts = merged
        val counts = withContext(Dispatchers.Default) {
            val m = HashMap<String, Int>()
            for (p in merged) {
                val d = p.displayDate
                if (d.isNotBlank()) m[d] = (m[d] ?: 0) + 1
            }
            m
        }
        _state.update { it.copy(dayCounts = counts) }
        if (_state.value.query.isBlank() && currentFilters.isEmpty() && activeDate == null) {
            filtered = merged
            emitPage()
        } else applyFilter(_state.value.query)
    }

    private fun loadCrossReplies() {
        viewModelScope.launch {
            try {
                crossReplies = api.fetchCrossReplies()
            } catch (e: Exception) { /* 忽略，回复功能降级 */ }
        }
    }

    // 账号 index.json 缓存（repo/account -> tweets），避免重复拉取。

    /**
     * 加载某主推文的完整对话：引用原推 + 回复链。
     * 回复链 = 主人自己的回复链（reader 逻辑）+ 别人的跨账号回复（cross-replies）。
     */
    suspend fun loadThread(post: GlobalPost): Pair<io.github.twitterarchiver.data.QuotedTweet?, List<io.github.twitterarchiver.data.ThreadItem>> {
        val repo = post.account.r
        val account = post.account.a
        val ownerUname = post.account.u.removePrefix("@").lowercase()

        // 1. 拉账号 index.json（缓存）
        val tweets = try {
            Repository.shared.getTweets(repo, account)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }

        val idIndex = tweets.associateBy { it.tweetId }
        fun avatarUrl(av: String) = if (av.isBlank()) "" else
            "${io.github.twitterarchiver.data.Config.snapshotsBase(repo, account)}/${MediaUtil.sanitizeRelPath(av)}"
        fun imgUrls(t: io.github.twitterarchiver.data.Tweet): List<String> {
            // 回复/推文的图在 images 字段（media_keys 为空但 images 有值的情况也要显示）
            if (t.images.isEmpty()) return emptyList()
            val base = io.github.twitterarchiver.data.Config.snapshotsBase(repo, account)
            return t.images.map { rel ->
                // 路径形如 ../image/xxx.jpg，取文件名再拼，避免 image/ 重复
                val name = rel.substringAfterLast('/')
                "$base/image/$name"
            }
        }
        fun toItem(t: io.github.twitterarchiver.data.Tweet, forceQuoted: Boolean = false): io.github.twitterarchiver.data.ThreadItem {
            val uname = t.authorUsername.removePrefix("@").lowercase()
            val isOwner = ownerUname.isNotBlank() && uname == ownerUname
            return io.github.twitterarchiver.data.ThreadItem(
                tweetId = t.tweetId,
                authorName = t.authorName,
                authorUsername = t.authorUsername,
                authorAvatarUrl = avatarUrl(t.authorAvatar),
                text = t.bodyText.ifBlank { t.text },
                images = imgUrls(t),
                time = t.timestamp,
                isOwner = isOwner,
                isQuoted = forceQuoted || (!isOwner && t.isVirtual)
            )
        }

        // 2. 引用原推：先试 quoted_id（quote 类型），再试 html embedded（RT 转推类型）
        val mainTweet = idIndex[post.tweetId]
        var quoted: io.github.twitterarchiver.data.QuotedTweet? = null
        if (mainTweet != null) {
            if (mainTweet.hasQuoted && mainTweet.quotedId.isNotBlank()) {
                // quote 类型：从 index.json 找被引用条目
                idIndex[mainTweet.quotedId]?.let { q ->
                    quoted = io.github.twitterarchiver.data.QuotedTweet(
                        authorName = q.authorName,
                        authorUsername = q.authorUsername,
                        authorAvatarUrl = avatarUrl(q.authorAvatar),
                        text = q.bodyText.ifBlank { q.text },
                        images = imgUrls(q)
                    )
                }
            }
            // RT 转推类型：正文以 RT @ 开头 或 有 embedded 内容，解析 html
            if (quoted == null && (mainTweet.text.startsWith("RT @") ||
                        mainTweet.bodyText.startsWith("RT @") ||
                        mainTweet.embeddedImages.isNotEmpty())) {
                quoted = try {
                    api.fetchEmbeddedTweet(repo, account, mainTweet.file, mainTweet.embeddedImages)
                } catch (e: Exception) { null }
            }
        }

        // 3. 主人自己的回复链（对齐网页版：replyMap 按 conversation_id 归组）
        val replyMap = HashMap<String, MutableList<io.github.twitterarchiver.data.Tweet>>()
        for (t in tweets) {
            if (t.isVirtual) continue
            if (t.isReply && t.conversationId.isNotBlank()) {
                replyMap.getOrPut(t.conversationId) { ArrayList() }.add(t)
            }
        }
        val ownerReplies = (replyMap[post.tweetId] ?: emptyList())
            .sortedBy { it.epochMs }
        val chain = ArrayList<io.github.twitterarchiver.data.ThreadItem>()
        val seen = HashSet<String>()
        seen.add(post.tweetId)
        for (rep in ownerReplies) {
            val parentId = rep.replyToId
            if (parentId.isNotBlank() && parentId != post.tweetId && !seen.contains(parentId)) {
                idIndex[parentId]?.let { parent ->
                    chain.add(toItem(parent)); seen.add(parentId)
                }
            }
            if (!seen.contains(rep.tweetId)) { chain.add(toItem(rep)); seen.add(rep.tweetId) }
        }

        // 4. 合并别人的跨账号回复（cross-replies），去重
        crossReplies[post.tweetId]?.forEach { cr ->
            if (!seen.contains(cr.tweetId)) {
                val acct = allAccounts.getOrNull(cr.acctIndex)
                chain.add(io.github.twitterarchiver.data.ThreadItem(
                    tweetId = cr.tweetId,
                    authorName = acct?.n ?: AppStrings[R.string.unknown_account],
                    authorUsername = acct?.u ?: "",
                    authorAvatarUrl = if (acct != null && acct.av.isNotBlank())
                        "${io.github.twitterarchiver.data.Config.snapshotsBase(acct.r, acct.a)}/avatar/${acct.av}" else "",
                    text = cr.text,
                    images = emptyList(),
                    time = cr.time,
                    isOwner = false,
                    isQuoted = false
                ))
                seen.add(cr.tweetId)
            }
        }

        // 按时间排序整个链
        val sorted = chain.sortedBy { it.epochMs }
        return quoted to sorted
    }

    /** timeline-recent 拿不到时的兜底：直接走分片清单 */
    private fun loadFullAsInitial() {
        viewModelScope.launch {
            recentLoaded = true
            _state.update { it.copy(loading = false) }
            loadFull()
        }
    }

    /**
     * 选日期。传 yyyy-MM 是整月，yyyy-MM-dd 是某天。
     * 所属月份还没下载就先下载，下完自动跳过去。
     */
    fun pickDate(date: String) {
        val month = date.take(7)
        val shard = meta?.shards?.find { it.month == month }
        if (month !in loadedMonths && shard != null) {
            viewModelScope.launch {
                loadShard(shard)
                activeDate = date
                applyFilter(_state.value.query)
            }
            return
        }
        activeDate = date
        applyFilter(_state.value.query)
    }

    fun clearDate() {
        activeDate = null
        applyFilter(_state.value.query)
    }

    fun clearIndexError() {
        _state.update { it.copy(indexError = null) }
    }

    fun search(q: String) = applyFilter(q)

    /** 多选筛选：传入选中的账号集合（空=全部） */
    fun filterByAccounts(accounts: Set<IndexAccount>) {
        currentFilters = accounts.map { it.r to it.a }.toSet()
        applyFilter(_state.value.query)
    }

    /** 应用账号筛选 + 搜索词 */
    private var searchJob: Job? = null
    /**
     * 上一次搜索的快照，用于增量收窄。
     *
     * 四个值必须一起读：分开存的话，即便每个都 @Volatile，也可能读到
     * "ab" 的 query 配上 "abc" 的结果集——基集比实际小，收窄后会漏结果，
     * 而且这种错配恰好绕过了"不是前缀就全量重算"的兜底。
     */
    private data class Narrow(
        val query: String,
        val result: List<GlobalPost>,
        val date: String?,
        val accounts: Set<Pair<String, String>>
    )

    @Volatile private var lastNarrow: Narrow? = null

    private fun applyFilter(q: String) {
        searchJob?.cancel()
        val debounce = q.isNotBlank() && q != lastNarrow?.query
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS.milliseconds)
            // 一次读一个引用，四个值天然一致
            val prev = lastNarrow
            val result = withContext(Dispatchers.Default) { computeFiltered(q, prev) }
            lastNarrow = Narrow(q, result, activeDate, currentFilters)
            filtered = result
            page = 0
            val missing = if (result.isEmpty() && q.isNotBlank()) {
                SearchUtil.monthFromTweetId(q.trim())
                    ?.takeIf { it !in loadedMonths && shardMonths.contains(it) }
            } else null
            _state.update { it.copy(
                query = q,
                filterAccounts = currentFilters,
                accounts = allAccounts,
                activeDate = activeDate,
                searchTotal = if (q.isBlank()) 0 else result.size,
                searchMissingMonth = missing
            ) }
            emitPage()
        }
    }

    private fun computeFiltered(raw: String, prev: Narrow?): List<GlobalPost> {

        var base = allPosts
        activeDate?.let { d -> base = base.filter { it.displayDate.startsWith(d) } }
        if (currentFilters.isNotEmpty()) {
            base = base.filter { (it.account.r to it.account.a) in currentFilters }
        }
        if (raw.isBlank()) return base

        SearchUtil.extractTCode(raw.trim())?.let { code ->
            return base.filter { it.tweetId.endsWith(code) }
        }
        if (SearchUtil.isFullTweetId(raw.trim())) {
            return base.filter { it.tweetId == raw.trim() }
        }

        val canNarrow = prev != null &&
            prev.query.isNotBlank() &&
            raw.startsWith(prev.query) &&
            prev.result.isNotEmpty() &&
            activeDate == prev.date &&
            currentFilters == prev.accounts
        val source = if (canNarrow) prev.result else base

        val hasAscii = SearchUtil.hasAsciiLetter(raw)
        val lower = raw.lowercase(java.util.Locale.ROOT)
        return source.filter {
            SearchUtil.matches(it.text, raw, lower, hasAscii) ||
                SearchUtil.matches(it.account.n, raw, lower, hasAscii) ||
                SearchUtil.matches(it.account.u, raw, lower, hasAscii) ||
                it.tweetId.contains(raw)
        }
    }

    /**
     * 从搜索结果跳到某条推文：清空搜索词、把日期筛选定位到它那一天，
     * 并记下要高亮的 tweetId 供列表滚动定位。
     */
    fun jumpToPost(post: GlobalPost) {
        val day = post.displayDate
        lastNarrow = null
        activeDate = day.takeIf { it.isNotBlank() }
        _state.update { it.copy(
            query = "",
            searchTotal = 0,
            searchMissingMonth = null,
            jumpTarget = post.tweetId
        ) }
        applyFilter("")
    }

    /**
     * 短码搜不到时的降级：从最新月份倒着逐个下载并检查，命中即停。
     * 短码是推文 ID 的后 8 位，而雪花 ID 的时间戳在高位，
     * 所以短码本身推不出日期，只能这样逐月找。
     */
    fun searchAllMonths(code: String) {
        if (fullSearchJob?.isActive == true) return
        fullSearchJob = viewModelScope.launch {
            val months = _state.value.shards.map { it.month }
                .filter { it !in loadedMonths }
                .sortedDescending()
            _state.update { it.copy(fullSearchRunning = true) }
            for (m in months) {
                if (!isActive) break
                val shard = meta?.shards?.find { it.month == m } ?: continue
                loadShard(shard, rebuild = false)
                if (monthPosts[m]?.any { p -> p.tweetId.endsWith(code) } == true) break
            }
            monthLock.withLock { rebuildFromLoaded() }
            _state.update { it.copy(fullSearchRunning = false) }
            applyFilter(_state.value.query)
        }
    }

    fun cancelFullSearch() {
        fullSearchJob?.cancel()
        _state.update { it.copy(fullSearchRunning = false) }
    }

    private var fullSearchJob: Job? = null

    /** 列表滚动到目标后调用，避免返回时又跳一次 */
    fun clearJumpTarget() {
        if (_state.value.jumpTarget != null) {
            _state.update { it.copy(jumpTarget = null) }
        }
    }

    fun loadMore() {
        if (!_state.value.canLoadMore) return
        page++
        emitPage()
    }

    private fun emitPage() {
        val end = ((page + 1) * pageSize).coerceAtMost(filtered.size)
        _state.update { it.copy(
            loading = false,
            visible = filtered.take(end),
            totalCount = filtered.size,
            canLoadMore = end < filtered.size,
            error = null,
            accounts = allAccounts,
            filterAccounts = currentFilters
        ) }
    }
}
