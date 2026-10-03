# 设计规格：力扣 P1 三项（每日一题 / LC 竞赛回顾 / 排序策略+隐藏已解决）

- 日期：2026-10-03
- 状态：已获用户批准（对话分节确认 + UI 对齐插件修订一轮）
- 目标版本：1.8.0（三 feature，minor bump）
- 前置：1.7.0 已定版（P0 四项：评分体系/历史同步/间隔复习/计时器，loop-20261002-lc-p0 achieved）
- 来源：插件分析报告的 P1 优先级项。UI 形态**尽可能对齐插件设计**（用户明确要求）：每日一题=常驻置顶入口、排序=循环切换按钮、竞赛行字段=标题/时间/时长/状态。

## 0. 需求与决策记录

AskUserQuestion 确认的六项决策：

1. **LC 竞赛仅 CN 变体实现 ContestCapable**——COM 站无竞赛 API（插件实证），不硬编码跨域请求；能力探测自动出现在竞赛 tab。
2. **每日一题纯卡片直达**——不做打卡状态跟踪（YAGNI）。
3. **排序混合策略**——题号/难度走服务端 sortBy 透传，评分排序保持 1.7.0 本地排序；隐藏已解决纯前端本地过滤。
4. UI 修订（对齐插件）：每日一题常驻卡片信息密度对齐树节点、排序用循环按钮扩展（非下拉）、竞赛行字段对齐插件。

**范围裁剪（YAGNI）**：每日一题打卡/提交状态、竞赛题解入口、rating/standings、收藏写操作（P2）、每日一题落库均不做。

## 1. F1 每日一题

### 1.1 SPI

```kotlin
interface DailyQuestionCapable {
    /** 当日题目；平台无此概念/拉取失败返回 null（降级不抛错）。 */
    suspend fun fetchDailyQuestion(): DailyQuestion?
}
@Serializable
data class DailyQuestion(
    val date: String,          // yyyy-MM-dd（CN 站返回或本地日期）
    val frontendQuestionId: String,
    val title: String,
    val titleSlug: String,     // remoteId，题面链路同源
    val difficulty: String,
    val tags: List<DailyQuestionTag>, // name+slug（照 Tag 精简）
)
```

- 仅 `LeetCodePlatform` CN 变体实现；协议 CN GraphQL `questionOfToday`（查询文本进 LeetCodeGraphQL.kt；字段 `todayRecord { question { questionFrontendId/title/titleSlug/difficulty/translatedTitle/topicTags } }`——插件实证，翻译标题优先）；解析失败/字段缺失返回 null。

### 1.2 REST 与缓存

- `GET /api/daily-question?platform=` → DailyQuestion DTO；无能力平台 404（照 /api/contests 探测惯例）。
- 服务端**当日进程内缓存**：`volatile var (date, payload)`，命中同日期直接返回；跨日首次请求重拉。不落库、不持久化。

### 1.3 前端（UI 对齐插件树顶常驻入口）

- 题库页**力扣 CN 平台时**顶部常驻卡片（「今日题目」横幅）：`日期 + 题号 + 标题 + 难度徽标 + 标签 chips`，点击走现有 `nav:openProblem`（platformId=leetcodecn, remoteId=titleSlug）直达题面。
- 平台切换到非 CN / 拉取失败 / 404：卡片不渲染，静默降级不打扰（照 myFavorites 降级惯例）。
- vitest：卡片渲染条件（CN/非 CN/失败降级）、字段渲染、点击导航 payload。

## 2. F2 LC 竞赛回顾（仅 CN 变体）

### 2.1 ContestCapable 实现（零接口改动）

`LeetCodePlatform`（**仅 CN 变体** implements ContestCapable）：

- `contests()`：CN GraphQL `contestHistory(pageNum: 1, pageSize: 10)` → `contests[{title, titleSlug, startTime, duration}]`；phase 按 `startTime/duration` 与 now 推导（`now < startTime` → BEFORE；`< startTime+duration` → CODING；否则 FINISHED——映射进现有五值 ContestPhase 枚举，仅用三值）；`ContestSummary(remoteId = titleSlug, title, startTime, durationMinutes, phase, url = "https://leetcode.cn/contest/$titleSlug/")`。
- `contestDetail(remoteId)`：CN REST `GET https://leetcode.cn/contest/api/info/{titleSlug}/` → `{questions: [{question_id, question__title, question__title_slug, ...}]}`（字段名以下钻实测为准，WP 内样例锁定）→ `ContestProblemRef(remoteId = question__title_slug, index = "Q${position+1}", title = question__title)`；竞赛不存在抛 OJException；题目拉取失败降级空列表（照 CF 先例「不致败详情」）。
- joinable：恒 false（回顾语义，LC 参赛走外链）。

