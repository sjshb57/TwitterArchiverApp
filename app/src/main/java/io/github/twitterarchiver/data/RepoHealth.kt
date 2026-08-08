package io.github.twitterarchiver.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** /orgs/{org}/repos 返回的仓库条目，只取健康检查用得上的字段 */
@Serializable
data class OrgRepo(
    val name: String = "",
    /** 单位 KB */
    val size: Long = 0,
    @SerialName("pushed_at") val pushedAt: String? = null,
    val archived: Boolean = false,
    val private: Boolean = false
)

/** 一个存档仓库的健康状况 */
data class RepoHealth(
    val name: String,
    val sizeKb: Long,
    val daysSincePush: Int?,
    val overdue: Boolean,
    val private: Boolean = false
) {
    val sizeMb: Double get() = sizeKb / 1024.0
    /** GitHub Pages 站点上限 1 GB，单仓库建议上限 5 GB */
    val sizeLevel: Int get() = when {
        sizeMb >= 2048 -> 2
        sizeMb >= 1024 -> 1
        else -> 0
    }
}

enum class HealthSort(val label: String) {
    STALE("更新"),
    SIZE("体积")
}

/** 从 Dispatcher 的 dispatch.yml 解析出的轮转配置 */
data class RotationConfig(
    val batch: Int,
    val runsPerDay: Int,
    val repoCount: Int
) {
    /** 转一圈需要的天数 */
    val cycleDays: Int
        get() = if (batch <= 0 || runsPerDay <= 0) 0
        else kotlin.math.ceil(repoCount.toDouble() / (batch * runsPerDay)).toInt()

    /** 超过一圈还没轮到就算异常，留 20% 余量 */
    val overdueDays: Int get() = (cycleDays * 1.2).toInt().coerceAtLeast(1)
}
