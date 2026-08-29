package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.R
import io.github.twitterarchiver.data.AppStrings
import io.github.twitterarchiver.data.CrossReply
import io.github.twitterarchiver.data.GitHubApi
import io.github.twitterarchiver.data.GlobalIndexStore
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.GlobalShard
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.data.Repository
import io.github.twitterarchiver.util.MediaUtil
import io.github.twitterarchiver.util.SearchUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

data class GlobalState(
    val loading: Boolean = true,
    val visible: List<GlobalPost> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false,
    val loadingFull: Boolean = false,
    val globalTotal: Int = 0,
    val shards: List<GlobalShard> = emptyList(),
    val downloadedMonths: Set<String> = emptySet(),
    val monthProgress: Map<String, Float> = emptyMap(),
    val indexError: String? = null,
    val dayCounts: Map<String, Int> = emptyMap(),
    val activeDate: String? = null,
    val filterAccounts: Set<Pair<String, String>> = emptySet(),
    val accounts: List<IndexAccount> = emptyList(),
    val searchTotal: Int = 0,
    val searchMissingMonth: String? = null,
    val jumpTarget: String? = null,
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
    private val deleteEpoch = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun bumpDeleteEpoch(month: String) {
        deleteEpoch.merge(month, 1, Int::plus)
    }

    private suspend fun downloadedMonths(): Set<String> =
        withContext(Dispatchers.IO) { GlobalIndexStore.downloadedMonths() }

    init { load() }

    fun load(force: Boolean = false) {
        if (recentLoaded && !force) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (force) {
                fullLoaded = false
                lastNarrow = null
            }
            try {
                val (accts, recent) = api.fetchRecentTimeline()
                val recentUnique = recent.distinctBy { it.tweetId }
                recentPosts = recentUnique
                allAccounts = accts.distinctBy { it.r to it.a }
                recentLoaded = true
                page = 0
                monthLock.withLock { rebuildFromLoaded() }
                loadFull()
                loadCrossReplies()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
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
                val local = downloadedMonths()
                _state.update { it.copy(
                    loadingFull = false,
                    globalTotal = m.total,
                    shards = m.shards,
                    downloadedMonths = local,
                    indexError = null
                ) }
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
                bumpDeleteEpoch(month)
                loadedMonths = loadedMonths - month
                monthPosts.remove(month)
                rebuildFromLoaded()
                val left = downloadedMonths()
                _state.update { it.copy(downloadedMonths = left) }
            }
        }
    }

    fun deleteYear(year: String) {
        viewModelScope.launch {
            monthLock.withLock {
                withContext(Dispatchers.IO) { GlobalIndexStore.deleteYear(year) }
                loadedMonths.filter { it.startsWith(year) }.forEach { bumpDeleteEpoch(it) }
                loadedMonths = loadedMonths.filterNot { it.startsWith(year) }.toSet()
                monthPosts.keys.filter { it.startsWith(year) }.forEach { monthPosts.remove(it) }
                rebuildFromLoaded()
                val left = downloadedMonths()
                _state.update { it.copy(downloadedMonths = left) }
            }
        }
    }

    private suspend fun loadShard(
        shard: GlobalShard,
        silent: Boolean = false,
        rebuild: Boolean = true
    ) {
        if (shard.month in loadedMonths) return
        val m = meta ?: return
        val epochBefore = deleteEpoch[shard.month] ?: 0
        if (!silent) _state.update { it.copy(
            monthProgress = it.monthProgress + (shard.month to 0f)) }
        val posts = try {
            var lastPct = -1
            api.fetchGlobalShard(shard, m.accounts) { done ->
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
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _state.update {
                it.copy(
                    monthProgress = it.monthProgress - shard.month,
                    indexError = AppStrings.get(
                        R.string.shard_download_failed, shard.month,
                        "${e::class.java.simpleName} ${e.message.orEmpty()}".trim()
                    )
                )
            }
            return
        }
        monthLock.withLock {
            if (shard.month in loadedMonths) return@withLock
            if ((deleteEpoch[shard.month] ?: 0) != epochBefore) {
                withContext(Dispatchers.IO) { GlobalIndexStore.deleteMonth(shard.month) }
                return@withLock
            }
            monthPosts[shard.month] = posts
            loadedMonths = loadedMonths + shard.month
            if (rebuild) rebuildFromLoaded()
        }
        val months = downloadedMonths()
        _state.update {
            it.copy(
                monthProgress = it.monthProgress - shard.month,
                downloadedMonths = months,
                indexError = null
            )
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
            out.sortWith(io.github.twitterarchiver.util.DateUtil
                .timeComparator(ascending = false) { it.epochMs })
            out
        }
        allPosts = merged
        lastNarrow = null
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
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /**
     * 加载某主推文的完整对话：引用原推 + 回复链。
     * 回复链 = 主人自己的回复链（reader 逻辑）+ 别人的跨账号回复（cross-replies）。
     */
    suspend fun loadThread(post: GlobalPost): Pair<io.github.twitterarchiver.data.QuotedTweet?, List<io.github.twitterarchiver.data.ThreadItem>> {
        val repo = post.account.r
        val account = post.account.a
        val ownerUname = post.account.u.removePrefix("@").lowercase()

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
            if (t.images.isEmpty()) return emptyList()
            val base = io.github.twitterarchiver.data.Config.snapshotsBase(repo, account)
            return t.images.map { rel ->
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

        val mainTweet = idIndex[post.tweetId]
        var quoted: io.github.twitterarchiver.data.QuotedTweet? = null
        if (mainTweet != null) {
            if (mainTweet.hasQuoted && mainTweet.quotedId.isNotBlank()) {
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
            if (quoted == null && (mainTweet.text.startsWith("RT @") ||
                        mainTweet.bodyText.startsWith("RT @") ||
                        mainTweet.embeddedImages.isNotEmpty())) {
                quoted = try {
                    api.fetchEmbeddedTweet(repo, account, mainTweet.file, mainTweet.embeddedImages)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
        }

        val replyMap = HashMap<String, MutableList<io.github.twitterarchiver.data.Tweet>>()
        for (t in tweets) {
            if (t.isVirtual) continue
            if (t.isReply && t.conversationId.isNotBlank()) {
                replyMap.getOrPut(t.conversationId) { ArrayList() }.add(t)
            }
        }
        val ownerReplies = (replyMap[post.tweetId] ?: emptyList())
            .sortedWith(io.github.twitterarchiver.util.DateUtil
                .timeComparator(ascending = true) { it.epochMs })
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

        val sorted = chain.sortedWith(io.github.twitterarchiver.util.DateUtil
            .timeComparator(ascending = true) { it.epochMs })
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

    fun searchAllMonths(code: String) {
        if (fullSearchJob?.isActive == true) return
        fullSearchJob = viewModelScope.launch {
            val months = _state.value.shards.map { it.month }
                .filter { it !in loadedMonths }
                .sortedDescending()
            _state.update { it.copy(fullSearchRunning = true) }
            try {
                for (m in months) {
                    if (!isActive) break
                    val shard = meta?.shards?.find { it.month == m } ?: continue
                    loadShard(shard, rebuild = false)
                    if (monthPosts[m]?.any { p -> p.tweetId.endsWith(code) } == true) break
                }
            } finally {
                withContext(NonCancellable) {
                    monthLock.withLock { rebuildFromLoaded() }
                    _state.update { it.copy(fullSearchRunning = false) }
                    applyFilter(_state.value.query)
                }
            }
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
