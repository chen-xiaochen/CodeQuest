---
title: CodeQuest 插件 Kotlin 推倒重写设计
date: 2026-08-14
status: plan
source: 用户重构需求（brainstorming 会话）
scope: 插件全量重写（架构 / 数据 / UI / 平台适配）
---

# CodeQuest 插件 Kotlin 推倒重写设计

## 背景与动机

CodeQuest（原 CodeEpiphany fork）是 JetBrains IDE 的多平台刷题插件，现有实现为 Scala + sbt，约 315 个 Scala 文件，支持 6 个 OJ 平台。经确认，本次完全重构的驱动力（按重要性）：

1. **加新 OJ 太痛苦**：7 个平台各自复制一套 Api/Auth/SubmissionService，`AuthService` 用 `CodeDojo` 枚举 match 硬分发；上轮 loop 的「OJ 公共基类抽象」(WP5) 仅 partial
2. **代码可维护性差**：包结构混乱、职责互相渗透、Scala/Java 混用、GUI Designer 生成代码
3. **用户体验/界面**：4 个独立 Vite 前端应用、JCEF 桥协议不统一
4. **稳定性**：登录态、Cloudflare、提交轮询等运行时问题

## 已确认的约束与决策

| 维度 | 决定 |
|---|---|
| 执行形态 | **推倒重写**，在本仓库进行；旧 Scala 代码保留作参考，M6 一次性删除 |
| 语言 | **Kotlin**（构建从 sbt 迁移到 Gradle） |
| 功能范围 | **全量对齐**现有版：查题/题面/提交/多解法/测试用例/统计/竞赛/跨题检索/题解 |
| 数据兼容 | **不迁移**旧 SQLite 数据，全新数据库 |
| UI 架构 | **JCEF 统一前端**（一个 Vite/TS Vue3 应用，多页面路由） |
| 平台范围 | **6 OJ 全量**：LeetCode / 力扣CN / CodeForces / AtCoder / 洛谷 / HackerRank；**牛客砍掉，不做 stub** |
| IDE 版本 | **仅 2025.2+ (252)**，无多版本兼容层 |
| 架构方案 | **方案 A：分层单体 + 能力型 OJ SPI**（否决多 Gradle 模块化与微内核开放生态） |

## 架构方案选型

- **方案 A（选定）**：单 Gradle 模块 + 严格包分层 + 能力型 OJ SPI（IntelliJ EP 注册）。拿到多模块方案 90% 的隔离收益而构建配置成本仅 10%
- 方案 B（否决）：每 OJ 独立 Gradle 模块，编译期强制隔离——构建/版本对齐成本高，插件单产物交付场景收益有限
- 方案 C（否决）：微内核全 EP 化面向第三方插件生态——无第三方消费者，YAGNI；API 逆向本就是每平台最难部分，抽象过度反而增加成本

## §1 总体架构与技术栈

**构建**：Gradle 8 + intellij-platform-gradle-plugin 2.x，Kotlin 2.x（JVM），仅目标 2025.2 (252)。旧 jOOQ codegen、sbt 脚本、GUI Designer `.form` 全部废弃。

**技术栈映射**（旧 → 新）：

| 关注点 | 旧 | 新 |
|---|---|---|
| 并发/异步 | cats-effect `IO` / `Stream[IO]` | Kotlin coroutines + `Flow` |
| HTTP | 自封装 HttpClientManager | ktor-client（OkHttp 引擎）+ CookieStorage 统一会话管理 |
| JSON | circe | kotlinx.serialization |
| 数据库 | jOOQ + SQLite | Exposed + JDBC SQLite，DAO 模式 |
| UI | Swing + GUI Designer 混合 | Swing（Kotlin UI DSL，仅工具窗口壳）+ JCEF 内容区 |
| 前端 | 4 个独立 Vite 应用 | 1 个 Vite/TS Vue3 应用，多路由页面 |
| i18n | PluginBundle properties | 保留 properties + Kotlin 类型化包装 |
| 登录凭据 | SensitiveDataStore | 保留同机制 |

**包结构**（单模块，依赖方向 `ui → platform → spi → core`，`infra` 实现 `core` 仓库接口）：

```
com.codequest
├── core        领域模型 + 用例服务 + 仓库接口
├── spi         OJPlatform 能力接口 + 注册
├── platform    <oj>/ 各平台适配器(api/ auth/ submission/ contest/)
├── infra       db / http / secrets / settings / messaging
├── ui          toolwindow 壳 + jcef 桥 + actions + settings
└── webview     (独立目录)统一前端应用
```

包名从 `com.wenjunhuang.codeepiphany` 换为 `com.codequest`。detekt + 自定义编译检查禁止跨层反向引用。

## §2 OJ 能力型 SPI

核心接口（全部在 `spi` 包，仅依赖 `core` 领域模型）：

