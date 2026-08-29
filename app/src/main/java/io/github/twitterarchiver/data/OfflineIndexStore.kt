package io.github.twitterarchiver.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.security.MessageDigest

/**
 * 离线索引存储：把每个账号的 index.json 按月切成本地小文件，
 * 依据 home/manifest/{repo}.json（月度哈希 + 字节区间）做增量更新——
 * 只有发生变化的月份才通过 HTTP Range 重新下载对应字节段。
 */
class OfflineIndexStore(private val api: GitHubApi) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun dirFor(repo: String, account: String): File? =
        AppDirs.root?.let { File(it, "index_cache/$repo/$account") }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun load(repo: String, account: String, force: Boolean = false): List<Tweet>? =
        locks.getOrPut("$repo/$account") { Mutex() }.withLock {
            loadLocked(repo, account, force)
        }

    suspend fun clearAll() {
        val held = locks.values.toList()
        held.forEach { it.lock() }
        try {
            AppDirs.root?.let { File(it, "index_cache").deleteRecursively() }
        } finally {
            held.forEach { it.unlock() }
        }
    }

    private suspend fun loadLocked(repo: String, account: String, force: Boolean): List<Tweet>? {
        val dir = dirFor(repo, account) ?: return null
        return try {
            val manifest = api.fetchIndexManifest(repo)
                ?.takeIf { it.account.isBlank() || it.account == account }
            if (!force) incrementalSync(dir, repo, account, manifest)?.let { return it }
            fullDownload(dir, repo, account, manifest)?.let { return it }
            readLocal(dir)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            readLocal(dir)
        }
    }

    private suspend fun incrementalSync(
        dir: File, repo: String, account: String, manifest: IndexManifest?
    ): List<Tweet>? {
        if (manifest == null || !manifest.range || manifest.months.isEmpty()) return null

        val state = readState(dir)
        val changed = manifest.months.filter { (m, span) ->
            state.months[m] != span.hash || !File(dir, "$m.json").isFile
        }
        val removed = state.months.keys - manifest.months.keys
        if (changed.isEmpty() && removed.isEmpty()) return readLocal(dir)

        dir.mkdirs()
        val months = state.months.toMutableMap()
        for ((month, span) in changed) {
            val (status, bytes) =
                api.fetchIndexRange(repo, account, span.offset, span.length) ?: return null
            when {
                status == 200 -> return saveWhole(dir, bytes, manifest)
                status != 206 || sha16(bytes) != span.hash -> return null
                else -> {
                    File(dir, "$month.json").writeBytes(bytes)
                    months[month] = span.hash
                    writeState(dir, LocalIndexState(months.toMap(), state.total))
                }
            }
        }
        removed.forEach {
            File(dir, "$it.json").delete()
            months.remove(it)
        }
        writeState(dir, LocalIndexState(months.toMap(), manifest.total))
        return readLocal(dir)
    }

    private suspend fun fullDownload(
        dir: File, repo: String, account: String, manifest: IndexManifest?
    ): List<Tweet>? {
        val bytes = try {
            api.fetchIndexBytes(repo, account)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return null
        }
        return saveWhole(dir, bytes, manifest)
    }

    private fun saveWhole(dir: File, bytes: ByteArray, manifest: IndexManifest?): List<Tweet>? =
        splitAndSave(dir, bytes, manifest) ?: parseWhole(bytes)

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
    private fun splitAndSave(dir: File, raw: ByteArray, manifest: IndexManifest?): List<Tweet>? {
        val spans = splitByMonth(raw) ?: run {
            File(dir, "_state.json").delete()
            return null
        }
        dir.mkdirs()
        val staging = File(dir.parentFile, "${dir.name}.staging")
            .apply { deleteRecursively(); mkdirs() }
        for ((month, range) in spans) {
            File(staging, "$month.json").writeBytes(raw.copyOfRange(range.first, range.last + 1))
        }
        writeState(
            staging, LocalIndexState(
                months = manifest?.months?.mapValues { it.value.hash }.orEmpty(),
                total = manifest?.total ?: 0,
            )
        )

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
