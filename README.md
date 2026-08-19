🌐 语言:
[中文](README.md) | [English](README_en.md)

> **声明**:本项目 **CodeQuest** 是基于 [LeetCode Epiphany](https://github.com/WenjunHuang/CodeEpiphany)(Apache License 2.0,© Wenjun Huang)的二次开发版本,修改遵循 Apache 2.0 开源。原项目版权归原作者所有,本 fork 的改动同样以 Apache 2.0 发布,详见 [LICENSE](LICENSE)。

# CodeQuest 🌟

[![CI](https://github.com/chen-xiaochen/CodeEpiphany/actions/workflows/ci-main.yml/badge.svg)](https://github.com/chen-xiaochen/CodeEpiphany/actions/workflows/ci-main.yml)
[![License](https://img.shields.io/github/license/chen-xiaochen/CodeEpiphany)](LICENSE)
[![GitHub issues](https://img.shields.io/github/issues/chen-xiaochen/CodeEpiphany)](https://github.com/chen-xiaochen/CodeEpiphany/issues)

**JetBrains IDE 多平台刷题插件**——查询题目、浏览题面、编写多解法、一键提交、统计复盘,六大 OJ 平台一个插件搞定。

自 v1.0.0 起,插件以 **Kotlin + Gradle** 全面重写:能力型 OJ SPI 分层架构、统一 JCEF 前端、517 个 Kotlin 测试 + 95 个前端测试守护,原 Scala/sbt 实现已整体移除。要求 IntelliJ Platform **2026.2+(build 262)**。

## 当前支持平台与功能矩阵

| 平台 | 登录 | 查题/题面 | 提交 | 竞赛 | 题解 |
|---|:---:|:---:|:---:|:---:|:---:|
| [LeetCode](https://leetcode.com) | ✅ | ✅ ¹ | ✅ | — | ✅ ² |
| [力扣 LeetCode CN](https://leetcode.cn) | ✅ | ✅ | ✅ | — | ✅ |
| [CodeForces](https://codeforces.com) | ✅ | ✅ | ✅ ³ | ✅ ⁴ | — |
| [AtCoder](https://atcoder.jp) | ✅ | ✅ ⁵ | ✅ ³ | — | — |
| [洛谷](https://www.luogu.com.cn) | ✅ | ✅ | ✅ ⁶ | — | ✅ ⁷ |
| [HackerRank](https://www.hackerrank.com) | ✅ | ✅ ⁸ | ✅ | — | — |

各平台登录形态统一为「粘贴 Cookie」:浏览器登录对应 OJ 后复制 Cookie 请求头,IDE 内首次操作时自动弹对话框粘贴;cookie 校验通过后持久化(PasswordSafe),重启免再登。能力边界如实标注:

1. leetcode.com 题目列表 GraphQL 要求登录(匿名返回 401,站点侧收紧);力扣CN 匿名可查。
2. leetcode.com 题解列表匿名 401(需登录);LeetCode 会员专属题解正文如实报错,不绕过付费墙。
3. 提交通道受 Cloudflare Turnstile 人机验证保护(CodeForces/AtCoder),插件以 JCEF 弹窗引导完成后继续提交。
4. CodeForces 竞赛支持列表/详情/赛前 24h 订阅提醒;「参加」经浏览器外链完成;排名无公开 API,不承诺。
5. AtCoder 官方无公开题目列表 API,列表数据来自 [kenkoooo](https://kenkoooo.com) 第三方镜像。
6. 洛谷提交可能触发易盾/图片验证码,本期如实报错兜底(不静默失败)。
7. 洛谷题解支持列表与应用内正文阅读;列表第二页及以后的楼层正文不可达(平台接口形态限制)。
8. HackerRank 题目列表匿名可查,题面详情需登录。

## 核心功能

- 🔤 **Challenge 工作区**:每道题可建多个解法(challenge),每解法独立语言/模板/入口文件,`begin/end` 区域标记界定提交代码;测试用例面板增删改查
- ⚡ **一键提交**:编辑器右键 *Submit to OJ* → 统一提交轮询状态机(指数退避)→ AC/失败气球通知 → 提交记录落库(含代码快照,回看不依赖工作区文件)
- 📖 **题面渲染**:HTML/Markdown 双管线,KaTeX 公式 + highlight.js 高亮;题面本地缓存,离线可回看
- 📊 **刷题统计**:汇总卡片(总数/AC 数/AC 率/连续天数)+ GitHub 风格年度热力图 + 30 天趋势条形图,提交后自动刷新
- 🔎 **跨题检索**:SQLite FTS5 全文检索(标题/题面/题解笔记三源命中标记)+ 标签 AND 筛选 + 平台过滤;无关键字时兼作本地题库浏览
- 📚 **题解阅读**:应用内阅读器(后退/前进/刷新/站外打开工具栏),力扣CN 匿名可用,洛谷登录后可用
- 🏆 **竞赛**:CodeForces 竞赛列表/详情/订阅提醒(赛前 24h 气球通知)

## 架构

单 Gradle 模块 + 严格包分层 + 能力型 OJ SPI(依赖方向 `ui → platform → spi → core`,`infra` 实现 `core` 仓库接口):

```
com.codequest
├── core        领域模型 + 用例服务 + 仓库接口(不依赖 IntelliJ)
├── spi         OJPlatform 能力接口 + 注册表(Extension Point)
├── platform    leetcode / codeforces / atcoder / luogu / hackerrank 平台适配器
├── infra       db(Exposed + SQLite)/ http(ktor)/ secrets / settings / messaging
├── ui          toolwindow 壳 + JCEF 桥 + actions + anti-bot 弹窗
frontend/       统一 webview(Vite + Vue3 + TS,六页独立 entry)
```

- **分层强制不靠自觉**:`./gradlew check` 的 `checkLayerDependencies` 静态扫描 import,反向/越层引用与平台子包互引即构建失败
- **数据走 HTTP、命令走桥**:UI 为 Swing 壳 + JCEF 内容区,页面数据经插件内 ResourceHttpServer 的 REST 端点(`/api/problems`、`/api/stats`、`/api/search` 等)拉取,JCEF 消息只留给命令/事件,消灭大数据量消息序列化卡顿

## 新增一个 OJ 平台(SPI 扩展指南)

平台按需组合实现能力接口(`AuthCapable` / `ProblemQueryCapable` / `SubmissionCapable` / `ContestCapable` / `SolutionViewCapable`),经 plugin.xml Extension Point 注册,`OJPlatformRegistry` 按能力查询——**core/ui 对新平台零感知**。M3 以 LeetCode 双站点实证,M4 四平台量产复验:core 零触碰,平均每平台约 8-9 个文件、千行量级。

1. 新建 `kotlin/src/main/kotlin/com/codequest/platform/<oj>/` 包:`XxxApi`(纯 HTTP 映射,可 MockEngine 单测)/ `XxxAuth` / `XxxProblemService` / `XxxSubmissionService`(只需实现 `fetchRemoteStatus()` 轮询钩子)/ `XxxLanguages` / `XxxPlatform`(装配根)
2. plugin.xml 声明两行:`ojPlatformProvider` + `ojAuthPromptProvider`
3. 用 ktor `MockEngine` 回放录制响应写 HTTP 层单测,不上真实网络

实证数据与已知偏差详见 [docs/spi-validation.md](docs/spi-validation.md)。

## 🛠️ 安装

要求 **IntelliJ Platform 2026.2+(build 262)**。本二开版本未上架 JetBrains Marketplace,请手动安装:

1. 从发布页下载 zip,或按[开发指南](#-开发)自行构建
2. 打开 JetBrains IDE,进入 **Settings → Plugins → ⚙️ → Install Plugin from Disk**
3. 选择下载的 zip,重启 IDE 激活插件

**数据库位置**:题目缓存/提交记录/统计等数据存于本地 SQLite,默认 `~/.codequest/codequest.db`,可在 **Settings → Tools → CodeQuest** 的「数据库文件夹」改为任意目录——多台 IDE(PyCharm/CLion/IDEA 等)指向同一目录即可共享刷题数据。旧 Scala 版(fork 0.1.0 及更早)数据不迁移。

## 💻 开发

环境:JDK 21 + Node.js 20+。

```bash
# 后端门禁:detekt + ktlint + 分层依赖检查 + 全部测试 + 打包
./gradlew check test buildPlugin          # 产物 build/distributions/codequest-<version>.zip

# 前端(先于 Gradle;构建产物由 copyWebviewDist 自动内嵌进插件 zip)
cd frontend
npm ci
npm test                                  # Vitest
npm run build                             # → frontend/dist/

# 沙箱运行(自动打开空项目触发插件启动)
./gradlew runIde

# Kotlin 代码格式化
./gradlew ktlintFormat
```

Gradle 不直接调用 npm:前端产物不入库,请**先 `npm run build` 再跑 Gradle**(缺失时构建给出可行动报错)。贡献指引见 [CONTRIBUTING.md](CONTRIBUTING.md),前端细节见 [frontend/README.md](frontend/README.md)。

## 🔗 了解更多

- **[SPI 实证验证结论](docs/spi-validation.md)**(新增 OJ 成本/分层零触碰/已知偏差)
- 手动验收清单:[M3 首平台](docs/m3-manual-checklist.md) / [M4 四平台](docs/m4-manual-checklist.md) / [M5 增强层](docs/m5-manual-checklist.md)
- **[发布清单](docs/release-checklist.md)** · **[贡献指引](CONTRIBUTING.md)**
- **[🐛 提交问题](https://github.com/chen-xiaochen/CodeEpiphany/issues)**

## 许可

[Apache License 2.0](LICENSE)。本项目为 [LeetCode Epiphany](https://github.com/WenjunHuang/CodeEpiphany)(Apache 2.0,© Wenjun Huang)的二开 fork,遵循 Apache 2.0 保留原项目版权与许可声明。

---
