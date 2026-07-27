package io.github.twitterarchiver.util

import io.github.twitterarchiver.data.Config

/** 把 index.json 里的相对路径（../image/x.jpg、../avatar/x.jpg）拼成完整 URL */
object MediaUtil {

    /** 相对路径转完整 URL。基准是 wayback_snapshots/ */
    fun resolveAsset(repo: String, account: String, relPath: String): String {
        if (relPath.isBlank()) return ""
        if (relPath.startsWith("http")) return relPath
        // ../image/x.jpg -> {snapshotsBase}/image/x.jpg
        val cleaned = relPath.removePrefix("../").removePrefix("./")
        return "${Config.snapshotsBase(repo, account)}/$cleaned"
    }

    /** 解析推文里的所有图片为完整 URL */
    fun resolveImages(repo: String, account: String, images: List<String>): List<String> =
        images.map { resolveAsset(repo, account, it) }
}
