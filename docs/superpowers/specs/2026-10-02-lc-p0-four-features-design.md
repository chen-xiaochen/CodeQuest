# 设计规格：力扣 P0 四项（难度评分 / 提交历史同步 / 间隔重复复习 / 题目计时器）

- 日期：2026-10-02
- 状态：已获用户批准（对话分节确认）
- 目标版本：1.7.0（四 feature，minor bump）
- 前置：1.6.0（双语题库/知识点筛选/题面会话恢复）已定版
- 来源：对 `E:\Desktop\leetcode-extension-main`（ccagml vscode 力扣插件 v3.2.4）的精华分析——本 spec 吸收其评分体系/搬砖复习/提交历史/计时器四项功能设计，**不吸收**其 eval 解析、缓存 key 路径穿越、工作区 JSON 无锁读写等实现缺陷。

## 0. 需求与决策记录

四项力扣优先增强，大方向与三项关键决策均经 AskUserQuestion 确认：

1. **评分数据源 = 内置 + 在线刷新**：zerotrac JSON 打包进插件资源兜底（离线可用），题库页手动「刷新评分」在线覆盖。
2. **着色基准 = 分差五档**：拉用户竞赛分（`userContestRankingInfo`），题目分−用户分着色；无竞赛分退化绝对分档。
3. **历史同步触发 = 手动 + 增量**：题库/统计页「同步历史」按钮，倒序分页拉到已存 remote_id 即停；不引入后台任务。
4. 复习完成语义、计时器会话级等次要决策在设计分节中随设计通过。

**范围裁剪（YAGNI 声明）**：提交记录页混合展示站内历史、耗时写入 submission 统计聚合、收藏写操作、每日一题、LC 竞赛、一键全用例均不在本次范围。

## 1. F1 难度评分体系

### 1.1 数据与表

- 打包资源 `kotlin/src/main/resources/rating/leetcode-ratings.json`：zerotrac `data.json` 精简（`frontend_id → rating` 映射）。构建时人工更新，仅兜底。
- 新表 `problem_rating(frontend_id TEXT PK, rating REAL NOT NULL, updated_at TEXT NOT NULL)`（schema v3）。在线刷新 upsert 全量覆盖。
- 加载优先级：DB 非空用 DB，否则用内置资源；两者均为内存 `Map<String, Double>`（`RatingService` 持有，进程内缓存）。

### 1.2 join 链路

- `ProblemSummary` 加可选字段 `frontendQuestionId: String? = null`（仅 LC 列表解析填充，`questionFrontendId` 协议字段；其他平台 null）。
- REST `/api/problems`（含题单/公司模式）装配响应时：按 `frontendQuestionId` 查内存 Map，行 JSON 加 `rating` 字段（无分 null）。装饰在 infra REST 层——platform 不 import infra，分层不破。

### 1.3 用户竞赛分

- `LeetCodeApi` 加 GraphQL `userContestRankingInfo`（CN/COM 同名 query）→ `LeetCodePlatform` 暴露于 REST `GET /api/rating/user-contest`（`{rating, attendedContestsCount}`；未登录/未参赛/查询失败返回 null，不抛错）。
- 前端拉一次会话内缓存，显示于题库页账号区。

### 1.4 前端呈现

- 题库表格加「评分」列：数值展示，支持列排序。
- 着色（载体定死：**评分列文字着色**，不加色点、不动题名列）：
  - 分差模式（默认，有用户竞赛分时）：`题目分 − 用户分`，五档 `<-200` 绿(秒杀) / `[-200,-50)` 蓝(热身) / `[-50,50)` 紫(普通) / `[50,200)` 黄(吃力) / `≥200` 红(劝退)——阈值对齐插件可调常量。
  - 绝对分退化（无用户分）：`<1400 / 1400-1799 / 1800-2199 / 2200-2599 / ≥2600` 同五色。
  - 无分题目不着色。
- 筛选行加评分区间：min/max 数字输入，前端本地过滤当前已加载行（与现有「状态」本地过滤同模式）。
- 着色模式开关与评分列显隐存 localStorage（与现有筛选状态同存储）。

