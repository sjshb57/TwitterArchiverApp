package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.GitHubApi
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.data.CrossReply
import io.github.twitterarchiver.data.GlobalIndexStore
import io.github.twitterarchiver.data.GlobalShard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val filterAccounts: Set<Pair<String, String>> = emptySet(),  // 当前筛选的账号集合（空=全部）
    val accounts: List<IndexAccount> = emptyList()  // 所有账号（供筛选选择）
)

class GlobalTimelineViewModel(private val api: GitHubApi = GitHubApi()) : ViewModel() {

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
    private var activeDate: String? = null
    /** 年下载会并发触发同一个月，串行化避免重复下载与并发改 loadedMonths */
    private val monthLock = kotlinx.coroutines.sync.Mutex()

    init { load() }

    fun load() {
        if (recentLoaded) return
        viewModelScope.launch {
            _state.value = GlobalState(loading = true)
            try {
                // 1. 先加载轻量的"最新一批"(小、秒开)。工作流已排好序，直接读。
                val (accts, recent) = api.fetchRecentTimeline()
                recentPosts = recent
                allPosts = recent
                filtered = recent
                allAccounts = accts
                recentLoaded = true
                page = 0
                emitPage()
                // 2. 后台静默加载完整索引 + 跨账号回复
                loadFull()
                loadCrossReplies()
            } catch (e: Exception) {
                // recent 失败则直接尝试完整索引
                loadFullAsInitial()
            }
        }
    }

