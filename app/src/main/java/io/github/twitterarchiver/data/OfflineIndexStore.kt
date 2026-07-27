package io.github.twitterarchiver.data

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 离线索引存储：把每个账号的 index.json 按月切成本地小文件，
 * 依据 home/manifest/{repo}.json（月度哈希 + 字节区间）做增量更新——
 * 只有发生变化的月份才通过 HTTP Range 重新下载对应字节段。
 *
 * 服务端不真正分片；分片只存在于本地：
 *   files/index_cache/{repo}/
 *     _state.json    已缓存月份 -> 清单哈希
 *     2026-07.json   该月记录的原始字节片段（不含外层 [ ]，与服务端字节一致）
 *
 * 安全性依赖内容哈希而非 ETag：Range 取回的字节段先对清单哈希，
 * 一致才落盘；不一致（清单已过期、offset 失效）退回全量下载。
 * GitHub Pages 的 ETag 按部署时间生成，Pages 重新部署就会变，不可依赖。
 *
 * 任何一步失败都逐级兜底：增量失败 → 全量下载 → 本地旧数据 → null（调用方直连）。
 */
class OfflineIndexStore(private val api: GitHubApi) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun dirFor(repo: String): File? =
        AppDirs.root?.let { File(it, "index_cache/$repo") }

    /**
     * 加载某账号的推文列表。
     * 返回 null 表示离线层完全无法提供（未初始化/彻底失败且无本地数据），
     * 由调用方退回旧的直连路径。
     */
    suspend fun load(repo: String, account: String): List<Tweet>? {
        val dir = dirFor(repo) ?: return null
        return try {
            sync(dir, repo, account)
        } catch (e: Exception) {
            readLocal(dir)          // 意外异常：能读本地就读本地
        }
    }

    // ── 同步主流程 ──────────────────────────────────────────────────────────

    private suspend fun sync(dir: File, repo: String, account: String): List<Tweet>? {
        val manifest = api.fetchIndexManifest(repo)

        // 清单不可用 / 标记不可 Range：全量路径（失败退本地）
        if (manifest == null || !manifest.range || manifest.months.isEmpty()) {
            return fullRefresh(dir, repo, account) ?: readLocal(dir)
        }

        val state = readState(dir)
        val changed = manifest.months.filter { (m, span) ->
            state.months[m] != span.hash || !File(dir, "$m.json").isFile
        }
        val removed = state.months.keys - manifest.months.keys

        // 全部命中：一次清单请求（几 KB）就完事
        if (changed.isEmpty() && removed.isEmpty()) {
            readLocal(dir)?.let { return it }
            return fullRefresh(dir, repo, account)   // 本地文件损坏/丢失
        }

        // 增量：逐月 Range 取变化的字节段
        dir.mkdirs()
        for ((month, span) in changed) {
            val res = api.fetchIndexRange(repo, account, span.offset, span.length)
                ?: return fullRefresh(dir, repo, account) ?: readLocal(dir)
            val (status, bytes) = res
            when {
                status == 206 && sha16(bytes) == span.hash ->
                    File(dir, "$month.json").writeBytes(bytes)
                status == 200 ->
                    // 服务器给了全量（文件已更新，比清单还新）：直接用它落盘
                    return splitAndSave(dir, bytes) ?: parseWhole(bytes)
                else ->
                    // 哈希不符：清单过期、offset 已失效 → 全量兜底
                    return fullRefresh(dir, repo, account) ?: readLocal(dir)
            }
        }
        removed.forEach { File(dir, "$it.json").delete() }
        writeState(dir, LocalIndexState(
            months = manifest.months.mapValues { it.value.hash },
            total = manifest.total,
        ))
        return readLocal(dir) ?: fullRefresh(dir, repo, account)
    }

    /** 全量下载并按月落盘；网络失败返回 null */
    private suspend fun fullRefresh(dir: File, repo: String, account: String): List<Tweet>? {
        val bytes = try { api.fetchIndexBytes(repo, account) } catch (e: Exception) { return null }
        return splitAndSave(dir, bytes) ?: parseWhole(bytes)
    }

    // ── 本地读写 ────────────────────────────────────────────────────────────

    private fun readState(dir: File): LocalIndexState = try {
        json.decodeFromString<LocalIndexState>(File(dir, "_state.json").readText())
    } catch (e: Exception) { LocalIndexState() }

    private fun writeState(dir: File, state: LocalIndexState) {
        try {
            File(dir, "_state.json").writeText(json.encodeToString(LocalIndexState.serializer(), state))
        } catch (e: Exception) { /* 写不进就下次重来 */ }
    }

    /** 读出全部本地月份文件并合并（时间倒序）。缺失/损坏返回 null */
    private fun readLocal(dir: File): List<Tweet>? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "_state.json" }
            ?.takeIf { it.isNotEmpty() } ?: return null
        // 月份倒序；unknown 固定排最后
        val ordered = files.sortedWith(
            compareBy<File> { it.name == "unknown.json" }.thenByDescending { it.name }
        )
        return try {
            val sb = StringBuilder("[")
            ordered.forEachIndexed { i, f ->
                if (i > 0) sb.append(',')
                sb.append(f.readText())
            }
            sb.append("]")
            json.decodeFromString<List<Tweet>>(sb.toString())
        } catch (e: Exception) { null }
    }

    private fun parseWhole(bytes: ByteArray): List<Tweet>? = try {
        json.decodeFromString<List<Tweet>>(bytes.decodeToString())
    } catch (e: Exception) { null }

    // ── 按月切分（与生成端 build-manifest 相同的字节级逻辑） ────────────────

    /** 把完整 index.json 字节按月切分落盘并写状态；月份交错等异常返回 null */
    private fun splitAndSave(dir: File, raw: ByteArray): List<Tweet>? {
        val spans = splitByMonth(raw) ?: run {
            // 切不动：清掉状态，本次直接内存解析，下次再试
            File(dir, "_state.json").delete()
            return null
        }
        dir.mkdirs()
        // 清掉旧月份文件再写新的，避免残留
        dir.listFiles()?.forEach { if (it.name.endsWith(".json") && it.name != "_state.json") it.delete() }
        val hashes = HashMap<String, String>()
        for ((month, range) in spans) {
            val seg = raw.copyOfRange(range.first, range.last + 1)
            File(dir, "$month.json").writeBytes(seg)
            hashes[month] = sha16(seg)
        }
        writeState(dir, LocalIndexState(months = hashes))
        return readLocal(dir)
    }

    /**
     * 字节级扫描：定位每条顶层记录的区间，按 timestamp 前 7 位归组为连续月份块。
     * 返回 [(月份, 字节区间)]；月份出现交错返回 null。
     */
    private fun splitByMonth(raw: ByteArray): List<Pair<String, IntRange>>? {
        var i = raw.indexOfFirst { it == '['.code.toByte() }
        if (i < 0) return null
        i++
        val spans = ArrayList<Pair<String, IntRange>>()
        val seen = HashSet<String>()
        var curMonth: String? = null
        var blockStart = -1
        var blockEnd = -1

        while (i < raw.size) {
            val b = raw[i].toInt()
            if (b == ','.code || b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) { i++; continue }
            if (b == ']'.code) break
            if (b != '{'.code) return null

            val start = i
            var depth = 0
            var inStr = false
            var esc = false
            while (i < raw.size) {
                val c = raw[i].toInt()
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\'.code -> esc = true
                        c == '"'.code -> inStr = false
                    }
                } else when (c) {
                    '"'.code -> inStr = true
                    '{'.code -> depth++
                    '}'.code -> { depth--; if (depth == 0) { i++; break } }
                }
                i++
            }
            val end = i          // exclusive
            val month = extractMonth(raw, start, end)

            if (month != curMonth) {
                if (month in seen) return null          // 交错
                // blockEnd 是开区间上界，IntRange 收闭区间，故减一
                curMonth?.let { spans.add(it to IntRange(blockStart, blockEnd - 1)) }
                seen.add(month)
                curMonth = month
                blockStart = start
            }
            blockEnd = end
        }
        curMonth?.let { spans.add(it to IntRange(blockStart, blockEnd - 1)) }
        return spans
    }

    private val tsKey = "\"timestamp\":\"".toByteArray()

    /** 在记录字节段里找 "timestamp":"YYYY-MM…，取前 7 位；找不到归 unknown */
    private fun extractMonth(raw: ByteArray, from: Int, to: Int): String {
        var i = from
        val limit = to - tsKey.size - 7
        outer@ while (i <= limit) {
            for (j in tsKey.indices) {
                if (raw[i + j] != tsKey[j]) { i++; continue@outer }
            }
            val s = String(raw, i + tsKey.size, 7)
            return if (s.length == 7 && s[4] == '-' && s.take(4).all { it.isDigit() }) s else "unknown"
        }
        return "unknown"
    }

    private fun sha16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8)
            .joinToString("") { "%02x".format(it) }
}
