package io.github.twitterarchiver.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OfflineIndexStore(private val api: GitHubApi) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun dirFor(repo: String, account: String): File? =
        AppDirs.root?.let { File(it, "index_cache/$repo/$account") }

    /** 每个账号一把锁：splitAndSave 会先清空目录再写，读写并发会拿到半截文件 */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun load(repo: String, account: String, force: Boolean = false): List<Tweet>? =
        locks.getOrPut("$repo/$account") { Mutex() }.withLock {
            loadLocked(repo, account, force)
        }

    private suspend fun loadLocked(repo: String, account: String, force: Boolean): List<Tweet>? {
        val dir = dirFor(repo, account) ?: return null
        return try {
            if (force) {
                val mf = api.fetchIndexManifest(repo)
                    ?.takeIf { it.account.isBlank() || it.account == account }
                fullRefresh(dir, repo, account, mf) ?: readLocal(dir)
            }
            else sync(dir, repo, account)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            readLocal(dir)
        }
    }

    private suspend fun sync(dir: File, repo: String, account: String): List<Tweet>? {
        val manifest = api.fetchIndexManifest(repo)
            ?.takeIf { it.account.isBlank() || it.account == account }

        if (manifest == null || !manifest.range || manifest.months.isEmpty()) {
            return fullRefresh(dir, repo, account) ?: readLocal(dir)
        }

        val state = readState(dir)
        val changed = manifest.months.filter { (m, span) ->
            state.months[m] != span.hash || !File(dir, "$m.json").isFile
        }
        val removed = state.months.keys - manifest.months.keys

        if (changed.isEmpty() && removed.isEmpty()) {
            readLocal(dir)?.let { return it }
            return fullRefresh(dir, repo, account, manifest)
        }

        dir.mkdirs()
        for ((month, span) in changed) {
            val res = api.fetchIndexRange(repo, account, span.offset, span.length)
                ?: return fullRefresh(dir, repo, account, manifest) ?: readLocal(dir)
            val (status, bytes) = res
            when (status) {
                206 if sha16(bytes) == span.hash ->
                    File(dir, "$month.json").writeBytes(bytes)
                200 ->
                    return fullRefresh(dir, repo, account, manifest) ?: readLocal(dir)
                else ->
                    return fullRefresh(dir, repo, account, manifest) ?: readLocal(dir)
            }
        }
        removed.forEach { File(dir, "$it.json").delete() }
        writeState(dir, LocalIndexState(
            months = manifest.months.mapValues { it.value.hash },
            total = manifest.total,
        ))
        return readLocal(dir) ?: fullRefresh(dir, repo, account, manifest)
    }

    /** 全量下载并按月落盘；网络失败返回 null */
    private suspend fun fullRefresh(
        dir: File, repo: String, account: String, manifest: IndexManifest? = null
    ): List<Tweet>? {
        val bytes = try {
            api.fetchIndexBytes(repo, account)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return null
        }
        val result = splitAndSave(dir, bytes) ?: return parseWhole(bytes)
        if (manifest != null) writeState(dir, LocalIndexState(
            months = manifest.months.mapValues { it.value.hash },
            total = manifest.total,
        ))
        return result
    }

    private fun readState(dir: File): LocalIndexState = try {
        json.decodeFromString<LocalIndexState>(File(dir, "_state.json").readText())
    } catch (e: Exception) { LocalIndexState() }

    private fun writeState(dir: File, state: LocalIndexState) {
        try {
            File(dir, "_state.json").writeText(json.encodeToString(LocalIndexState.serializer(), state))
        } catch (e: Exception) { /* 写不进就下次重来 */ }
    }

    /** 读出全部本地月份文件并合并（时间倒序）。缺失/损坏返回 null */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readLocal(dir: File): List<Tweet>? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "_state.json" }
            ?.takeIf { it.isNotEmpty() } ?: return null
        val ordered = files.sortedWith(
            compareBy<File> { it.name == "unknown.json" }.thenByDescending { it.name }
        )
        return try {
            val parts = ArrayList<java.io.InputStream>(ordered.size * 2 + 2)
            parts.add(java.io.ByteArrayInputStream(BRACKET_OPEN))
            ordered.forEachIndexed { i, f ->
                if (i > 0) parts.add(java.io.ByteArrayInputStream(COMMA))
                parts.add(f.inputStream())
            }
            parts.add(java.io.ByteArrayInputStream(BRACKET_CLOSE))
            java.io.SequenceInputStream(java.util.Collections.enumeration(parts)).use { input ->
                json.decodeFromStream<List<Tweet>>(input)
            }
        } catch (e: Exception) { null }
    }

    private companion object {
        val BRACKET_OPEN = "[".toByteArray()
        val BRACKET_CLOSE = "]".toByteArray()
        val COMMA = ",".toByteArray()
    }

    private fun parseWhole(bytes: ByteArray): List<Tweet>? = try {
        json.decodeFromString<List<Tweet>>(bytes.decodeToString())
    } catch (e: Exception) { null }

    /** 把完整 index.json 字节按月切分落盘并写状态；月份交错等异常返回 null */
    private fun splitAndSave(dir: File, raw: ByteArray): List<Tweet>? {
        val spans = splitByMonth(raw) ?: run {
            File(dir, "_state.json").delete()
            return null
        }
        dir.mkdirs()
        val staging = File(dir.parentFile, "${dir.name}.staging")
            .apply { deleteRecursively(); mkdirs() }
        val hashes = HashMap<String, String>()
        for ((month, range) in spans) {
            val seg = raw.copyOfRange(range.first, range.last + 1)
            File(staging, "$month.json").writeBytes(seg)
            hashes[month] = sha16(seg)
        }
        writeState(staging, LocalIndexState(months = hashes))

        val trash = File(dir.parentFile, "${dir.name}.trash")
        trash.deleteRecursively()
        val swapped = (!dir.exists() || dir.renameTo(trash)) && staging.renameTo(dir)
        trash.deleteRecursively()
        if (!swapped) {
            staging.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            staging.deleteRecursively()
        }
        return readLocal(dir)
    }

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
            val end = i
            val month = extractMonth(raw, start, end)

            if (month != curMonth) {
                if (month in seen) return null
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
