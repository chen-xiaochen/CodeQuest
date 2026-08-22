# 编辑器三键（测试用例/提交测试/提交）+ 题库页题单/学习计划

消费规格：docs/superpowers/specs/2026-08-22-editor-actions-and-favorites-design.md（2026-08-22 获批，commit 1ad0361）。
两个目标：挑战文件编辑器右上浮现三键（修改测试用例 / 提交测试=力扣 interpret 云端 / 提交=复用现链路）；题库页新增「题单」下拉（学习计划内置清单 + 我的题单登录拉取，与公司下拉互斥）。目标版本 1.3.0。

关键事实（Teamee 执行时引用）：
- 原版 interpret 链路（git `a8f69a0^` LeetCodeApi.scala:300-350）：`POST /problems/{slug}/interpret_solution/`，JSON body snake_case `{lang, data_input, question_id, typed_code}` + CSRF → `{interpret_id}` → 轮询 `GET /submissions/detail/{interpretId}/check/`；结果字段全集见 `leetcode/models/runCode.scala`（code_answer[]/expected_code_answer[]/compare_result/total_correct/total_testcases/full_compile_error/full_runtime_error/status_runtime/status_memory）
- questionId 获取：`fetchProblemDetail(slug)`（GraphQL questionData 已解析 questionId，1.2.4 起在用）
- 浮动栏 EP：`com.intellij.editorFloatingToolbarProvider`（平台 2026.2）；接口确切签名以本地 IDE 依赖源为准（WP 内首步核对）
- `/api/problems` 分支先例：`ProblemApi.listProblems` 中 `company` 非空走 `listCompanyProblems`——`favorite` 参数照此挂分支
- 提交链路复用：`SubmissionCoordinator.submitFile(file, text)`（区域提取→SPI submit→轮询→落库→通知→提交记录 tab）
- 用例存储：`testCaseRepository`（挑战工具窗测试用例区同源，写库即两边互通）

## 实现 SPI TestEvaluationCapable 与力扣 interpret 云端测试
- [ ] spi 新增 `TestEvaluationCapable` 接口：`watchTestRun(request: TestRunRequest): Flow<TestRunStatus>`；模型 `TestRunRequest(problemRemoteId, questionId, languageId, code, dataInput)`、`TestRunStatus`（Pending / Finished(TestRunResult)）、`TestRunResult(correctCount, totalCount, codeAnswers, expectedCodeAnswers, compareResult, statusRuntime, statusMemory, compileError, runtimeError, state)`
- [ ] 力扣实现 `LeetCodeTestEvaluationService`：POST `/problems/{slug}/interpret_solution/`（snake_case body + 复用现有 LeetCodeApi csrf 通道）→ interpretId → 轮询 `/submissions/detail/{interpretId}/check/`（轮询节奏对齐 BaseSubmissionFlow 语义：1s 起步指数退避封顶，state != SUCCESS 继续，非终态字段宽容解析）
- [ ] check 响应映射：code_answer[]/expected_code_answer[]/compare_result/total_correct/total_testcases/full_compile_error（优先全文）/full_runtime_error/status_runtime/status_memory/state；CE 时 message 取 full_compile_error 全文
- [ ] LeetCodePlatform 实现该能力接口（leetcode + leetcodecn 两变体共用，域名走 variant）
- [ ] [unit] FakeOJTransport 单测：interpret_solution 请求体（snake_case/字段值透传）与 interpret_id 解析；check 轮询→终态字段全集映射；CE/RE 全文优先级；非终态（PENDING）继续轮询
- [ ] [unit] 平台能力归属测试（service is TestEvaluationCapable）

## 实现测试用例编辑对话框与云端测试结果对话框
先完成「实现 SPI TestEvaluationCapable 与力扣 interpret 云端测试」
- [ ] `TestCasesDialog`（DialogWrapper + JBTable 两列「输入/期望输出」）：工具条增行/删行/清空；打开时装载当前挑战用例（testCaseRepository.findByChallenge），「确定」逐行 upsert + 缺席行删除（写库与挑战工具窗测试用例区同源互通）
- [ ] 行编辑纯函数抽离（列表↔表格模型转换、diff 出增/删/改），可脱离 Swing 单测
- [ ] `TestRunResultDialog`（DialogWrapper）：汇总行「X/Y 用例通过」+ JBTable 每用例（输入摘要/期望/实际/通过标记，通过判定取 compare_result 逐位或 codeAnswers 与 expectedCodeAnswers 逐项比较）+ compileError/runtimeError 非空时全文区替代表格
- [ ] [unit] 表格模型转换与增删改 diff 纯函数测试
- [ ] [unit] 结果对话框数据组装纯函数测试（compare_result 逐位对错、CE 替代表格分支）

