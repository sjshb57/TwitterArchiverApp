package io.github.twitterarchiver.data

/** 全局配置：组织、仓库、URL 拼接 */
object Config {
    const val ORG = "TwitterArchiver"
    const val ORG_LOWER = "twitterarchiver"

    fun apiOrgRepos(page: Int) =
        "https://api.github.com/orgs/$ORG/repos?per_page=100&page=$page&type=all"

    // GitHub Pages 门面
    const val PAGES_BASE = "https://$ORG_LOWER.github.io"
    const val HOME_BASE = "$PAGES_BASE/home"

    // GitHub API
    const val API_BASE = "https://api.github.com"

    // 存档申请收件仓库（访客提交 Issue，管理员接收）
    const val REQUESTS_OWNER = "sjshb57"
    const val REQUESTS_REPO = "Test"

    // 门面数据
    fun reposJsonUrl() = "$HOME_BASE/repos.json"

    // 全站索引分片：服务端按月存，客户端按年用。
    fun globalIndexBase() = "$HOME_BASE/search-index"
    fun globalIndexMetaUrl() = "${globalIndexBase()}/meta.json"
    fun globalIndexShardUrl(month: String) = "${globalIndexBase()}/$month.json"
    fun timelineRecentUrl() = "$HOME_BASE/timeline-recent.json"
    fun crossRepliesUrl() = "$HOME_BASE/cross-replies.json"
    fun indexManifestUrl(repo: String) = "$HOME_BASE/manifest/$repo.json"
    // GitHub Actions 最近运行
    fun apiRepoRuns(repo: String) = "$API_BASE/repos/$ORG/$repo/actions/runs?per_page=20"
    fun apiCancelRun(repo: String, runId: Long) = "$API_BASE/repos/$ORG/$repo/actions/runs/$runId/cancel"
    fun apiRerunRun(repo: String, runId: Long) = "$API_BASE/repos/$ORG/$repo/actions/runs/$runId/rerun"

    // 从模板仓库创建新仓库（建档）
    const val TEMPLATE_REPO = "project-starter"
    fun apiGenerateRepo() = "https://api.github.com/repos/$ORG/$TEMPLATE_REPO/generate"

    /** reader 页 URL，带账号参数 ?a= 指定具体账号（一个 repo 可能多账号） */
    fun readerUrl(repo: String, account: String) = "$PAGES_BASE/$repo/?a=$account"

    fun snapshotsBase(repo: String, account: String) =
        "$PAGES_BASE/$repo/accounts/$account/wayback_snapshots"

    fun indexJsonUrl(repo: String, account: String) =
        "${snapshotsBase(repo, account)}/index.json"

    fun profileJsonUrl(repo: String, account: String) =
        "${snapshotsBase(repo, account)}/profile.json"

    fun tweetHtmlUrl(repo: String, account: String, file: String) =
        "${snapshotsBase(repo, account)}/html/$file"

    fun apiRepoContents(repo: String, path: String) =
        "$API_BASE/repos/$ORG/$repo/contents/$path"

    fun apiWorkflowDispatch(repo: String, workflow: String) =
        "$API_BASE/repos/$ORG/$repo/actions/workflows/$workflow/dispatches"

    fun apiRequestIssues() =
        "$API_BASE/repos/$REQUESTS_OWNER/$REQUESTS_REPO/issues"

    fun apiUser() = "$API_BASE/user"
}
