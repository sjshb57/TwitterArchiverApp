package io.github.twitterarchiver.data

import java.io.File

/** meta.json 里的一个月度分片 */
data class GlobalShard(
    val month: String,          // YYYY-MM
    val count: Int,
    val bytes: Long,
    val hash: String
)

/** search-index/meta.json */
data class GlobalIndexMeta(
    val total: Int,             // 全站推文总数，与本地下载了多少无关
    val generated: String,
    val accounts: List<IndexAccount>,
    val shards: List<GlobalShard>
) {
    fun shardsOf(year: String) = shards.filter { it.month.startsWith(year) }
}

/**
 * 全站索引的本地副本。
 *
 * 刻意放在 files/global_index/ 而不是 index_cache/：设置页的"清理缓存"会把
 * index_cache/ 整个递归删掉，历史年份是用户手动下载的，不该被顺手清掉。
 * 它的清理入口在"历史存档"管理页。
 */
object GlobalIndexStore {

    private const val DIR = "global_index"

    private fun dir(): File? = AppDirs.root?.let { File(it, DIR).apply { mkdirs() } }

    private fun shardFile(month: String): File? = dir()?.let { File(it, "$month.json") }

    private fun metaFile(): File? = dir()?.let { File(it, "meta.json") }

    fun readMetaRaw(): String? = metaFile()?.takeIf { it.isFile }?.readText()

    fun writeMetaRaw(text: String) {
        metaFile()?.writeText(text)
    }

    fun shardBytes(month: String): ByteArray? = shardFile(month)?.takeIf { it.isFile }?.readBytes()

    fun writeShard(month: String, bytes: ByteArray) {
        shardFile(month)?.writeBytes(bytes)
    }

    /** 分片是否已下载且内容与 meta 里的哈希一致 */
    fun isFresh(shard: GlobalShard): Boolean {
        val f = shardFile(shard.month) ?: return false
        if (!f.isFile) return false
        return hashOf(f.readBytes()) == shard.hash
    }

    fun downloadedMonths(): Set<String> =
        dir()?.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "meta.json" }
            ?.map { it.name.removeSuffix(".json") }?.toSet() ?: emptySet()

    fun deleteMonth(month: String) {
        shardFile(month)?.delete()
    }

    fun deleteYear(year: String) {
        dir()?.listFiles { f -> f.isFile && f.name.startsWith(year) && f.name != "meta.json" }
            ?.forEach { it.delete() }
    }

    fun hashOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)
}