### 2.2 前端（字段对齐插件近期竞赛节点）

- 竞赛 tab 平台下拉经 `/api/contests` 能力探测自动出现 leetcodecn——**前端零结构改动**，核对现有 contest 页字段展示：竞赛行需显示 `标题 + 开始时间（本地化）+ 时长 + 状态徽标`（现有 CF 行若缺字段则补齐渲染，LC/CF 共用行模板）；详情题目行 `Q 序号 + 标题`，点题走现有开题面交互（LC 题 remoteId=titleSlug 与题库同源，链路自然通——WP 内实测核对 contest 页点题对 LC 平台的 platform 参数传递）。
- 竞赛提醒（contest_reminders 表通用）LC 顺带可用，零代码改动（WP 验收时确认 UI 上 LC 平台可订阅）。
- vitest：LC 平台出现在竞赛平台下拉（能力探测）、竞赛行字段渲染、题目行点击导航。

## 3. F3 排序策略 + 隐藏已解决

### 3.1 后端 sortBy 透传

- `ProblemFilter` 加 `sortBy: String? = null`（协议枚举名透传，SPI 语义：实现忽略不支持值）。
- `LeetCodeApi` 列表查询：`sortBy` 非空时替换写死的 `CUSTOM ASCENDING`（LeetCodeApi.kt:495 现状）——协议枚举值 WP 内**实测锁定**（预期形如 `FRONTEND_QUESTION_ID`/`DIFFICULTY`，与 orderBy ASCENDING/DESCENDING 组合），未知值照旧 CUSTOM；测试断言请求参数快照。
- REST `/api/problems` 加 `sortBy` 参数（值域 `id`/`difficulty` → 映射协议枚举；空=默认序）；题单/公司模式照旧忽略排序（协议不支持，UI 不给入口）。
- 其他平台忽略 sortBy（SPI 契约允许，照 tags 先例）。

### 3.2 前端（循环按钮对齐插件 sort-precedence 交互）

- **扩展 1.7.0 排序循环按钮档位**：`默认 → 题号 → 难度 → 评分↑ → 评分↓` 循环：
  - 默认/题号/难度：带 `sortBy` 参数 reload（服务端排序，分页一致）；
  - 评分↑/↓：保持本地排序（按钮旁标注「仅已加载」，现有提示模式）；
  - 切换平台/筛选条件重置为「默认」。
- 「隐藏已解决」checkbox（筛选行，评分列开关旁）：本地过滤已加载行（状态徽标非 AC 的行隐藏；未登录无站内状态时仅按本地提交判定——复用现有状态列判定函数）；localStorage 键 `cq-problems-hide-solved` 默认关；与评分区间/状态筛选可叠加（filter-note 计数合并）。
- vitest：循环五档切换与 reload/本地分流、sortBy 参数传递、隐藏已解决过滤（AC 行保留/非 AC 隐藏/未登录判定）、localStorage 持久化、叠加过滤计数。

## 4. 横切

- 依赖零新增、无新表无迁移；GraphQL 查询文本集中 LeetCodeGraphQL.kt；REST 挂 ResourceHttpServer 照 RatingApi 先例。
- `questionOfToday`/`contestHistory`/`contest/api/info` 均为站方私有协议：错误一律降级（每日一题 null/竞赛抛领域异常映射 502），验收沙箱实测记录。
- 版本：1.7.0 → 1.8.0。

## 5. 测试

- LeetCodeApiTest：questionOfToday 三态（正常/字段缺失 null/errors 信封）、contestHistory 解析+phase 推导边界（BEFORE/CODING/FINISHED 跨界用注入 clock）、contest/api/info 解析（字段缺失降级空表）；sortBy 请求参数快照（id/difficulty/空）。
- REST HttpTest：/api/daily-question（200 缓存命中/无能力 404）、/api/contests?platform=leetcodecn（CN 实现/COM 404——能力探测）、/api/problems sortBy 参数。
- vitest：三功能前端用例（§1.3/§2.2/§3.2）。
- runIde 沙箱：每日一题真实拉取、LC 竞赛列表/详情真实数据、排序真实变化——照 1.7.0 沙箱探测模式。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| questionOfToday 协议变动/字段缺失 | null 降级卡片隐藏，不致败题库页 |
| contest/api/info 字段名与预期不符 | WP 内真实响应样例锁定；题目拉取失败空列表不致败详情 |
| sortBy 枚举值与协议不符 | 实测锁定+未知值回退 CUSTOM；请求参数快照测试 |
| 竞赛 tab 现有行模板对 LC 数据展示不全 | 共用行模板补齐字段（CF/LC 同受益），vitest 覆盖 |
| phase 推导时区错位 | startTime 为 epoch 秒/毫秒（实测锁定单位），统一 Instant 比较 |
