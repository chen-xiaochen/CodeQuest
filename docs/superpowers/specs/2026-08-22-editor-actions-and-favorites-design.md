# 设计规格：编辑器三键（测试用例/提交测试/提交）+ 题库页题单接入

- 日期：2026-08-22
- 状态：已获用户批准（对话分节确认）
- 目标版本：1.3.0（双 feature，minor bump）
- 前置：ui-revamp 1.2.x 系列（面板三窗/平台门控/模版双风格/状态列）已定版

## 0. 需求与决策记录

用户两项需求，关键决策已经 AskUserQuestion 确认：

1. **编辑器三键**：修改测试用例、提交测试、最终发布代码——入口在**编辑器页面上**（非右键菜单），交互参考旧项目（git `a8f69a0^` Scala 事实源）。
   - 「提交测试」语义 = **平台云端测试**（复刻原版 interpret 链路，力扣全语言支持），不用本地 Runner。
2. **力扣题单/学习计划**加入题库浏览。
   - 呈现 = **题库页过滤行下拉**（与现有「公司」下拉同构、互斥），不建独立页。

## 1. 编辑器三键

### 1.1 载体：EditorFloatingToolbarProvider

- EP `<editorFloatingToolbar implementation="..."/>`（IntelliJ 2023.1+；本项目平台 2026.2 可用）。
- `actionGroupId` 指向 plugin.xml 新 group `CodeQuest.ChallengeEditor.FloatingToolbar`；`canShow(file, editor)` = 文件属于某挑战工作区（复用 `WorkspaceTargetResolver` 路径匹配语义，轻量判定优先：路径前缀匹配工作区基目录 + 文件在 DB 挑战集合内）。
- 不做原版的自定义 FileEditor + 顶部工具栏（TextEditorBridge 全套桥接，重且侵入）；浮动栏零侵入达到「编辑器页面上」的同等效果。右键菜单现有 `CodeQuest.SubmitToOj` 保留不动。

### 1.2 三按钮

| 按钮 | 行为 | 数据/链路复用 |
|---|---|---|
| 修改测试用例 ✏️ | 弹「测试用例」对话框：表格行编辑（输入/期望输出），增删行、批量粘贴导入；确认写库 | testCaseRepository（与挑战工具窗测试用例区同源互通） |
| 提交测试 ▶️ | 区域提取代码 + 拼装当前挑战全部用例 → 平台云端跑 → 结果对话框 | 新 SPI（§1.3）+ SubmitRegionExtractor + BaseSubmissionFlow 轮询参数 |
| 提交 ⬆️ | 现有提交流程原样 | `SubmissionCoordinator.submitFile(file, text)` 零新逻辑 |

**测试用例对话框**（复刻原版 TestCasesDialog 精简版）：`JBTable` 两列（输入/期望输出）+ 工具条（增行/删行/导入）；「确定」逐行 upsert、删除缺席行。对话框纯 Swing，编辑当前挑战（按编辑器文件定位）。

**结果对话框**（提交测试）：汇总行（X/Y 通过）+ 表格（每用例：输入摘要/期望/实际/结果）+ CE/RE 时显示全文错误区。数据来自 interpret 轮询终态。

### 1.3 新 SPI 能力：TestEvaluationCapable

```kotlin
interface TestEvaluationCapable {
    /** 提交云端测试并轮询至终态；非终态发射 Pending。 */
    fun watchTestRun(request: TestRunRequest): Flow<TestRunStatus>
}
data class TestRunRequest(
    val problemRemoteId: String,      // slug
    val questionId: String,           // 力扣 questionId（数字 id）；其他平台可空
    val languageId: String,           // 平台侧语言 id（与 LanguageInfo.id 同源）
    val code: String,
    val dataInput: String,            // 用例输入拼接（\n 分隔，原版语义）
)
sealed interface TestRunStatus {
    data object Pending : TestRunStatus
    data class Finished(val result: TestRunResult) : TestRunStatus
}
data class TestRunResult(
    val correctCount: Int?, val totalCount: Int?,          // total_correct / total_testcases
    val codeAnswers: List<String>, val expectedCodeAnswers: List<String>,  // 每用例实际/期望
    val compareResult: String?,                            // "1110..." 逐用例对错
    val statusRuntime: String?, val statusMemory: String?,
    val compileError: String?, val runtimeError: String?,  // 全文（full_* 优先）
    val state: String,                                     // SUCCESS/PENDING...
)
```

