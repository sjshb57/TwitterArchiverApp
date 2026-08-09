package io.github.twitterarchiver.util

import io.github.twitterarchiver.data.Config

/** 把 index.json 里的相对路径（../image/x.jpg、../avatar/x.jpg）拼成完整 URL */
object MediaUtil {

    /**
     * 把 profile.json / index.json 里的相对路径收拾成安全的形式。
     *
     * 只 removePrefix("../") 挡不住 ../../x 或 a/../../b——前者只剥一层，
     * 后者根本不在开头。这里逐段过滤掉所有 .. 和 . ，再拼回去。
     */
    fun sanitizeRelPath(relPath: String): String =
        relPath.split('/')
            .filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/")


    /** 相对路径转完整 URL。基准是 wayback_snapshots/ */
    fun resolveAsset(repo: String, account: String, relPath: String): String {
        if (relPath.isBlank()) return ""
        if (relPath.startsWith("http")) return relPath
        // ../image/x.jpg -> {snapshotsBase}/image/x.jpg
        val cleaned = sanitizeRelPath(relPath)
        return "${Config.snapshotsBase(repo, account)}/$cleaned"
    }

    /** 解析推文里的所有图片为完整 URL */
    fun resolveImages(repo: String, account: String, images: List<String>): List<String> =
        images.map { resolveAsset(repo, account, it) }
}
