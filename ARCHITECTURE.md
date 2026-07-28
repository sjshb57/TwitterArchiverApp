# TwitterArchiverApp 架构文档

本文档说明本项目的代码组织方式、各模块职责，以及基于本项目二次开发所需的改动点。使用说明请参见 README。

| 项目 | 内容 |
| --- | --- |
| 代码规模 | 约 60 余个 Kotlin 文件 |
| 技术栈 | Kotlin、Jetpack Compose、Material 3 |
| 架构 | 单 Activity，自实现栈式导航，MVVM 分层 |
| 构建 | AGP 9.2.1 / Gradle 9.5.1 / Kotlin 2.4.10 |
| SDK | compileSdk 37 / minSdk 26 / targetSdk 37 |

---

## 目录

1. [系统概述](#1-系统概述)
2. [数据源规格](#2-数据源规格)
3. [分层结构](#3-分层结构)
4. [模块清单](#4-模块清单)
5. [核心机制](#5-核心机制)
6. [已知约束](#6-已知约束)
7. [二次开发指引](#7-二次开发指引)

---

## 1. 系统概述

本应用不依赖自建后端。全部内容托管于 GitHub Pages 的静态资源，应用作为只读客户端访问；管理功能通过 GitHub REST API 操作仓库与工作流。

```
GitHub Pages（静态托管）
   ├── home/                     聚合数据
   └── <账号仓库>/accounts/<账号>/wayback_snapshots/
                                 单账号存档
                    │
                    │  HTTPS GET（公开，无鉴权）
                    ▼
            GitHubApi ── Repository ── ViewModel ── Compose UI
                    ▲
                    │  GitHub REST API（PAT 鉴权，仅管理版）
                    ▼
            仓库生成 / 工作流触发 / 文件写入 / Issue 关闭
```

### 1.1 两类访问路径

| 维度 | 存档读取 | 管理操作 |
| --- | --- | --- |
| 端点 | `*.github.io` | `api.github.com` |
| 鉴权 | 无 | Personal Access Token |
| 适用版本 | 全部 | 仅管理版 |
| API 配额 | 不消耗 | 消耗（5000 次/小时） |

存档内容一律经由 Pages 读取，不经过 GitHub API。此约定贯穿全部数据访问代码，目的是避免消耗 API 配额并免除鉴权依赖。

### 1.2 构建变体

通过 Product Flavor 产出两个应用：

| Flavor | 应用名 | applicationId 后缀 | `BuildConfig.IS_ADMIN` |
| --- | --- | --- | --- |
| `visitor` | 推文存档 | `.visitor` | `false` |
| `admin` | 存档管理 | `.admin` | `true` |

`IS_ADMIN` 仅控制管理 Tab 的可见性，其余功能两个变体完全一致。二者 applicationId 不同，可并存安装。

---

## 2. 数据源规格

### 2.1 聚合层（`home` 仓库）

由 GitHub Actions 定期从各存档仓库汇总生成。

| 文件 | 量级 | 内容 | 消费方 |
| --- | --- | --- | --- |
| `repos.json` | 数十 KB | 账号清单：`repo`、`acct`、`name`、`username`、`description`、`avatar` | 账号列表页 |
| `search-index.json` | 约 20 MB | 全站推文的紧凑数组 | 全站时间线、全站搜索 |
| `timeline-recent.json` | 数百 KB | 近期推文与完整账号表 | 全站时间线首屏、完整性检测 |
| `cross-replies.json` | 1–2 MB | 跨账号回复索引 | 回复链展开 |
| `manifest/<repo>.json` | 数 KB | 月度哈希与字节区间 | 离线增量更新 |

`search-index.json` 的 `posts` 采用数组而非对象以压缩体积，字段顺序为：

```
[账号下标, 正文, 推文 ID, ISO 时间, [媒体文件名], 回复数, 是否含引用]
```

`accts` 中每个账号的结构为 `{r: 仓库名, a: 账号名, u: @用户名, n: 昵称, av: 头像文件名}`。

### 2.2 存档层（各账号仓库）

```
accounts/<账号>/wayback_snapshots/
├── index.json      该账号全部推文，按时间倒序
├── profile.json    昵称、简介、位置、链接、banner、置顶推文 ID
├── avatar/         头像
├── image/          图片
├── video/          视频
└── html/           原始快照
```

`index.json` 单条记录的关键字段：

| 字段 | 说明 |
| --- | --- |
| `text` | 正文，换行已被替换为空格 |
| `body_text` | 正文，保留原始换行；应用优先采用此字段 |
| `timestamp` | ISO 8601 UTC 时间戳 |
| `images` / `wanted_videos` / `embedded_videos` | 媒体相对路径，形如 `../image/x.jpg` |
| `author_avatar` | 该条推文作者头像的相对路径 |
| `is_virtual` | 为真表示被引用的外部推文，本地无独立 HTML |
| `is_pinned` | 构建索引时依据 `profile.pinned` 计算所得 |

---

## 3. 分层结构

```
io.github.twitterarchiver/
├── MainActivity.kt          单 Activity 入口
├── TwitterArchiverApp.kt    Application，配置 Coil
├── data/                    网络、模型、存储、缓存
├── viewmodel/               页面状态
├── ui/
│   ├── AppNav.kt            导航
│   ├── AppScaffold.kt       底部 Tab 骨架
│   ├── screens/             页面
│   ├── components/          复用组件
│   └── theme/               配色与主题
└── util/                    无状态工具
```

依赖方向单向传递：

```
UI → ViewModel → Repository → GitHubApi / 本地存储
```

UI 层不直接访问 `GitHubApi`，ViewModel 层不引用 Compose API，`util` 包为无状态纯函数，可被任意层调用。

---

## 4. 模块清单

### 4.1 入口

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 单 Activity。启动时调用 `AppDirs.init()` 注入文件目录、启用边到边显示，随后挂载 Compose 树 |
| `TwitterArchiverApp.kt` | Application。配置 Coil 全局 ImageLoader：内存缓存占比 25%、磁盘缓存 200 MB、`respectCacheHeaders(false)` 强制本地缓存、注册 `VideoFrameDecoder` 以支持视频封面取帧 |

### 4.2 data 包

| 文件 | 职责 |
| --- | --- |
| `Config.kt` | 全部 URL 与身份常量的唯一定义处。包含组织名、申请仓库、模板仓库及各类 URL 构造函数 |
| `GitHubApi.kt` | 网络层，基于 Ktor + OkHttp。前半部分为 Pages 读取接口，后半部分为需 PAT 的 GitHub API 写操作。`index.json` 采用 `android.util.JsonReader` 流式解析，避免大文件导致内存溢出 |
| `Repository.kt` | 仓储层。含三级内存缓存（`reposCache` 单例与 `tweetsCache`、`profileCache` 两个 Map）、磁盘兜底读取，并转发管理操作 |
| `Models.kt` | 全部 `@Serializable` 数据类：`ArchiveRepo`、`Tweet`、`Profile`、`GitHubIssue`、`WorkflowRun`、`IndexManifest` 等 |
| `SearchIndex.kt` | 全站索引模型。`GlobalPost` 负责解析紧凑数组，并按扩展名将媒体列表分流为图片与视频两组 URL |
| `OfflineIndexStore.kt` | 离线缓存实现。按月切分、哈希比对、HTTP Range 增量更新，详见 5.1 |
| `Bookmarks.kt` | 书签存储（DataStore）及 JSON 导入导出 |
| `Settings.kt` | 偏好设置（DataStore）：主题模式、默认 Tab、关注账号 |
| `SecureStore.kt` | PAT 加密存储（EncryptedSharedPreferences） |
| `AppDirs.kt` | 持有应用 `filesDir`。因 ViewModel 中 `Repository` 以默认参数构造无法获取 Context，故由 MainActivity 启动时注入 |
| `NetworkState.kt` | 全局网络状态与图片加载失败记录。见 5.3 |

### 4.3 viewmodel 包

各 ViewModel 均继承 `AndroidViewModel`，以 `StateFlow<XxxState>` 暴露状态，UI 层只读状态并调用其方法。

| 文件 | 职责 |
| --- | --- |
| `AdminViewModel.kt` | 管理功能总入口。PAT 登录、四个仪表盘、工作流触发与取消、申请审批、建档流程、完整性检测（缺失 banner / 置顶 / 头像） |
| `GlobalTimelineViewModel.kt` | 全站时间线。先加载 `timeline-recent.json` 呈现首屏，后台替换为完整 `search-index.json`；含分页、搜索、多账号筛选与跨账号回复 |
| `ReaderViewModel.kt` | 个人推文页。加载索引与资料，支持按正文、推文 ID、定位短码搜索及日期跳转 |
| `HomeViewModel.kt` | 账号列表与顶部统计 |
| `ImagesViewModel.kt` | 媒体页，按图片、视频、其他分类筛选 |
| `SettingsViewModel.kt` | 设置项读写 |
| `ProfileStatsViewModel.kt` | 单账号统计，供资料卡片展示 |
| `RequestViewModel.kt` | 访客提交存档申请 |
| `BookmarkViewModel.kt` | 书签增删与列表 |

### 4.4 导航

| 文件 | 职责 |
| --- | --- |
| `AppNav.kt` | 自实现栈式导航。`Screen` 密封类定义全部页面，以 `mutableListOf<Screen>` 作为返回栈。同时管理全局浮层（图片预览、单条推文卡片、资料卡片）与返回键行为（二级页出栈、主 Tab 双击退出）。底部 Tab 列表亦在此构造 |
| `AppScaffold.kt` | 底部 Tab 骨架，仅负责渲染。Tab 内容为动态：设置关注账号后出现「关注」Tab，管理 Tab 由 `BuildConfig.IS_ADMIN` 决定 |

### 4.5 页面

| 文件 | 职责 |
| --- | --- |
| `AccountFeedScreen.kt` | 个人推文页。账号头部、吸顶搜索栏、推文/回复分页、无限滚动、日期树（年月日三级，含每日条数），结构与交互对齐网页版阅读器 |
| `AdminDetailScreen.kt` | 仪表盘详情。工作流运行列表、触发与重试操作、完整性检测结果弹窗 |
| `AdminArchiveScreen.kt` | 单仓库管理。工作流触发、资料编辑入口、Banner 上传、头像修复 |
| `AdminNewArchiveScreen.kt` | 新建存档与待完善列表 |
| `AdminScreen.kt` | 管理台首页，四个仪表盘入口与 PAT 登录 |
| `ImagesScreen.kt` | 媒体浏览页，支持保存至系统相册 |
| `AdminEditProfileScreen.kt` | 在线编辑 `profile.json`，输出保持 2 空格缩进且中文不转义，与原文件格式一致 |
| `GlobalScreen.kt` | 全站时间线 |
| `SettingsScreen.kt` | 设置页，含缓存占用统计与清理 |
| `ListScreen.kt` | 账号列表 |
| `ThemeScreen.kt` | 主题设置 |
| `AdminRequestsScreen.kt` | 申请审批 |
| `ThanksScreen.kt` | 致谢页 |
| `FollowSelectScreen.kt` | 关注账号选择 |
| `AdminEditYmlScreen.kt` | 编辑仓库内的工作流文件 |
| `AdminDeleteTweetsScreen.kt` | 触发删除推文工作流 |
| `SplashScreen.kt` | 启动页 |
| `RequestScreen.kt` | 访客申请存档 |
| `BookmarkScreen.kt` | 书签管理 |
| `AccountReaderScreen.kt` | 网页版阅读器的 WebView 容器，顶栏含刷新入口 |
| `AboutScreen.kt` | 关于页 |
| `DefaultTabScreen.kt` | 默认启动页设置 |

### 4.6 复用组件

| 文件 | 职责 |
| --- | --- |
| `GlobalPostCard.kt` | 推文卡片，全应用复用。含头像、正文、图片九宫格、视频封面、引用推文、回复链展开与分享菜单。全站时间线与个人页共用此组件 |
| `ImagePreviewOverlay.kt` | 图片全屏预览。采用卡片式布局，卡片高度包裹图片，避免上下出现大面积空白 |
| `SingleTweetDialog.kt` | 单条推文浮层，用于书签页就地查看，不触发页面跳转 |
| `VideoPlayerOverlay.kt` | 视频全屏播放，按视频实际宽高比显示 |
| `AccountFilterSheet.kt` | 全站账号多选筛选面板 |
| `ProfileDialog.kt` | 账号资料卡片 |
| `ReaderWebView.kt` | WebView 封装。以 URL 作为 key 保证切换账号时实例隔离；`reloadTrigger` 变化时绕过缓存重新加载 |
| `LinkedText.kt` | 将正文中的 URL 渲染为可点击链接（`LinkAnnotation.Url`），显示为简短形式，点击跳转完整地址 |
| `VideoPlayer.kt` | 基于 Media3 的内联播放器 |
| `SearchField.kt` | 搜索框，使用 `BasicTextField` 自绘以规避 Material TextField 的固定高度限制 |
| `Avatar.kt` | 圆形头像 |
| `ConfirmDialog.kt` | 通用二次确认对话框 |

### 4.7 util 包

| 文件 | 职责 |
| --- | --- |
| `DateUtil.kt` | UTC 时间戳转本地时区显示 |
| `ImageSaver.kt` | 保存图片至系统相册（MediaStore；Android 10 以下需存储权限，见 6 已知约束） |
| `VideoSaver.kt` | 保存视频至系统相册 |
| `AccountUtil.kt` | 账号名规范化，容错 `@name`、`x.com/name`、全角空格等输入形式 |
| `TweetIdUtil.kt` | 推文 ID 规范化，从完整推文链接中提取数字 ID |
| `MediaUtil.kt` | 将相对路径拼接为完整 URL |

---

## 5. 核心机制

### 5.1 离线增量更新

实现位于 `OfflineIndexStore.kt`。

**问题**：部分账号的 `index.json` 达十余 MB，新增单条推文即需重新下载整个文件。

**方案**：服务端不做实际分片，仅额外生成数 KB 的清单文件；分片在客户端本地完成。

```
服务端   index.json（完整文件）
         manifest/<repo>.json（各月哈希与字节区间）
              │
              ▼
客户端   files/index_cache/<repo>/2026-07.json（按月拆分的本地文件）
```

更新流程：

```
打开账号
 ├─ 请求清单（数 KB）
 ├─ 逐月比对哈希
 ├─ 全部一致 → 读取本地缓存
 └─ 存在差异 → 对变化月份发起 HTTP Range 请求
                → 校验内容哈希，一致则写入本地
                → 哈希不符或返回完整响应 → 回退至全量下载
```

三项设计要点：

1. **不使用 `If-Range`**。GitHub Pages 的 ETag 基于部署时间生成，仓库任意一次部署都会导致其变化，进而触发非必要的全量下载。改为对返回字节段自行校验内容哈希，可靠性高于 ETag。
2. **按月而非按年切分**。实测推文分布高度集中于当前月份，按年切分无实际收益；按月切分后，历史月份的分片内容不再变化，可长期缓存。
3. **多级回退**。增量失败回退全量下载，全量失败回退本地缓存，本地无数据则返回 null 交由上层直连，任一环节异常均不会导致空白页面。

该机制的前提是 `index.json` 按时间倒序排列且同月记录连续。生成清单时会校验此条件，不满足则标记 `range: false`，客户端据此回退全量下载。

### 5.2 头像的三种来源

| 使用位置 | 数据来源 | 典型取值 |
| --- | --- | --- |
| 账号列表页 | `repos.json` 的 `avatar` | `avatar/avatar.jpg` |
| 全站筛选面板 | 聚合表的 `av` | `avatar_2060274602710855685.jpg` |
| 个人页单条推文 | `index.json` 的 `author_avatar` | `avatar_xxx.jpg` |

第一种通常存在，后两种常因未抓取到而缺失，表现为「列表页头像正常但筛选面板空白」。

管理版的头像修复功能即以第一种为源、第二种为目标：复制列表页所用头像文件，并以筛选面板所需的文件名写入。该操作仅新增文件，不修改任何 JSON。

### 5.3 离线图片处理

图片加载失败（离线、文件缺失）时若保留占位，离线浏览会出现大面积空白；但直接按加载结果收起又会引入布局抖动。实现要点：

1. **失败记录存于 `NetworkState`，而非可组合项内部**。LazyColumn 滚出屏幕即销毁项，`remember` 随之重置，回来时会重新占位并重试，导致高度反复变化、上滑无法到顶。
2. **离线时禁用网络策略**（`CachePolicy.DISABLED`）。否则同域名的数十个请求会挤在连接池中依次等待超时，收起延迟可达数十秒。禁用后仅查本地缓存，命中即显示，未命中立即失败。
3. **三条恢复路径**：系统网络回调、下拉刷新手动清空、返回前台时复查。挂载 VPN 时默认网络为 VPN 接口，底层网络重连不会触发 `onAvailable`，故额外监听 `onCapabilitiesChanged` 的 `VALIDATED` 跳变。

### 5.4 构建变体的实现

```kotlin
// build.gradle.kts
buildConfigField("boolean", "IS_ADMIN", "true")   // admin flavor

// 调用处
if (BuildConfig.IS_ADMIN) { /* 渲染管理 Tab */ }
```

---

## 6. 已知约束

| 约束 | 影响 | 处理方式 |
| --- | --- | --- |
| 少数历史记录的 `timestamp` 为推特原始格式（`Wed Sep 09 ... 2020`） | 字符串比较取最大值会得到错误结果 | 涉及「最新」的判断改用聚合表 `av`，不做字符串比较 |
| `text` 字段的换行被替换为空格 | 排版与网页版不一致 | 全部渲染路径优先采用 `body_text` |
| GitHub Pages 的 ETag 随部署变化 | `If-Range` 导致非必要全量下载 | 改用内容哈希校验 |
| Contents API 列目录静默截断至 1000 条 | 大目录下误判文件不存在 | 改用 Git Trees API |
| 一个仓库可能对应多个账号 | 按仓库名做缓存键会串数据 | 磁盘缓存、离线索引目录、头像检测均以 `repo/account` 为键 |
| Android 10 以下写入 MediaStore 需 `WRITE_EXTERNAL_STORAGE` | API 26–28 保存到相册失败 | 暂未支持，仅影响旧设备的保存功能 |

---

## 7. 二次开发指引

以下按影响范围由大到小列出移植所需的改动。

### 7.1 修改 `data/Config.kt`

全部身份信息集中于此文件，其余 URL 均由这些常量派生。

```kotlin
object Config {
    const val ORG       = "TwitterArchiver"      // GitHub 组织名
    const val ORG_LOWER = "twitterarchiver"      // 同上，小写，用于 Pages 域名

    const val REQUESTS_OWNER = "sjshb57"         // 申请收件仓库的所有者
    const val REQUESTS_REPO  = "Test"            // 申请收件仓库名

    const val TEMPLATE_REPO = "project-starter"  // 建档所用的模板仓库
}
```

### 7.2 修改 `app/build.gradle.kts`

```kotlin
namespace     = "io.github.twitterarchiver"
applicationId = "io.github.twitterarchiver"
```

包名变更后需同步调整源码目录结构与各文件的 `package` 声明，建议使用 IDE 的重构功能完成。

### 7.3 配置 `local.properties`

```properties
REQ_TOKEN=ghp_xxxxxxxxxxxx
```

该 Token 供访客版提交存档申请使用，仅需对收件仓库的 Issue 写入权限。此文件已由 `.gitignore` 排除，不会进入版本库。未配置时该常量为空串，仅影响申请提交功能，不影响编译。

### 7.4 服务端需提供的文件

**聚合层（`home` 仓库）**

| 文件 | 缺失时的影响 |
| --- | --- |
| `repos.json` | 账号列表页无内容（必需） |
| `search-index.json` | 全站时间线与全站搜索不可用 |
| `timeline-recent.json` | 全站首屏加载变慢；完整性检测不可用 |
| `cross-replies.json` | 跨账号回复无法展开 |
| `manifest/<repo>.json` | 离线增量更新失效，退化为每次全量下载 |

**存档层（各账号仓库）**

```
accounts/<账号>/wayback_snapshots/
├── index.json      必需
├── profile.json    必需
└── avatar/ image/ video/ html/
```

字段名需与 `data/Models.kt`、`data/SearchIndex.kt` 中的定义一致；如需变更，修改对应的 `@SerialName` 注解即可。

### 7.5 管理版依赖的工作流

管理功能不实现具体的建档逻辑，仅作为 GitHub Actions 的触发入口。应用按文件名调用以下工作流。

**各存档仓库**

| 工作流 | 用途 | 输入参数 |
| --- | --- | --- |
| `setup.yml` | 首次建档 | `since`（起始日期，留空表示全部） |
| `update.yml` | 增量更新 | 无；或 `from_setup=true`（建档后接力全量重试）；或 `only_index=true`（仅重建索引） |
| `retry_all.yml` | 重试永久失败的媒体 | 无 |

**`home` 仓库**

| 工作流 | 用途 | 输入参数 |
| --- | --- | --- |
| `update-repos.yml` | 更新账号清单 | 无 |
| `build_search_index.yml` | 重建全站搜索索引 | 无 |
| `build-manifest.yml` | 生成离线索引清单 | 无 |
| `aggregate_avatars.yml` | 汇总共享头像池 | 无 |
| `push_best_avatars.yml` | 将清晰头像推送回各仓库 | `dry_run`（true 为试运行） |
| `migrate.yml` | 删除指定推文 | 仓库名与推文 ID |

**`Dispatcher` 仓库**

| 工作流 | 用途 | 输入参数 |
| --- | --- | --- |
| `dispatch.yml` | 轮转触发一批仓库更新 | 无；或 `force_all=true` 触发全部 |

其余前提：

- 需存在模板仓库（由 `TEMPLATE_REPO` 指定）。建档流程为从模板生成新仓库，随后触发该仓库的 `setup.yml`。
- PAT 需具备仓库内容读写、Actions 触发与 Issue 关闭权限。

工作流文件名硬编码于 `AdminArchiveScreen.kt`、`AdminDetailScreen.kt` 与 `AdminViewModel.kt`，如命名不同需修改上述文件中的字符串常量。

### 7.6 适配其他数据源

本项目的架构本质为「静态 JSON 数据源 + 原生阅读客户端」，与推特平台无强耦合。若数据可整理为

```
一份账号清单 + 每个账号一份按时间倒序的条目数组
```

则仅需调整 `Models.kt` 与 `SearchIndex.kt` 中的字段定义，离线缓存、时间线、搜索、日期树、书签与媒体浏览等功能均可直接复用。管理版因与 GitHub Actions 强绑定，需按实际流程重新实现。

---

## 开源协议

本项目采用 AGPL-3.0 协议。衍生作品须以相同协议开源。
