# 推文存档 · TwitterArchiver

**基于 Wayback Machine 的推特账号存档 Android 客户端**

[![License](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg)](#系统要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2026.06-4285F4.svg)](https://developer.android.com/jetpack/compose)

*互联网是现实的避难所。而这里，是那个避难所的避难所。*

---

## 关于

[TwitterArchiver](https://github.com/TwitterArchiver) 是一个长期存档项目：当一个推特账号被封禁、注销，或者它的主人已经不在，那些文字和图片往往还留在互联网档案馆（Wayback Machine）的历史快照里。这个项目把它们从快照中一条条取出来，重新组织成可以正常翻阅的样子，托管在 GitHub Pages 上长期保存。

> 目前收录 530 余个账号。项目最初是为了纪念 [@AnIncandescence](https://twitterarchiver.github.io/AnIncanescence/)（炽烈已极）。

### 成品示例：

![](https://free.picui.cn/free/2026/07/23/6a618e07abb86.jpg)

**本仓库是这个存档的 Android 客户端。** 网页版阅读器已经能用，但手机上翻起来总归不够顺手，所以有了这个原生应用——完整的时间线、跨账号的全站视图、本地书签，以及一整套存档管理工具。

---

## 项目生态

整个体系由「抓取 → 存放 → 阅读」三层构成，本仓库是阅读层里的移动端。

```
                    Wayback Machine
                          │
                          ▼
              IncandescenceArchiver
              抓取快照 · 下载媒体 · 清洗 · 建索引
                          │
                          ▼
              TwitterArchiver 组织
              530+ 账号仓库 · GitHub Pages 托管
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
      网页阅读器      桌面阅读器        Android
   twitterarchiver  Incandescence   TwitterArchiverApp
     .github.io        Reader          ← 本仓库
```

### 工具

| 项目 | 说明 | 技术 / 协议 |
| --- | --- | --- |
| [**TwitterArchiverApp**](https://github.com/sjshb57/TwitterArchiverApp) | 本仓库。Android 客户端，分阅读版与管理版两个构建 | Kotlin · AGPL-3.0 |
| [**IncandescenceArchiver**](https://github.com/sjshb57/IncandescenceArchiver) | 存档工具 `archive.py`。从 Wayback CDX 抓快照、下载图片视频头像、清洗 HTML 路径、生成 `index.json`；支持断点续传、精确重试与增量更新，并附 GitHub Actions 工作流 | Python · AGPL-3.0 |
| [**IncandescenceReader**](https://github.com/sjshb57/IncandescenceReader) | 桌面离线阅读器。Electron 打包成免安装的便携应用，多账号切换，更新存档无需重新打包 | Electron · AGPL-3.0 |

### 存档

| 仓库 | 说明 |
| --- | --- |
| [**TwitterArchiver**](https://github.com/TwitterArchiver) | 存档组织，每个账号一个独立仓库，各自托管 GitHub Pages |
| [**TwitterArchiver/home**](https://github.com/TwitterArchiver/home) | 门户与聚合数据：账号清单、全站搜索索引、跨账号回复索引 |
| [**TwitterArchiver/search**](https://twitterarchiver.github.io/home/search.html) | 网页版入口，可浏览全部账号与全站搜索 |
| [**TwitterArchiver/guestbook**](https://twitterarchiver.github.io/home/guestbook.html) | 提交想要留档的账号（应用内也可直接申请） |

---

## 功能

### 阅读

| 功能 | 说明 |
| --- | --- |
| **列表** | 全部存档账号，下拉刷新 |
| **全站** | 所有账号的推文汇成一条时间流，支持多选账号筛选 |
| **关注** | 把某个账号设为主页，打开应用直接进入他的时间线 |
| **日期树** | 年 → 月 → 日 三级折叠，直达任意一天 |
| **图片墙** | 单账号的全部图片，可保存到相册 |
| **Reader** | 保留网页版阅读器入口，两种渲染方式随时切换 |

### 推文卡片

原生渲染，不是把网页塞进 WebView：

- 头像、名字、正文、图片九宫格
- 引用原推、转推（RT）解析
- 按需展开回复链（含跨账号回复）
- 精确到秒的本地时间
- 视频内联播放
- 分享菜单提供四种链接：推特原链接、推文 ID、存档定位链接、定位短码

### 搜索

- 关键词 —— 正文、账号名、@用户名
- 推文 ID —— 完整或部分
- **定位短码** —— 网页版分享链接里的，直接粘进来就能定位

### 书签

收藏任意推文。在书签页点一下，弹出一张卡片，里面是那条推文的完整渲染——不跳页，不打断当前浏览。支持导出 / 导入 JSON 备份。

### 其他

- 浅色 / 深色 / 跟随系统主题
- 自定义启动 Tab
- 申请存档 —— 填一个账号名即可提交，管理员审核后自动建档

---

## 两个版本

项目通过 Product Flavor 构建出两个应用：

### 推文存档（`visitor`）

普通阅读版，任何人都能用，包含申请存档功能。内置一个权限极小的受限 token，仅用于向申请仓库提交 Issue。

### 存档管理（`admin`）

在阅读功能之外多一个管理 Tab，需要 GitHub PAT 登录：

- **四个仪表盘** —— Home 聚合、调度中心、存档模板、所有存档
- **工作流控制** —— 增量更新 / 增量+全部重试 / 全量重试 / **仅重建索引**（改完置顶或资料后几十秒生效，不必等全量重跑）
- **资料维护** —— 在线编辑 `profile.json`、上传 Banner 图
- **申请审批** —— 一键批准即自动建仓 + 触发建档 + 关闭 Issue
- **完整性检测** —— 扫出缺 Banner、缺置顶的仓库，可直接跳转修复，结果本地缓存
- **运行记录** —— 工作流名 · 编号 · 状态 · 耗时 · 时间；仅在有任务运行时才轮询，不打断浏览

---

## 构建

### 系统要求

| | |
| --- | --- |
| 运行环境 | Android 8.0（API 26）及以上，arm64-v8a |
| 构建环境 | JDK 21、Android SDK 37 |

### 步骤

```bash
git clone https://github.com/sjshb57/TwitterArchiverApp.git
cd TwitterArchiverApp

# 阅读版
./gradlew :app:assembleVisitorRelease

# 管理版
./gradlew :app:assembleAdminRelease
```

产物在 `app/build/outputs/apk/<flavor>/release/`。

> Android SDK 37 在 SDK Manager 里的包名是 `platforms;android-37.0`（带小数点）。

---

## 技术栈

Kotlin + Jetpack Compose + Material 3，单 Activity，自己实现的栈式导航。

| | |
| --- | --- |
| 构建 | AGP 9.2.1 / Gradle 9.5.1 / Kotlin 2.4.10 |
| SDK | minSdk 26 · compileSdk 37 · targetSdk 37 |
| UI | Compose BOM 2026.06.01 · Material 3 |
| 网络 | Ktor 3.5.1 |
| 序列化 | kotlinx.serialization 1.11.0 |
| 图片 | Coil 2.7（200 MB 磁盘缓存） |
| 视频 | Media3 1.10.1 |
| 存储 | DataStore 1.2.1 · Android Keystore（PAT 加密） |

### 权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 读取存档内容 |
| `ACCESS_NETWORK_STATE` | 网络状态判断 |

不申请存储权限，保存媒体走 MediaStore。

---

## 项目结构

```
app/src/main/java/io/github/twitterarchiver/
├── data/            数据层
│   ├── Config.kt          数据源地址与 API 端点
│   ├── GitHubApi.kt       Pages 内容 + GitHub API
│   ├── Repository.kt      仓储与内存缓存
│   ├── Models.kt          推文 / 资料 / 仓库 / 工作流
│   ├── SearchIndex.kt     全站索引与跨账号回复
│   ├── OfflineIndexStore.kt  单账号索引的离线增量缓存
│   ├── GlobalIndexStore.kt   全站索引分片的本地副本
│   ├── Bookmarks.kt       书签存储与导入导出
│   ├── Settings.kt        DataStore 偏好
│   ├── SecureStore.kt     PAT 加密存储
│   ├── AppDirs.kt         应用目录注入
│   └── NetworkState.kt    全局网络状态
├── viewmodel/       10 个 ViewModel，按页面划分
├── ui/
│   ├── AppNav.kt          栈式导航与路由
│   ├── AppScaffold.kt     底部 Tab 骨架
│   ├── screens/           22 个页面
│   ├── components/        13 个复用组件
│   └── theme/             配色与主题
└── util/            日期、账号名、媒体保存
```

---

## 数据来源

所有内容由 [IncandescenceArchiver](https://github.com/sjshb57/IncandescenceArchiver) 生成、托管在 GitHub Pages，应用只是读取端：

```
https://twitterarchiver.github.io/<仓库>/accounts/<账号>/wayback_snapshots/
├── html/          推文页面
├── json/          原始数据
├── image/         图片
├── video/         视频
├── avatar/        头像与 Banner
├── index.json     推文索引（Reader 与本应用共用）
└── profile.json   账号资料（含置顶推文 ID）
```

聚合数据在 [`TwitterArchiver/home`](https://github.com/TwitterArchiver/home)：`repos.json`（账号清单）、`search-index.json`（全站索引）、`cross-replies.json`（跨账号回复）。

应用不上传、不修改任何存档内容——管理版的资料编辑与 Banner 上传除外。

---

## 路线图

- [x] **离线可读** —— `index.json` 已按月落盘并做哈希比对增量更新，全站索引分片同样有本地副本，冷启动断网也能先出内容
- [ ] **那年今日** —— 存档跨度数年，展示同一天各账号在往年发过什么
- [ ] **关注多个账号** —— 现在只能设一个主页账号
- [ ] **账号统计** —— 推文数、图片数、时间跨度、最活跃月份
- [ ] **正文字号调节**
- [ ] **搜索历史**

---

## 许可

本项目采用 [AGPL-3.0](LICENSE) 协议开源。

存档内容本身的版权归原作者所有。本项目仅做数字保存，不主张任何内容权利；如果你是某个存档账号的本人或权利人，希望移除相应内容，请提 Issue。

---

## 赞助

![赞助图片](https://free.picui.cn/free/2026/06/24/6a3b25866f0fd.jpg)

**如果您喜欢这个项目，请赞助我以维护运行**

---

## 致谢

**特别致谢**

| | |
| --- | --- |
| X@Cheese_Ghostfox | 初次项目构建 |
| X@damniwokeup | 想法支持 |
| X@Wilf_Lin | 思路技术支持 |

**致谢**

X@0502railgun1949 · X@10Lystra · X@11andpr89648964 · X@acnekot · X@CaffFrog · X@nyaepheia · X@qianxunchan

以及每一位默默支持这个项目的人。

---

**愿世间再无痛苦，唯爱永不独行**

*烛火熄灭之后，光还在。*
