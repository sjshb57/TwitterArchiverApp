package io.github.twitterarchiver.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.twitterarchiver.data.GitHubApi
import io.github.twitterarchiver.data.GlobalPost
import io.github.twitterarchiver.data.IndexAccount
import io.github.twitterarchiver.data.CrossReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GlobalState(
    val loading: Boolean = true,
    val visible: List<GlobalPost> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false,
    val loadingFull: Boolean = false,  // 后台在加载完整索引
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

    init { load() }

    fun load() {
        if (recentLoaded) return
        viewModelScope.launch {
            _state.value = GlobalState(loading = true)
            try {
                // 1. 先加载轻量的"最新一批"(小、秒开)。工作流已排好序，直接读。
                val (accts, recent) = api.fetchRecentTimeline()
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

    private fun loadFull() {
        if (fullLoaded) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingFull = true)
            try {
                val (accts, full) = api.fetchSearchIndex()
                fullLoaded = true
                allPosts = full
                allAccounts = accts
                // 若当前没在搜索，更新 filtered（保持已翻页数）
                if (_state.value.query.isBlank()) {
                    filtered = full
                    emitPage()
                }
                _state.value = _state.value.copy(loadingFull = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingFull = false)
            }
        }
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

    private fun loadFullAsInitial() {
        viewModelScope.launch {
            try {
                val (_, full) = api.fetchSearchIndex()
                fullLoaded = true; recentLoaded = true
                allPosts = full; filtered = full
                page = 0
                emitPage()
            } catch (e: Exception) {
                _state.value = GlobalState(loading = false, error = "加载失败：${e.message}")
            }
        }
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
                accounts = allAccounts
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