**力扣实现**（原版 LeetCodeApi 事实，git `a8f69a0^:.../leetcode/services/LeetCodeApi.scala:300-350`）：
- `POST https://<domain>/problems/<slug>/interpret_solution/`，JSON body snake_case `{lang, data_input, question_id, typed_code}`，需 CSRF token（复用现有 LeetCodeApi csrf 通道）→ `{interpret_id, test_case, interpret_expected_id}`。
- 轮询 `GET /submissions/detail/<interpret_id>/check/`（1s 起步，复用 BaseSubmissionFlow 的退避/封顶/最大尝试语义；state != SUCCESS 继续，非终态字段宽容解析）。
- `questionId` 获取：实现内部经 `fetchProblemDetail(slug)`（GraphQL questionData 已含 questionId，1.2.4 起已解析）现查，主档不冗余存列。
- 字段映射对齐原版 `runCode.scala`（code_answer[]/expected_code_answer[]/compare_result/total_correct/total_testcases/full_compile_error/full_runtime_error/status_runtime/status_memory…）。

**门控**：
- 文件不属于挑战工作区 → 浮动栏整体不显示（canShow=false）。
- 平台无 TestEvaluationCapable（CF/AtCoder/洛谷/HackerRank 一期不做）→ 「提交测试」按钮隐藏（action update 可见性），其余两键仍在。
- 平台未登录 → 「提交测试」「提交」置灰（原版语义：显示但禁用）。
- 编辑器有未保存修改 → 三键触发前统一 `FileDocumentManager.saveDocument`（原版 saveEditedFile 语义）。

### 1.4 UI 文案

全部走 PluginBundle 双语键（`_zh.properties` 中文为准），新增键：浮动栏三 action 文案、测试用例对话框标题/列头/按钮、结果对话框标题/列头/汇总模板、提交测试各错误态。

## 2. 题库页题单/学习计划

### 2.1 后端

- **查询通道**：复用 WP42 `favoriteQuestionList(favoriteSlug, filtersV2, skip, limit)` GraphQL（学习计划与公司题单同源——都是 favorite）。SPI 侧把 `CompanyQueryCapable.queryCompanyProblems` 的底层查询泛化出 `queryFavoriteProblems(favoriteSlug, filters, offset, limit)`（公司实现委托之，避免复制）。
- **学习计划清单**：内置静态清单（slug + 中文名），一期以 leetcode.cn/studyplan/ 官方清单为准（热题 100 `top-100-liked`、面试经典 75 题 `interview-75`、LeetCode 75 `leetcode-75`、数据结构入门 `data-structure`、初级/中级/高级算法、二分/哈希/动规/图论系列、SQL 50 等，实现时对照官网核对 slug）。放 core 或 platform 层常量表。
- **我的题单**：新能力方法 `fetchMyFavorites(): List<FavoriteSummary>`（slug/name/题数）。接口实现时探测（WP 内首个 spike）：
  1. 优先 GraphQL `favoriteFolders`（CN 站）；
  2. 备选老 REST `GET /api/list_favorites/`（shuzijun/leetcode-editor 事实，`/problems/api/favorites/` 同族）。
  3. 都失败 → 我的题单组空缺、学习计划组照常（降级不阻塞）。
- **HTTP 端点**：`GET /api/favorites?platform=leetcodecn` → `{ studyPlans: [{slug,name}], myFavorites: [{slug,name,questionCount}] }`（能力探测 404 语义与 /api/companies 一致）；题目列表查询扩展现有 problems 端点参数 `favorite=<slug>`；与 company/period 同时给出时 **favorite 优先、company 忽略**（前端已互斥，后端宽松兜底）。

### 2.2 前端（题库页 App.vue）

- 过滤行新增「题单」下拉，渲染条件：平台 = 力扣系（leetcode/leetcodecn）。结构：
  ```html
  <select> 全部题库
    <optgroup label="学习计划">…内置清单…</optgroup>
    <optgroup label="我的题单" v-if="myFavorites.length">…登录态拉取…</optgroup>
  </select>
  ```