```kotlin
interface OJPlatform {
    val id: String            // "leetcode", "luogu" ...
    val domain: String
    val info: PlatformInfo    // 显示名、图标、支持的语言列表
}

// 能力接口按需组合，suspend + Flow 风格
interface AuthCapable {
    suspend fun ensureLoggedIn(): LoginSession     // 未登录时弹登录流程
    fun isLoggedIn(): Boolean
    suspend fun logout()
}
interface ProblemQueryCapable {
    fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary>
    suspend fun fetchProblemDetail(id: ProblemId): ProblemDetail
}
interface SubmissionCapable {
    suspend fun submit(request: SubmitRequest): SubmissionHandle
    fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult>   // 轮询封装成 Flow
}
interface ContestCapable {
    fun contests(): Flow<ContestSummary>
    suspend fun contestDetail(id): ContestDetail
}
interface SolutionViewCapable {    // 题解浏览（洛谷题解、LC 社区解）
    suspend fun fetchSolutions(id: ProblemId): List<SolutionArticle>
}
```

**平台能力矩阵**（重写版真实实现范围，牛客已砍）：

| 平台 | Auth | Problem | Submit | Contest | Solutions |
|---|---|---|---|---|---|
| LeetCode | ✅ | ✅ | ✅ | — | ✅ |
| 力扣CN | ✅ | ✅ | ✅ | — | ✅ |
| CodeForces | ✅ | ✅ | ✅ | ✅（列表/详情/参加/提醒） | — |
| AtCoder | ✅（Turnstile） | ✅ | ✅ | — | — |
| 洛谷 | ✅ | ✅ | ✅ | — | ✅ |
| HackerRank | ✅ | ✅ | ✅ | — | — |

**注册机制**：每平台一个 `OJPlatformProvider`（IntelliJ Extension Point，plugin.xml 声明）。UI 与用例层通过 `OJPlatformRegistry` 查询：`registry.find(id)` / `registry.allWith<SubmissionCapable>()`。**不存在任何 `CodeDojo` 枚举 match 分发**——新增 OJ = 新建 `platform/<oj>/` 包 + plugin.xml 一行。

**平台适配器内部约定**：
- 每平台统一拆 `XxxApi`（纯 HTTP 映射）/ `XxxAuth` / `XxxSubmissionService`（实现 SPI）；公共轮询/重试/限速逻辑由 `spi` 的 `BaseSubmissionFlow` 承担
- Cloudflare Turnstile / 登录 JCEF 弹窗作为 `infra/anti-bot` 公共组件，AtCoder、洛谷复用

## §3 数据层设计

数据库：继续 SQLite，单库文件存于用户自选目录（保留「数据库文件夹」设置语义）。ORM 用 Exposed，首版直接建全量 schema + `schema_version` 版本表。

**Schema**（全新库，不做旧表兼容）：

```
problem            题目主档(id, platform_id, dojo_problem_id, title, difficulty, url, fetched_at)
problem_content    题面缓存(raw_md/html, 渲染格式标记)          -- 支持离线回看
challenge          解题工作区(problem_id → 多个 challenge)
challenge_language 每个 challenge 的语言+模板+入口文件
solution           多解法记录(challenge_id, name, note_md)
test_case          测试用例(challenge_id, input, expected, 来源:用户/平台)
submission         提交记录(id, challenge_id, platform_id, remote_submission_id,
                   language, code_snapshot, verdict, time_ms, memory_kb, submitted_at)
tag                标签(独立表 + problem_tag 关联)              -- 支撑标签筛选
contest_reminder   竞赛提醒(contest_id, remind_at, notified)
settings_kv        杂项设置
```

要点：
- `submission` 保存代码快照，回看不依赖工作区文件是否还在
- 标签为独立表（非 JSON 列），支撑跨题检索
- 全文检索：`problem_content` + `solution.note_md` 建 **FTS5** 虚拟表，LIKE 作降级路径（延续 WP7 结论）
- DAO 接口定义在 `core`（如 `SubmissionRepository`），`infra/db` 提供 Exposed 实现，用例服务只依赖接口，单测可换内存实现

**并发模型**：数据库写操作收敛到单写协程（Mutex/Channel 串行化），读并发放开；SQLite WAL 模式。

## §4 UI 与统一前端

**ToolWindow 布局**（Swing 壳 + JCEF 内容区，壳用 Kotlin UI DSL）：

```
左侧 CodeQuest 主侧栏(可切换面板):
├── Problems     题目列表(平台筛选/关键词/标签)→ JCEF 列表页
├── Contest      竞赛(CodeForces)→ JCEF 页
├── Statistics   刷题统计(热力图/趋势)→ JCEF 图表页
├── Search       跨题检索(FTS+标签)→ JCEF 检索页
编辑器区:
├── 题面工具窗口  → JCEF 题面渲染页(含题解浏览)
└── 提交结果面板  → JCEF 提交结果/历史页
```

**统一前端**：一个 Vite + Vue3 + TS 应用（`webview/`），路由页面：`/problems` `/contest` `/stats` `/search` `/problem-view` `/submissions`。
- 共享组件库与主题（跟随 IDE 主题：JB 颜色变量注入）、highlight.js 按需语言、KaTeX
- 每页面独立 entry 打包（按 toolwindow 按需加载，不做 SPA 大包）

