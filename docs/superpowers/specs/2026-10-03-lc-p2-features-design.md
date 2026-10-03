# 设计规格：力扣 P2 三项（收藏写操作 / Hints 折叠 / 一键全示例测试）

- 日期：2026-10-03
- 状态：已获用户批准（对话分节确认，三项决策均选推荐项）
- 目标版本：1.9.0（三 feature，minor bump）
- 前置：1.8.0 已定版（loop-20261003-lc-p1 achieved，每日一题/竞赛回顾/排序）
- 来源：插件分析报告 P2 优先级项 + UI 参考插件设计原则延续（星标按钮/提示折叠照插件同构交互）

## 0. 需求与决策记录

AskUserQuestion 三项决策：

1. **收藏目标 = 默认收藏夹**——题面页星标两态按钮，本地乐观切换；选夹面板 YAGNI。
2. **Hints = 懒加载 + 逐条折叠**——「提示」按钮点击才拉（剧透控制），`<details>` 逐条展开。
3. **全示例测试入口 = 编辑器浮动栏第四键**——有代码上下文，复用现有云测链路与结果对话框。

**范围裁剪（YAGNI）**：选夹面板、收藏夹管理（建/删夹）、hints 与题解联动、按用例子集选择测试均不做。

## 1. F1 收藏写操作

### 1.1 SPI

```kotlin
interface FavoriteWriteCapable {
    /** 加入默认收藏夹。questionId 为平台数字主档 id（ProblemStatement.questionId 同源）。 */
    suspend fun addToDefaultFavorite(questionId: String)
    /** 从默认收藏夹移除。题目不在夹中视为幂等成功（站方语义实测锁定）。 */
    suspend fun removeFromDefaultFavorite(questionId: String)
}
```

- 仅 LeetCodePlatform（两变体）实现。失败抛领域异常（AuthExpired 透传 401 提示重登）。

### 1.2 协议（实测锁定，结论进 KDoc）

- **默认夹定位**：复用 WP56 `fetchFavoriteFolders`（GraphQL `favoritesLists`）——实测确认响应项含 `idHash` 与默认夹标记（预期 `isDefaultFavorite` 字段；缺失则 fallback 列表首夹，如实记录实测结论）。idHash 进程内缓存（登录态变化失效——auth 事件或简单进程缓存）。
- **mutation**：GraphQL `addQuestionToFavorite(questionId: ID!, favoriteIdHash: String!)` / `removeQuestionFromFavorite(...)`，响应 `{ok, error}`（对齐插件源 grep + 真实站实测字段形态；`ok=false` 或 errors 信封抛 OJException）。
- **初始收藏态**（实测优先）：`questionData` 详情响应若含收藏态字段（如 `isFavorited`）则顺带解析透出；实测无此字段则前端中性星标 + 首次点击后本地记忆（**不引入新查询协议**，spec 如实记录该降级）。

### 1.3 REST 与前端

- `POST /api/favorite/toggle`（body `{platformId, questionId, add: Boolean}`）→ 成功 `{ok:true}` / 失败透传错误码（未登录 401 / 站方失败 502）。
- 题面页 header 星标按钮：初始态（详情带出或中性）→ 点击乐观切换 → POST → 失败回滚+toast 错误；成功保持。CSS 变量两态样式（实心星/空心星）。
- vitest：按钮两态渲染、乐观切换与失败回滚、401 提示文案、中性初始态行为。

## 2. F2 Hints 懒加载折叠

### 2.1 SPI 与协议

```kotlin
interface ProblemHintsCapable {
    /** 题目提示列表；平台无此概念/失败返回空表（降级不抛）。 */
    suspend fun fetchHints(remoteId: String): List<String>
}
```

- 仅 LC 两变体：GraphQL `questionHints(titleSlug: String!){question{hints}}`（插件实证；hints 为字符串数组，元素可能含换行/HTML 实体——原样透出前端按文本渲染）。真实站实测锁定字段形态进 KDoc。
- REST：`GET /api/problem/{remoteId}/hints?platform=` → `{hints:[...]}`；无能力平台 404（探测惯例）。

### 2.2 前端

- 题面页「提示」按钮恒显示（与题解按钮同区）：点击才拉取 → 渲染逐条 `<details><summary>提示 N</summary>正文</details>`（默认全收起）；空数组显示「无提示」；再点收起整个区块。api.ts `fetchProblemHints(platform, remoteId)`。

## 3. F3 一键全示例测试（浮动栏第四键）

### 3.1 后端零改动声明

`TestEvaluationCapable.watchTestRun(dataInput)` 与 `TestRunResult` 多用例模型（`codeAnswers[]/expectedCodeAnswers[]/compareResult`）均为 WP53 现成产物；本功能纯编辑器侧接线。

### 3.2 Kotlin 新 action

- plugin.xml 浮动栏 group `CodeQuest.ChallengeEditor.FloatingToolbar` 加第四 action「全示例测试」（图标 + bundle 英中文案）。
- 链路（照 WP55「提交测试」键模式）：`ChallengeEditorGate.resolveTarget` → `SubmitRegionExtractor.extract`（缺标记报错同现有文案）→ 取题面 `exampleTestcases`（题面 detail 缓存/`/api/problem` 数据；空示例提示不可用）→ `join("\n")` 组装 dataInput → `TestEvaluationCapable.watchTestRun` 收集至终态 → **复用 `TestRunResultDialog`**（多行对比现成，标题「全部示例」区分）；平台无云测能力隐藏该键（同三键门控）。
- 用例提取纯函数（exampleTestcases join + 空判定）抽离可单测。

## 4. 横切

- 依赖零新增、无新表无迁移；GraphQL 文本集中 LeetCodeGraphQL.kt；REST 挂载照 RatingApi 先例。
- 三项协议（favoritesLists idHash/默认夹标记、mutation 字段、questionHints 形态）真实站实测锁定；初始收藏态字段有无也属实测项。
- 测试：LeetCodeApiTest 样例 JSON（favoritesLists 复用回归/mutation 两态/hints 三态）、新 REST HttpTest、action 纯函数测试、前端 vitest（星标/hints 折叠）、runIde 沙箱实测（收藏真机需登录态——沙箱无登录态时记录 401 透传如设计）。
- 版本 1.8.0 → 1.9.0。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| favoritesLists 无 idHash/默认夹标记 | 实测锁定；缺失 fallback 首夹并如实记录 |
| mutation 为写操作，协议/权限变动 | 错误透传+前端回滚；幂等语义实测（重复加/移除不视为失败） |
| questionData 无收藏态字段 | 设计已声明降级：中性星标+本地记忆，不引新协议 |
| questionHints 私有协议变动 | 空表降级不致败，「无提示」文案兜底 |
| 全示例 join 后超站方输入长度限制 | 站方报错原样透传结果对话框（插件实证常规题可行；超长属边缘如实透传） |
| 沙箱无登录态验不了收藏真链路 | 单测锁协议形态 + 沙箱记录 401 透传；真机留人工（清单） |