## 接线编辑器浮动工具栏三键
先完成「实现测试用例编辑对话框与云端测试结果对话框」
- [ ] 核对平台 2026.2 `FloatingToolbarProvider` 接口签名（本地 IDE 依赖源/反编译），实现 `ChallengeFloatingToolbarProvider`（EP `com.intellij.editorFloatingToolbarProvider`；actionGroupId=`CodeQuest.ChallengeEditor.FloatingToolbar`；canShow=文件属于挑战工作区）
- [ ] plugin.xml：extensions 挂 `<editorFloatingToolbar>`；actions 注册 group `CodeQuest.ChallengeEditor.FloatingToolbar` 与三 action（修改测试用例 / 提交测试 / 提交，带图标）
- [ ] 「修改测试用例」action：按编辑器文件定位 challenge（复用 WorkspaceTargetResolver/挑战集合枚举语义）→ 打开 TestCasesDialog
- [ ] 「提交测试」action：saveDocument → 区域提取（SubmitRegionExtractor，缺标记报错同提交流程文案）→ 拉用例拼 dataInput（\n 连接，原版语义）→ questionId 经 fetchProblemDetail 取 → TestEvaluationCapable.watchTestRun 收集至终态 → TestRunResultDialog；平台无能力该键隐藏；未登录置灰；进行中防重入（同文件门禁）
- [ ] 「提交」action：saveDocument → 复用 `SubmissionCoordinator.submitFile`（零新逻辑）
- [ ] canShow 门控性能：路径前缀匹配工作区基目录 + 挑战集合缓存（DB 变更失效），不逐次查库
- [ ] PluginBundle 英中同步：三 action 文案、两对话框全部文案、提交测试错误态（非挑战文件/平台不支持/未登录/无用例/区域标记缺失）
- [ ] [unit] canShow 判定纯函数测试（工作区内/外、多基目录）
- [ ] [unit] 提交测试链路纯函数部分（dataInput 拼装、错误分支文案选择）

## 实现后端题单：favorite 查询泛化 + 学习计划清单 + 我的题单 + 端点
- [ ] SPI 泛化：`queryFavoriteProblems(favoriteSlug, filters, offset, limit)`（favoriteQuestionList 查询抽出；`queryCompanyProblems` 委托之，参数协议不变防回归）
- [ ] 学习计划内置清单常量（slug + 中文名，约 20 个：热题 100 top-100-liked、面试经典 75 题、LeetCode 75、数据结构入门/计划、初级/中级/高级算法、二分查找/哈希/动规/图论系列、SQL 50 等）——对照 leetcode.cn/studyplan/ 官方核对 slug
- [ ] `fetchMyFavorites()` 能力方法：接口探测（优先 GraphQL `favoriteFolders`，备选 REST `GET /api/list_favorites/`）；两路都失败返回空表（降级不抛错）
- [ ] `GET /api/favorites?platform=` 端点：`{studyPlans:[{slug,name}], myFavorites:[{slug,name,questionCount}]}`；无能力平台 404（同 /api/companies 探测语义）
- [ ] `/api/problems` 挂 `favorite=<slug>` 分支：走 queryFavoriteProblems；favorite 与 company 同给时 favorite 优先、company 忽略
- [ ] [unit] queryCompanyProblems 委托泛化后回归测试（请求参数快照不变）
- [ ] [unit] fetchMyFavorites 两种接口形态解析测试（favoriteFolders GraphQL / list_favorites REST）与双失败降级
- [ ] [unit] /api/favorites 端点 HTTP 测试（结构/404 语义）；favorite 分支与互斥兜底测试

## 实现前端题库页题单下拉
先完成「实现后端题单：favorite 查询泛化 + 学习计划清单 + 我的题单 + 端点」
- [ ] 过滤行新增「题单」下拉（渲染条件：力扣系平台）：`<optgroup label="学习计划">`（/api/favorites studyPlans）+ `<optgroup label="我的题单">`（myFavorites 非空才渲染）；默认「全部题库」
- [ ] 与「公司」下拉互斥：选题单→清公司+期间；选公司→清题单（双向）
- [ ] 选中后列表查询带 `favorite=<slug>`（复用现有 reload 参数链路）；题单模式下 keyword 忽略提示（同公司模式文案）
- [ ] 我的题单拉取失败/未登录 → 只显示学习计划组，静默降级不打扰
- [ ] 前端类型定义（FavoriteSummary）与 api.ts 请求函数
- [ ] [unit] vitest：下拉渲染条件（力扣系/其他平台）、optgroup 两组结构、互斥双向清空、favorite 参数传递、myFavorites 失败降级
- [ ] [acceptance] `npm run test && npm run build` 全绿

## 集成验收与定版 1.3.0
先完成前面全部单元
- [ ] `./gradlew test check buildPlugin` 全绿；前端 `npm run test && npm run build` 全绿
- [ ] runIde 沙箱走查：开刷力扣题（Java）→ 编辑器右上浮现三键；改用例保存后挑战工具窗同步；「提交测试」结果对话框展示每用例对比；「提交」走现有链路（通知+提交记录 tab）
- [ ] runIde 沙箱走查：题库页「题单」下拉出现学习计划组（选热题 100 列表切换且开刷正常）；登录后「我的题单」组出现；与公司下拉互斥
- [ ] runIde 沙箱走查：CF 等平台编辑器无「提交测试」键；非挑战文件无浮动栏
- [ ] CHANGELOG 1.3.0 条目 + build.gradle.kts 版本 bump + 构建产物 zip
- [ ] [acceptance] spec §4 六条验收全过（对话框互通/云端测试结果/提交流程/题单下拉/能力门控/定版）

## 成功标准
- 六个工作单元 checklist 全部通过
- `./gradlew test check buildPlugin` 与前端 `npm run test && npm run build` 全绿
- spec §4 六条验收：三键显隐与链路、用例对话框与工具窗互通、云端测试结果完整（对比表/CE 全文）、题单下拉两组与互斥、非力扣平台门控、定版 1.3.0 产出
- 无破坏性回归：既有提交链路/公司题单/模版体系行为不变