### 1.5 REST 面

- `GET /api/rating/status` → `{source: "db"|"bundled"|"empty", count, updatedAt}`。
- `POST /api/rating/refresh` → 拉取 zerotrac → upsert DB → 重载内存，返回 `{count, updatedAt}`；失败透传错误消息。
- `GET /api/rating/user-contest` → 如上。

## 2. F2 站内提交历史同步

### 2.1 SPI 与协议

```kotlin
interface SubmissionHistoryCapable {
    /** 按用户全局倒序分页拉站内提交。 */
    suspend fun fetchSubmissionHistory(offset: Int, limit: Int): RemoteSubmissionPage
}
data class RemoteSubmissionPage(val submissions: List<RemoteSubmission>, val hasMore: Boolean)
data class RemoteSubmission(
    val remoteId: String, val titleSlug: String, val title: String,
    val statusDisplay: String, val lang: String,
    val runtimeMs: Int?, val memoryKb: Int?, val submittedAt: Instant,
)
```

- `LeetCodeApi` 加 GraphQL `submissionList(offset, limit, lastKey)` 映射（字段：id/title/titleSlug/statusDisplay/runtime/memory/lang/timestamp）。
- 仅 LeetCode（两 variant）实现；其余平台不实现该 Capable，REST 对不支持平台返回 400。

### 2.2 落库与增量

- 新表 `remote_submission`（schema v3）：
  `id PK AUTOINCREMENT, platform_id, remote_id, title_slug, title, status_display, lang, runtime_ms NULL, memory_kb NULL, submitted_at TEXT`，`UNIQUE(platform_id, remote_id)`。
- **不动 `Submission` 模型**（挂 challengeId、30 callers；站内历史无本地 challenge，独立表零风险）。
- `SubmissionHistorySyncService`：倒序分页拉取（页大小 20，与站内一致），逐条 upsert；**遇到已存在 (platform_id, remote_id) 即停止**（增量语义）；上限保护：单次同步最多 200 页（4000 条）防失控。
- 触发：REST `POST /api/submissions/sync`（body `{platformId}`），同步在 pooled 线程执行，返回 `{synced, total}`。

### 2.3 消费

- `StatisticsService` 合并 `submission` + `remote_submission` 两表口径：streak、热力图、AC 总数 = 两表并集（站内历史按 `status_display=AC` 判 AC）；同一 (platform_id, remote_id) 同时存在时**本地 `submission` 优先**（含代码快照与完整 verdict），站内条目跳过。
- 统计页显示「站内历史：已同步 N 条 + 同步按钮」。
- REST `GET /api/submissions/remote?platform=&limit=&offset=` 分页查看（本版本最小 UI：统计页计数；列表混合展示列 P1）。

## 3. F3 间隔重复复习

### 3.1 表与模型

- 新表 `review_schedule`（schema v3）：
  `problem_id TEXT PK, plan_dates TEXT NOT NULL, history_dates TEXT NOT NULL, updated_at TEXT NOT NULL`——两列均为 epoch 秒数组 JSON（对齐当天**零点**时间戳，系统默认时区，插件同款语义）。
- 「当日到期」= `plan_dates` 含今天零点；「已过期」= plan_dates 存在小于今天零点的值。

### 3.2 ReviewService（core）

- `onAccepted(problemId)`：
  - plan 为空 → 按 `1,4,7,14,28,60` 天生成 plan（AC 日零点 + n×86400）；
  - plan 非空 → 移除今天及过去的全部日期（完成本次复习），history 追加今天。
- `addManual(problemId)`：冷启动入口（已 AC 老题/任意题），按明天零点起生成全量 plan。
- 间隔可配置：settings_kv key `review_intervals`，默认 `"1,4,7,14,28,60"`（正整数升序，逗号分隔；非法回退默认）。
- 触发点：`SubmissionCoordinator.runSubmission` AC 后（`broadcastUpdated` 旁）调 `onAccepted`——**平台无关**，任何平台 AC 均排复习。

### 3.3 REST 与前端

