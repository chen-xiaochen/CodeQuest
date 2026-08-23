# 编辑器三键 + 题单接入 集成验收清单(loop-20260822-editor-actions-favorites / WP58)

- 日期:2026-08-23
- 规格:`docs/superpowers/specs/2026-08-22-editor-actions-and-favorites-design.md` §4(六条验收)
- 结论速览:六条中 ④⑥ 完整自动化(含沙箱实测);①②⑤ 自动化覆盖主体、IDE 内交互留人工;③ 静态核验(复用既有链路零新逻辑)+ 留人工

## 0. 全量门禁结果(2026-08-23 实测)

| 门禁 | 命令 | 结果 |
|---|---|---|
| Kotlin | `./gradlew clean check test buildPlugin --console=plain` | BUILD SUCCESSFUL;749 tests / 0 failures / 0 errors / 1 skipped(基线 749);detekt + ktlint + 分层依赖检查全过;zip 产物 `build/distributions/codequest-1.3.0.zip`(27,842,152 bytes ≈ 26.5 MB) |
| 前端 | `cd frontend && npm run test && npm run build` | 18 文件 165 tests 全过(基线 165);vite build 成功 |

### 沙箱自动化走查(runIde,2026-08-23)

沙箱 `.intellijPlatform/sandbox-project`,IDE 进程监听端口扫描定位到插件内嵌服务器 **127.0.0.1:2304**(OS 随机端口):

| 探测 | 结果 |
|---|---|
| `GET /api/health` | `{"status":"ok","version":"0.1.0"}` |
| `GET /api/favorites?platform=leetcodecn` | HTTP 200,1242 bytes:`studyPlans` 18 项(top-100-liked / leetcode-75 / top-interview-150 / … / 2024-spring-sprint-100)+ `myFavorites` 1 项(slug 3205224, name "Favorite", questionCount 0)——沙箱存有力扣中国已登录会话(black__boy),我的题单为真实 GraphQL 实拉结果 |
| `GET /api/favorites?platform=codeforces` | HTTP 404 `{"error":"unknown-platform","message":"no favorite-capable platform registered for id 'codeforces'"}`(与 /api/companies 404 语义一致) |
| Playwright(chromium headless)打开 `http://127.0.0.1:2304/problems/` | 页面标题 `CodeQuest · Problems`;「题单」下拉可见:裸选项「全部题库」+ optgroup「学习计划」18 项(热题 100 / LeetCode 75 / 面试经典 150 题 / …)+ optgroup「我的题单」1 项「Favorite (0)」;平台下拉按登录门控只列「力扣」;1 条 console 404(静态资源,非阻塞);截图存 `C:\Users\chenchen\AppData\Local\Temp\claude\wp58pw\problems-page.png` |

走查后沙箱 IDE 进程已按 CommandLine 匹配 `*sandbox-project*` 全部杀净(0 残留),再重跑 buildPlugin,无 jar 锁。

## A. 自动化验收映射表(spec §4 六条)

| # | 验收条目 | 结论 | 证据(测试文件 / 用例名) |
|---|---|---|---|
| ① | 力扣题开刷→编辑器三键浮现;改用例→工具窗同步 | 自动化部分 ✅;IDE 内浮现/点击/同步留人工(B-1) | 产物含全部新类(核验 §B 产物清单);`TestCasesEditStateTest.kt` 9 例(`toRows keeps order and carries existing ids`、`changed row is upserted with its existing id`、`absent original rows are deleted`、`fully blank new rows are dropped…`、`clearing all rows deletes everything`、`reorder alone produces no writes` 等——对话框与工具窗同库写穿语义);`ChallengeFileMatcherTest.kt` 7 例(浮动栏 canShow 判定:工作区内/嵌套子目录/外部/多基目录/组件级前缀/未归一化路径) |
| ② | 「提交测试」→ 结果对话框每用例 输入/期望/实际/通过数;CE 全文 | 自动化 ✅(FakeOJTransport 单测链);真实登录跑一次留人工(B-2) | `LeetCodeTestEvaluationTest.kt` 8 例(`interpret solution posts snake case body and parses interpret id`、`success check maps full result field set`、`compile and runtime error prefer full text over short`、`compile error falls back to short text when full missing`、`pending state keeps polling with backoff until success`、`polling times out with domain exception after max attempts`、`unsupported language id fails fast`、`service and platform variants are test evaluation capable`);`TestRunResultStateTest.kt` 9 例(`compare_result bitmap wins per position`、`caseRows zips inputs expected actual and pass flag`、`summary counts prefer platform-reported correctCount and totalCount`、`compile error text replaces the table, runtime error second` 等);`TestRunPrepTest.kt` 5 例(dataInput 拼接/拒绝文案键) |
| ③ | 「提交」→ 既有链路照常 | 静态核验 ✅;人工走查留 B-3 | plugin.xml:`CodeQuest.SubmitToOj` 原 action `add-to-group` 并入浮动栏组(零新提交逻辑,WP21 链路复用);回归:`SubmissionFlowTest.kt` / `SubmissionGuardTest.kt`(防重入锁 3 例)全绿 |
| ④ | 题库页「题单」下拉:学习计划组/我的题单组/公司互斥 | 自动化 ✅(沙箱实测 + 单测) | 沙箱:端点 200/404 + Playwright 两组 optgroup 渲染断言(§0 表);Kotlin:`ProblemApiHttpTest.kt` 6 例(`favorites endpoint returns study plans and my favorites for capable platform`、`…degrades my favorites to empty when probes fail`、`…maps non-capable platform to 404`、`problems endpoint with favorite param queries study plan list and persists items`、`problems endpoint favorite takes precedence over company`、`…non-capable platform with favorite param to 404`);`LeetCodeProblemServiceTest.kt` 6 例(`favorite query passes slug and filters…`、`company query keeps wire protocol after favorite generalization`、`fetchMyFavorites parses favoritesLists graphql shape without rest fallback`、`…cn variant derives count from questions and slug from id`、`…falls back to rest list_favorites when graphql fails`、`…degrades to empty list when both probes fail`);vitest `problems-page.test.ts` 6 例(`renders the favorite dropdown with two optgroups on leetcode platforms only`、`hides the my-favorites optgroup when myFavorites degrades to empty`、`queries with favorite slug and clears company and period when a favorite is selected`、`clears the favorite selection when a company is selected`、`notes that keyword is ignored in favorite list mode`、`clears the favorite filter when switching platforms`) |
| ⑤ | CF 等平台无「提交测试」键;非挑战文件无浮动栏 | canShow 单测 ✅;能力门控静态核验;IDE 内肉眼确认留人工(B-4) | `ChallengeFileMatcherTest.kt` 7 例(`file outside all workspace roots does not match` 等);`ChallengeEditorActions.kt:109` `if (platform !is TestEvaluationCapable)` update 隐藏「提交测试」(BGT 快照查询,静态核验——该分支无直接单测);`LeetCodeTestEvaluationTest.'service and platform variants are test evaluation capable'` 证明力扣能力位 |
| ⑥ | 定版 1.3.0 | 自动化 ✅ | `build.gradle.kts` `version = "1.3.0"`;产物 `build/distributions/codequest-1.3.0.zip`(主 jar `codequest/lib/codequest-1.3.0.jar` 含 §B 全部新类;plugin.xml 含 `editorFloatingToolbarProvider` EP 与 `CodeQuest.ChallengeEditor.FloatingToolbar` 组);CHANGELOG.md 新增 1.3.0 条目(2026-08-23) |