- 与「公司」下拉互斥：选题单 → 清公司+期间；选公司 → 清题单（双向）。
- 选中后列表查询带 `favorite=<slug>`；keyword 在题单模式下与公司模式同规则（后端忽略则前端提示同公司模式文案）。
- 我的题单拉取失败/未登录 → 只显示学习计划组，不打扰（console 静默）。

### 2.3 i18n

前端沿用现有中文文案直写模式（ui-revamp 已定：前端直接中文化）。

## 3. 测试

- **SPI/平台单测**（FakeOJTransport，WP42 模式）：
  - interpret_solution 请求体（snake_case/CSRF 调用）与 interpret_id 解析；
  - check 轮询 → 终态字段全集映射（code_answers/compare_result/CE/RE 全文）；
  - favoriteQuestionList 泛化后公司题单回归（参数不变）；
  - 学习计划清单常量与 fetchMyFavorites 两种接口形态解析。
- **UI 纯逻辑单测**：对话框表格行编辑/增删/导入纯函数部分；浮动栏 canShow 判定。
- **前端 vitest**：题单下拉渲染条件、optgroup 两组、与公司互斥行为、favorite 参数传递、我的题单失败降级。
- **既有回归**：`./gradlew test check`、`cd frontend && npm run test && npm run build` 全绿。
- **沙箱走查**（runIde）：开刷→编辑器浮动栏三键显隐与点击链路、题库页题单下拉→列表切换→开刷闭环。

## 4. 验收

1. 开刷一个力扣题（Java）→ 编辑器右上浮现三键；点「修改测试用例」改用例并保存 → 挑战工具窗测试用例区同步显示。
2. 「提交测试」→ 结果对话框展示每用例 输入/期望/实际/通过数；CE 时显示全文。
3. 「提交」→ 现有提交链路照常（通知+提交记录 tab）。
4. 题库页（力扣中国）出现「题单」下拉：学习计划组可选（如 热题 100），列表切换为题单内容且开刷正常；登录后「我的题单」组出现收藏夹；与公司下拉互斥。
5. CF/AtCoder 等平台编辑器无「提交测试」键；非挑战文件无浮动栏。
6. 上述全绿后定版 1.3.0。

## 5. 风险与边界

- **interpret_solution/check 老接口存活**：原版即此端点且 WP36 提交详情已实证 `/submissions/detail/<id>/check/` 存活；interpret_solution 若 CN 站变动 → 沙箱实测校正（接口探测为 WP 首步）。
- **favoriteFolders/list_favorites 均可能失效**：降级策略已定（我的题单组隐藏，不阻塞学习计划）。
- **学习计划 slug 漂移**：清单静态内置，失效 slug 表现为该计划空列表；对照官网核对 + 用户可反馈补充。
- **浮动栏 canShow 高频调用**：只做路径前缀与缓存的挑战集合判定（不逐次查库；DB 变更时失效缓存），避免 UI 卡顿。
- **多 challenge 同题**：提交测试/提交均按编辑器文件定位 challenge（与现提交链路同语义）。
- 一期范围外：HackerRank/CF 等平台的提交测试能力；题单内进度同步（完成打勾以站内 status ac 合并语义为准，已有）。

## 6. 实施切分（供 tackle-plan 消费）

- WP-A：SPI `TestEvaluationCapable` + 力扣 interpret 实现（含接口探测 spike）+ 单测。
- WP-B：测试用例对话框 + 结果对话框（Swing，纯逻辑抽离可测）。
- WP-C：浮动栏（EP 注册 + 三 action 接线 + canShow 门控）+ 文案。
- WP-D：favorite 查询泛化 + 学习计划清单 + 我的题单探测实现 + `/api/favorites` 端点 + 单测。
- WP-E：前端题单下拉（optgroup/互斥/参数/降级）+ vitest。
- WP-F：沙箱走查 + 定版 1.3.0（CHANGELOG/版本/构建）。

依赖：A→B→C 串行（链路层层依赖）；D/E 并行可；F 收尾。