- `GET /api/review/today` → `{overdue: [...], today: [...], upcoming: [...], totalTracked}`（行含题号/标题/难度/平台/到期日）。
- `POST /api/review/problems/{problemId}/add`、`POST /api/review/problems/{problemId}/complete`（手动完成当日复习，等价再 AC 路径的移除逻辑）。
- 题库页加「复习」子视图入口（对齐现有本地搜索子视图切换模式），入口徽标 = 今日到期数：
  - 分组列表：已过期（红）→ 今日 → 未来；行点击走现有 `nav:openProblem` 开右窗题面。
  - 子视图内提供间隔配置输入（写 settings_kv）与「标记完成」操作。
- 题面页 header 加「加入复习」按钮（调 add 接口，已在计划中则显示「已排期至 x」）。

## 4. F4 题目计时器

- 纯前端会话级：problem-view 页 header 计时角标 `mm:ss`（超 1h 显示 `h:mm:ss`）；**页面收到题面数据即重置起表**（切题自动重置）。
- 停表：题面页订阅现有 `submission:updated` 桥事件——**payload 增补 `platformId` + `problemRemoteId` 两字段**（`runSubmission` 已持有 `target.problem`，`broadcastUpdated` 签名顺带传入；`challengeId` 等既有字段不动，submissions 页零影响）。题面页按 `(platformId, problemRemoteId)` 匹配当前题且 `verdict` 为 Accepted → 停表定格 + toast「本题耗时 xx」。网页直接提交（无插件事件）不停表，属预期。
- Kotlin 侧唯一改动：`SubmissionCoordinator.submissionsBridge` 现为单槽（`attach/detachSubmissionsBridge`），改为多播列表，题面页 bridge 注册订阅同一 topic；submissions 页现有行为不变（兼容改造）。
- 开关 `enableTimer`（localStorage，默认开）；不持久化、不进统计（升级路径：elapsed 列入 submission 表）。

## 5. 迁移与横切

- schema **v3**：`problem_rating` / `remote_submission` / `review_schedule` 三张新表；既有表零改动（`problem`/`submission` 不动）。
- bundle：新增 REST 路由的 i18n key（PluginBundle）与前端文案，中英对照。
- 依赖零新增：HTTP 用 infra 现有栈，JSON 用 kotlinx.serialization，前端无新库。
- 平台优先级声明：三 Capable/服务中仅评分用户竞赛分与历史同步为 LC 专属；复习调度与计时器平台无关。

## 6. 测试

- `RatingService`：内置资源解析、DB 优先级、刷新 upsert 单测。
- `LeetCodeApi.submissionList` / `userContestRankingInfo`：样例 JSON 解析单测（照 `LeetCodeApiTest` 既有模式）。
- `SubmissionHistorySyncService`：fake api 验证增量停止、页上限保护。
- `ReviewService`：首 AC 排期、再 AC 完成移除、过期判定、间隔配置解析与非法回退。
- `ExposedReviewRepository` / remote_submission repo：集成测试（照 `SubmissionRepositoryIntegrationTest` 模式）。
- REST 新端点：HttpTest（照 `SubmissionsApiHttpTest` 模式）；bridge 多播改造回归 submissions 页刷新路径。
- 前端：手动清单（着色五档/区间筛选/复习子视图/计时器起停），追加到 `docs/` 手册清单模式。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| zerotrac 数据格式变动/下线 | 内置资源兜底；刷新失败只提示不破坏 DB |
| `submissionList` 为站方私有协议，可能变动 | 错误透传 + 同步按钮失败提示；SPI 隔离，其他平台不受影响 |
| CN 站 `userContestRankingInfo` 未参赛/未登录无数据 | 返回 null，着色退化绝对分档 |
| bridge 单槽改多播 | 兼容改造：列表遍历 postMessage，单注册时行为与现状逐字节一致 |
| 历史同步量大（数千条分页） | 页上限 4000 条 + pooled 线程 + 按钮反馈进度计数 |
| 时区零点语义跨机器漂移 | 固定系统默认时区（插件同款），不引 UTC 复杂度 |
