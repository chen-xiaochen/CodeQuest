# UI 重构：面板精简 · 平台登录门控 · 模版风格化 · 界面中文化

消费规格：docs/superpowers/specs/2026-08-21-ui-revamp-design.md（2026-08-21 获批）。
四个目标：工具窗 4→3/主侧栏 tab 4→3；未登录平台不出现在平台下拉（全未登录回落力扣中国）+账号区显示用户名可退出；模版按 ACM/LeetCode 风格×语言显式启用、默认 Java、设置页带帮助；Kotlin 与前端界面中文化。

## SPI 与平台层：模版风格定义与登录会话快照
- [ ] core.workspace 新增 TemplateStyle 枚举（ACM=stdin/stdout 竞赛版 / LEETCODE=Solution 函数版）
- [ ] spi.PlatformInfo 增加 templateStyle 字段；六平台声明：力扣中国/国际→LEETCODE，CodeForces/AtCoder/洛谷/HackerRank→ACM
- [ ] spi.AuthCapable 新增 currentSession(): LoginSession?（同步只读快照）与 suspend restoreSession(): LoginSession?（静默启动恢复：只尝试已存凭据注入+校验，绝不弹窗，失败返回 null）
- [ ] 六平台 auth 实现（LeetCodeAuth[CN/COM 共用]、CodeForcesAuth、AtCoderAuth、LuoguAuth、HackerRankAuth）补两方法：restoreSession 复用各实现的启动恢复段，currentSession 返回 session?.takeIf { verified }
- [ ] ChallengeTemplates.entryTemplate(style, languageId)：ACM Java=BufferedReader stdin 版、ACM Kotlin=readLine 版、LEETCODE=现状 Solution 版；Python/C++/Go 按风格给对应默认；regionMarkers/defaultCode/fileNameRule 与提交区域标记两行文案不动
- [ ] [unit] TemplateStyle 六平台映射测试
- [ ] [unit] entryTemplate(style, lang) 各风格快照测试（含两风格默认模板必须含 beginRegion/endRegion）
- [ ] [unit] 各平台 restoreSession 静默性测试（无凭据返回 null 不触发 prompt；有效凭据校验成功后 session/verified 就位）

## 设置层：风格×语言模版存储与迁移
先完成「SPI 与平台层：模版风格定义与登录会话快照」
- [ ] CodeQuestSettings.State.templates 键升级为 "style:languageId"；loadState 检测旧格式键（无冒号=纯语言键）一次性双写到 acm:L 与 leetcode:L 并归一化为新格式
- [ ] entryTemplateOverride(style, languageId)：非空白键=启用返回原文；空白/缺失返回 null（运行时无内置兜底）
- [ ] TemplateDraftsState 扩展为 (风格,语言) 双键：editorText 预填对应风格内置默认文本；空白=未配置；「恢复默认」=文本回填内置默认（不改启用状态）
- [ ] 新增纯函数 TemplateAvailability：usableLanguages(style, persisted) 与 defaultLanguage(可用列表)=Java 优先否则第一个
- [ ] [unit] 旧键迁移双写测试（loadState 归一化、幂等）
- [ ] [unit] TemplateDraftsState 双键全语义测试（TemplateDraftsStateTest 扩展：空白/恢复默认/与内置一致=已配置）
- [ ] [unit] TemplateAvailability 测试（空列表、Java 优先、风格隔离）

## 设置页：风格+语言编辑器与模版帮助
先完成「设置层：风格×语言模版存储与迁移」
- [ ] CodeQuestSettingsConfigurable Templates 区改造：风格下拉（ACM 竞赛版/LeetCode 版）+语言下拉+编辑器+「恢复默认文本」按钮，接线双键 TemplateDraftsState；提示文案（空白=该语言不可用，修改后点「应用」生效）
- [ ] 「帮助」按钮 → 帮助对话框（DialogWrapper）：①变量表（challenge.* 六变量、velocity.* 工具、beginRegion/endRegion）②提交区域标记说明（两行标记必须保留，标记之间代码才会被提交）③文件名规则 ④风格说明（哪些平台用哪个风格）
- [ ] PluginBundle 英中同步新增 settings.templates.style.* 与 help.* keys
- [ ] [unit] 设置面板草稿状态接线测试（isModified/apply 往返、风格切换 flush）

## 创建链路：模版门控与默认 Java
先完成「设置层：风格×语言模版存储与迁移」
- [ ] ChallengeTemplateWiring.createChallengeWithTemplate 改为按平台风格读 entryTemplateOverride(styleOf(problem.platformId), languageId)，非空白必传 template（core 服务签名不动）
- [ ] NewChallengeDialog：平台选定后语言下拉=usableLanguages(平台风格)；默认选中 Java（Java 无模版时选第一个可用）；列表为空显示「请先在设置中配置代码模版」+「打开设置」按钮并禁止创建
- [ ] ChallengeCreateBridge（题面一键开刷）：Java 优先→第一个可用语言→全无可用回错误并引导去设置
- [ ] ChallengeWorkspacePanel.createNewSolution（修复#45 语义）：读当前 (styleOf(题目平台), 语言) 覆盖，无则用落库模板
- [ ] [unit] ChallengeTemplateWiringTest 适配（风格维度覆盖/未配置路径）
- [ ] [unit] 两条创建路径的可用语言与默认语言接线测试