**JCEF 桥协议**：单一 `CQBridge` 协议——
- Kotlin 侧 `postMessage(topic, jsonPayload)` 下发 / `on(topic, handler)` 接收；JS 侧对称
- topic 命名空间化：`problems:list` / `submission:submit` / `nav:openProblem`
- 全部走一个 `ResourceHttpServer`（本地静态资源 + REST 风格数据端点），页面数据直接 HTTP 拉取；**JCEF 消息只留给命令/事件**（提交、打开文件、跳转），消灭旧版大数据量消息序列化卡顿

**交互链路示例（提交）**：编辑器 action → `core` 用例服务 → SPI `submit()` → 结果写库 → HTTP 端点推送事件 → 提交面板自动刷新。

**登录 UI**：登录页（Cookie 粘贴 / 账号密码 / JCEF 内嵌官方登录页）与 Turnstile 弹窗作为 `ui/auth` 公共组件，由平台 `AuthMethod` 描述（表单型/内嵌浏览器型/cookie 粘贴型）驱动渲染，平台适配器不自带 UI 代码。

## §5 平台适配器迁移要点

「逻辑参考旧码、代码全部重写」：

| 平台 | 迁移要点 |
|---|---|
| LeetCode/CN | GraphQL API 携带；CN 端 Java 模板注入逻辑（见 2026-08-03 spec）保留设计、重写实现；题解文章页走统一前端渲染 |
| CodeForces | REST API 公开稳定；竞赛列表/详情/参加/24h 提醒（WP6 结论）全量保留；排名不承诺（API 限制，文档如实说明） |
| AtCoder | CSRF + Cloudflare Turnstile 复用 `infra/anti-bot` 弹窗（sitekey/选择器做成平台配置）；语言 ID 映射表保留 |
| 洛谷 | 题面渲染沿用 WP3 结论（前端 marked + 本地 KaTeX）；题解浏览器导航工具栏保留 |
| HackerRank | 登录态校验 + 提交轮询逻辑参考旧实现 |
| ~~牛客~~ | 砍掉，不迁移任何代码 |

**会话管理统一**：收敛为 `infra/http/SessionStore`——每平台一个 `CookieJar`，登录凭据持久化走 `infra/secrets`，`AuthCapable` 实现只面对自家 CookieJar。

**错误处理约定**：
- SPI 全部抛领域异常体系：`OJException` / `AuthExpiredException`（触发重登）/ `AntiBotChallengeException`（触发 Turnstile）/ `RateLimitedException`（自动退避）
- UI 层统一异常→通知映射，平台适配器不直接弹通知

**提交轮询**：`BaseSubmissionFlow` 通用「提交→轮询→终态」状态机（指数退避 + 超时），各平台只实现 `fetchRemoteStatus()` 钩子，消灭 6 份复制轮询代码。

## §6 质量与测试策略

| 层 | 策略 |
|---|---|
| `core` 用例服务 | 单测全覆盖（仓库接口内存假实现）；提交状态机、统计聚合、检索逻辑重点覆盖 |
| `platform` 适配器 | HTTP 映射层用 ktor MockEngine 回放录制响应夹具；不上真实网络 |
| `infra/db` | SQLite 临时库跑 schema/DAO/FTS 集成测试 |
| `webview` | Vitest 组件测试（桥协议 mock）；关键页面冒烟 |
| 端到端 | 不做自动化（依赖真实平台账号）；手工验证 checklist 按平台逐项过 |

**静态质量门禁**（CI，GitHub Actions 改 Gradle）：detekt + 自定义规则（禁止跳层引用、禁止 platform 包互相 import）、ktlint、`test`、`buildPlugin` 全绿才算阶段完成。

## §7 分阶段交付计划

推倒重写但**分阶段可发布**，每里程碑产出可安装 zip：

```
M1  骨架:Gradle/Kotlin 工程 + 包结构 + detekt/CI + JCEF 壳 + 统一前端脚手架(空页面)
M2  核心:core 领域模型 + Exposed 数据层 + settings + challenge 工作区(编辑器开题/多解法/测试用例)
M3  首个平台闭环:LeetCode/CN(登录/查题/题面渲染/提交轮询)——验证 SPI 真实可用
M4  平台铺开:CodeForces(含竞赛)/AtCoder(Turnstile)/洛谷(题解)/HackerRank
M5  增强:统计、跨题检索(FTS+标签)、题解文章、竞赛提醒
M6  收尾:旧 Scala 源码整体删除、README/文档更新、发布 1.0
```

M3 是关键验证点：SPI 若有缺陷，只接一个平台时返工成本最低。旧代码保留到 M6。

## 成功标准

1. 新增一个 OJ 平台 = 一个包 + plugin.xml 一行声明，不需要改动 core/ui 任何代码（以真实实现衡量）
2. `./gradlew test buildPlugin` 全绿，detekt 无跳层违规
3. 功能对齐现有版（6 平台 × 各自能力矩阵），手工 checklist 逐项通过
4. 任意 toolwindow 页面数据加载不再出现消息序列化卡顿（HTTP 数据端点 + 事件化桥）