    /** 只取分片清单。历史内容一律按需下载，冷启动不再自动拉整年。 */
    private fun loadFull() {
        if (fullLoaded) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingFull = true)
            try {
                val m = api.fetchGlobalMeta()
                meta = m
                allAccounts = m.accounts
                fullLoaded = true
                _state.value = _state.value.copy(
                    loadingFull = false,
                    globalTotal = m.total,
                    shards = m.shards,
                    downloadedMonths = GlobalIndexStore.downloadedMonths(),
                    indexError = null
                )
                // 上次已下载到本地的月份直接合入，无需联网
                val local = GlobalIndexStore.downloadedMonths()
                m.shards.filter { it.month in local }
                    .forEach { loadShard(it, silent = true, rebuild = false) }
                rebuildFromLoaded()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loadingFull = false,
                    indexError = "索引清单加载失败：${e::class.simpleName} ${e.message ?: ""}"
                )
            }
        }
    }

    /** 下载并合入某一年的全部月份 */
    fun downloadYear(year: String) {
        val m = meta ?: return
        viewModelScope.launch {
            // 每合入一个月就全量重排一次的话，一年要排 12 遍几十万条。只在最后重建一次。
            for (shard in m.shardsOf(year).sortedByDescending { it.month })
                loadShard(shard, rebuild = false)
            rebuildFromLoaded()
        }
    }

    /** 下载并合入单个月份 */
    fun downloadMonth(month: String) {
        val shard = meta?.shards?.find { it.month == month } ?: return
        viewModelScope.launch { loadShard(shard) }
    }

    fun deleteMonth(month: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { GlobalIndexStore.deleteMonth(month) }
            loadedMonths = loadedMonths - month
            rebuildFromLoaded()
            _state.value = _state.value.copy(downloadedMonths = GlobalIndexStore.downloadedMonths())
        }
    }

    fun deleteYear(year: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { GlobalIndexStore.deleteYear(year) }
            loadedMonths = loadedMonths.filterNot { it.startsWith(year) }.toSet()
            rebuildFromLoaded()
            _state.value = _state.value.copy(downloadedMonths = GlobalIndexStore.downloadedMonths())
        }
    }

    private suspend fun loadShard(
        shard: GlobalShard,
        silent: Boolean = false,
        rebuild: Boolean = true
    ) = monthLock.withLock {
        if (shard.month in loadedMonths) return@withLock
        val m = meta ?: return@withLock
        if (!silent) _state.value = _state.value.copy(
            monthProgress = _state.value.monthProgress + (shard.month to 0f))
        try {
            // 每 64KB 回调一次，12MB 的分片会回调近 200 次。只在百分比整数变化时才发状态，
            // 否则下载一个月就要触发两百次重组。
            var lastPct = -1
            val posts = api.fetchGlobalShard(shard, m.accounts) { done ->
                if (!silent && shard.bytes > 0) {
                    val p = (done.toFloat() / shard.bytes).coerceIn(0f, 1f)
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        _state.value = _state.value.copy(
                            monthProgress = _state.value.monthProgress + (shard.month to p))
                    }
                }
            }
            monthPosts[shard.month] = posts
            loadedMonths = loadedMonths + shard.month
            if (rebuild) rebuildFromLoaded()
            _state.value = _state.value.copy(
                monthProgress = _state.value.monthProgress - shard.month,
                downloadedMonths = GlobalIndexStore.downloadedMonths(),
                indexError = null
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                monthProgress = _state.value.monthProgress - shard.month,
                indexError = "${shard.month} 下载失败：${e::class.simpleName} ${e.message ?: ""}"
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
            // 两种时间格式并存，必须解析成时间戳再比，直接比字符串会把老格式全排到最前
            out.sortByDescending { io.github.twitterarchiver.util.DateUtil.epochMillis(it.time) }
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
        _state.value = _state.value.copy(dayCounts = counts)
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

    // 账号 index.json 缓存（repo/account -> tweets），避免重复拉取
    private val accountIndexCache = HashMap<String, List<io.github.twitterarchiver.data.Tweet>>()

    /**
     * 加载某主推文的完整对话：引用原推 + 回复链。
     * 回复链 = 主人自己的回复链（reader 逻辑）+ 别人的跨账号回复（cross-replies）。
     */
    suspend fun loadThread(post: GlobalPost): Pair<io.github.twitterarchiver.data.QuotedTweet?, List<io.github.twitterarchiver.data.ThreadItem>> {
        val repo = post.account.r
        val account = post.account.a
        val ownerUname = post.account.u.removePrefix("@").lowercase()

        // 1. 拉账号 index.json（缓存）
        val cacheKey = "$repo/$account"
        val tweets = accountIndexCache[cacheKey] ?: try {
            api.fetchTweets(repo, account).also { accountIndexCache[cacheKey] = it }
        } catch (e: Exception) { emptyList() }

        val idIndex = tweets.associateBy { it.tweetId }
        fun avatarUrl(av: String) = if (av.isBlank()) "" else
            "${io.github.twitterarchiver.data.Config.snapshotsBase(repo, account)}/${av.removePrefix("../")}"
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
        //    主推文的 conversation_id == 自己的 tweet_id，所以取 replyMap[主推文id]
        val replyMap = HashMap<String, MutableList<io.github.twitterarchiver.data.Tweet>>()
        for (t in tweets) {
            if (t.isVirtual) continue
            if (t.isReply && t.conversationId.isNotBlank()) {
                replyMap.getOrPut(t.conversationId) { ArrayList() }.add(t)
            }
        }
        val ownerReplies = (replyMap[post.tweetId] ?: emptyList())
            .sortedBy { it.timestamp }
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
                    authorName = acct?.n ?: "某账号",
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
        val sorted = chain.sortedBy { it.time }
        return quoted to sorted
    }

    /** timeline-recent 拿不到时的兜底：直接走分片清单 */
    private fun loadFullAsInitial() {
        viewModelScope.launch {
            recentLoaded = true
            _state.value = _state.value.copy(loading = false)
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
        _state.value = _state.value.copy(indexError = null)
    }

    fun search(q: String) = applyFilter(q)

    /** 多选筛选：传入选中的账号集合（空=全部） */
    fun filterByAccounts(accounts: Set<IndexAccount>) {
        currentFilters = accounts.map { it.r to it.a }.toSet()
        applyFilter(_state.value.query)
    }

    /** 应用账号筛选 + 搜索词 */
    private fun applyFilter(q: String) {
        viewModelScope.launch {
            filtered = withContext(Dispatchers.Default) {
                var list = allPosts
                // 前缀匹配，yyyy-MM 就是整月，yyyy-MM-dd 就是某天
                activeDate?.let { d -> list = list.filter { it.displayDate.startsWith(d) } }
                if (currentFilters.isNotEmpty()) {
                    list = list.filter { (it.account.r to it.account.a) in currentFilters }
                }
                if (q.isNotBlank()) {
                    val raw = q.trim()
                    // 定位短码 ?t=后8位：提取后8位做后缀匹配
                    val tCode = Regex("^\\?t=(\\w+)$").find(raw)?.groupValues?.get(1)
                    if (tCode != null) {
                        list = list.filter { it.tweetId.endsWith(tCode) }
                    } else {
                        val lower = raw.lowercase()
                        list = list.filter {
                            it.text.lowercase().contains(lower) ||
                                it.account.n.lowercase().contains(lower) ||
                                it.account.u.lowercase().contains(lower) ||
                                it.tweetId.contains(raw)   // 支持按推文 ID 搜索
                        }
                    }
                }
                list
            }
            page = 0
            _state.value = _state.value.copy(
                query = q,
                filterAccounts = currentFilters,
                accounts = allAccounts,
                activeDate = activeDate
            )
            emitPage()
        }
    }

    fun loadMore() {
        if (!_state.value.canLoadMore) return
        page++
        emitPage()
    }

    private fun emitPage() {
        val end = ((page + 1) * pageSize).coerceAtMost(filtered.size)
        _state.value = _state.value.copy(
            loading = false,
            visible = filtered.take(end),
            totalCount = filtered.size,
            canLoadMore = end < filtered.size,
            error = null,
            accounts = allAccounts,
            filterAccounts = currentFilters
        )
    }
}