## 认证端点：用户名透传与静默恢复
先完成「SPI 与平台层：模版风格定义与登录会话快照」
- [ ] AuthApi：userId 从恒 null 改为透传 currentSession()?.userId（未登录仍 null）
- [ ] 新增 POST /api/auth/refresh：经 AuthPlatformSources 并发跑 restoreSession()（网络校验），返回与 /api/auth/status 同构响应
- [ ] ResourceHttpServer 挂载新端点
- [ ] frontend/src/lib/api.ts 新增 refreshAuthStatus()
- [ ] AuthLoginBridge 面向用户展示的错误串中文化（unknown platform/has no login 等入 bundle）
- [ ] [unit] AuthApi userId 透传测试
- [ ] [unit] refresh 端点逻辑测试（restoreSession 并发调用、响应同构、失败平台如实 false）

## 前端题库页：平台门控+账号区+本地搜索子视图
先完成「认证端点：用户名透传与静默恢复」
- [ ] 账号区重设计：全部六平台常驻；已登录=平台名+用户名+显式「退出」按钮；未登录=「登录」按钮（触发既有 auth:login 流程，替代点击整行切换）
- [ ] 平台下拉过滤：fetchPlatforms ⋈ fetchAuthStatus 仅留 loggedIn=true；全未登录回落 leetcode.cn+提示条（「当前无已登录平台，默认展示力扣中国；在上方账号区登录后即可切换其他平台」）
- [ ] 加载时序：GET /api/auth/status 瞬时渲染→POST /api/auth/refresh→刷新下拉与账号区；收到 auth:login-result/auth:logout-result 后重拉两接口
- [ ] search/App.vue 列表逻辑抽为 SearchPanel.vue 组件；problems/App.vue 顶部加子视图切换「平台题库｜本地搜索」（/search/ 路由保留）
- [ ] 题库页+本地搜索子视图中文化（筛选/按钮/空态/提示条）
- [ ] [unit] vitest：平台过滤与全未登录回落 leetcode.cn 的纯逻辑测试（抽纯函数或 composable）

## 前端其余页面：中文化与竞赛门控
先完成「认证端点：用户名透传与静默恢复」（仅竞赛门控项依赖；纯文案项无依赖可并行）
- [ ] contest 页：平台列表过滤已登录+全未登录引导文案；PHASE_LABELS 中文化（未开始/进行中/等待系统测试/系统测试中/已结束）+提醒订阅文案
- [ ] stats 页中文化：汇总卡片（总提交/通过数/AC 率/当前连续/最长连续）、热力图/趋势标题、空态
- [ ] problem-view 页中文化：一键开刷/登录/题解排序/缩放工具栏标签
- [ ] submissions 页中文化：输入/期望输出/实际输出、详情/代码 tab、verdict.ts 判定文案与 Kotlin bundle 中文对齐
- [ ] landing 页中文化：入口标签对齐新结构（题库/竞赛/统计/题面/提交记录/本地搜索）

## Kotlin UI 中文化与面板结构重组
（中文化文案先行；面板重组需本单元内的 bundle keys 就绪）
- [ ] CodeQuestToolWindowFactory PANELS 4→3（题库/竞赛/统计），标题/描述/JCEF 降级文案走 PluginBundle
- [ ] ChallengeWorkspacePanel 全文案 bundle 化（约 25 条：Challenge/刷新/新建挑战/解法/测试用例分组与按钮、对话框文案、运行输出「用例 N：通过/失败」「汇总：x/y 通过」）
- [ ] NewChallengeDialog/ImportTestCasesDialog 文案 bundle 化
- [ ] plugin.xml 工具窗 displayName 更新：「CodeQuest」「CodeQuest 题面」「CodeQuest 挑战」
- [ ] ChallengeWorkspacePanel 外层包 JBTabbedPane：「工作区」tab（现有 Swing 面板）+「提交记录」tab（JCEF 加载 /submissions/?challengeId=当前选中挑战，随选中联动、惰性创建，JCEF 不可用降级中文提示）
- [ ] SubmissionCoordinator 提交完成导航：从打开提交记录工具窗改为激活挑战工具窗的提交记录 tab（保留 challengeId 定向过滤）
- [ ] 删除 SubmissionsToolWindowFactory 及 plugin.xml 注册
- [ ] [acceptance] IDE 工具窗 3 个、主侧栏 tab 3 个；提交后焦点跳挑战窗提交记录 tab 且按 challengeId 过滤

## 集成验收与收尾
先完成前面全部工作单元
- [ ] ./gradlew test 全绿；frontend `npm run test` + `npm run build`（含 vue-tsc）通过
- [ ] [acceptance] 规格第 8 节验收清单 8 条逐条手动走查：中文无残留/平台门控与回落/用户名显示与退出/重启静默恢复/模版门控默认 Java/老配置双风格迁移可见/提交导航/本地搜索入口
- [ ] CHANGELOG.md 按 SemVer 更新（MINOR，1.2.0 候选）

## 成功标准
- 全部工作单元 checklist 通过；gradlew test 与前端 vue-tsc/vitest/build 全绿
- 平台下拉仅显示已登录平台；全未登录回落力扣中国+提示条；账号区显示用户名且可退出登录；IDE 重启后已登录平台经静默恢复仍显示
- 未配置模版的语言不出现在语言下拉，默认语言 Java；未配置任何模版时创建被拦截并引导去设置；老用户语言模版迁移后在两个风格下可见
- 工具窗 3 个、主侧栏 tab 3 个，提交记录并入挑战窗且提交后定向跳转
- 中文 IDE 下五块界面（主侧栏/题面/挑战窗/设置页/各对话框）无英文残留；前端页面全部中文