### B. 产物类核验(codequest-1.3.0.jar 内实测存在)

`ChallengeFloatingToolbarProvider` / `EditTestCasesAction` / `RunCloudTestAction` / `CloudTestCoordinator` / `TestRunPrep` / `TestCasesDialog` / `TestCasesEditState` / `TestRunResultDialog` / `TestRunResultState` / `ChallengeEditorGate`(以上 ui);`TestEvaluationCapable` / `TestRunRequest` / `TestRunResult` / `TestRunStatus` / `FavoriteQueryCapable` / `FavoriteList` / `FavoriteProblemPage`(以上 spi);`LeetCodeTestEvaluationService` / `LeetCodeStudyPlans` / `ProblemApi$listFavorites` / `FavoritesResponseDto` / `StudyPlanItemDto` / `MyFavoriteItemDto`(以上平台/端点)。

## C. 人工走查步骤(runIde 沙箱,建议中文 IDE)

准备:`./gradlew runIde` 启动沙箱;力扣中国账号已登录(本次沙箱会话沿用即可)。

### C-1 三键浮现与用例互通(验收①)

1. 题库页任选一题「开刷」(Java)→ 打开生成的工作区文件,编辑器右上应浮现三键:修改测试用例 ✏️ / 提交测试 ▶️ / 提交 ⬆️。
2. 点「修改测试用例」→ 对话框改一行输入/期望、增一行、删一行 → 确定 → 挑战工具窗「工作区」tab 测试用例区应同步显示变更。
3. 再随便打开一个非挑战文件(如项目自带 README)→ 浮动栏应消失。

预期:三键仅在挑战文件上出现;对话框增删改与工具窗互通。

### C-2 提交测试结果对话框(验收②)

1. 在 C-1 的挑战文件写入正确解法 → 点「提交测试」→ 结果对话框:汇总「X/Y 通过」+ 每用例 输入/期望/实际/对错;运行时间/内存。
2. 故意写编译错误(如删分号)再跑 → 对话框应显示编译错误全文(表格被错误区替换)。

预期:两态展示正确;轮询期间按钮防重入。

### C-3 提交链路(验收③)

1. 正确解法 → 点「提交」→ 沿用既有链路:提交通知 + 挑战窗「提交记录」tab 新记录。

### C-4 平台门控(验收⑤)

1. 开刷一个 CodeForces 挑战文件 → 浮动栏只有两键(修改测试用例/提交),无「提交测试」。
2. 未登录平台开刷力扣题 → 「提交测试」「提交」置灰。

### C-5 题单下拉闭环(验收④补充)

1. 题库页(力扣中国)「题单」下拉选「热题 100」→ 列表切换为题单内容;「公司」下拉被清空。
2. 选一个公司 → 「题单」回到「全部题库」(互斥双向)。
3. 题单内任选一题「开刷」→ 正常建挑战。

(渲染与互斥逻辑已由沙箱 Playwright + vitest 覆盖;此处补列表内容与开刷闭环。)
