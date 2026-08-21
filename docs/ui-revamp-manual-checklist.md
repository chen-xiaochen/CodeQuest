# UI 重构集成验收清单(loop-20260821-ui-revamp / WP52)

- 日期:2026-08-21
- 规格:`docs/superpowers/specs/2026-08-21-ui-revamp-design.md` §8(八条验收)
- 结论速览:八条中 6 条自动化覆盖、1 条静态核验+人工复核、1 条纯人工(提交跳转);另有重启场景(④)单测覆盖端点但需 runIde 人工确认

## 0. 全量门禁结果(2026-08-21 实测)

| 门禁 | 命令 | 结果 |
|---|---|---|
| Kotlin | `./gradlew clean check test buildPlugin --console=plain` | BUILD SUCCESSFUL;696 tests / 0 failures / 0 errors / 1 skipped(基线 696);zip 产物 `build/distributions/codequest-1.1.0.zip`(27 MB,版本号未定版) |
| 前端 | `cd frontend && npm run test && npm run build` | 18 文件 159 tests 全过(基线 159);vite build 成功 |

## A. 自动化验收映射表

| # | 验收条目 | 结论 | 证据(测试文件 / 用例名) |
|---|---|---|---|
| ① | 中文 IDE 无英文残留 | 静态核验通过;IDE 内实际渲染留人工(B-1) | 前端六页 + landing + components grep `placeholder/title/label="英文"` 与模板英文文本节点:0 命中;Kotlin 四类(`CodeQuestToolWindowFactory` / `ChallengeWorkspacePanel` / `NewChallengeDialog` / `ImportTestCasesDialog`)grep 非 bundle 英文串:0 命中 |
| ② | 平台下拉仅已登录 + 全未登录回落力扣中国 + 提示条 | 自动化 ✅ | `frontend/tests/pages/problems-platform-gate.test.ts`(6 例:`filterPlatformsByAuth 仅保留 loggedIn=true…`、`全未登录返回空数组`、`pickDefaultPlatform 全空回落力扣中国(leetcodecn)` 等);`problems-page.test.ts`:`filters the platform dropdown to logged-in platforms only`、`falls back to 力扣中国 with a hint when no platform is logged in` |
| ③ | 账号区用户名 + 退出 | 自动化 ✅ | `problems-page.test.ts`:`renders account rows for every platform with explicit login/logout buttons`、`shows the platform userId on a logged-in row when available`、`sends auth:logout when clicking the logout button and refreshes status after the result`、`refreshes auth status after auth:login-result from an account-area login` |
| ④ | 重启后已登录平台仍显示(静默恢复) | 端点/平台层自动化 ✅;IDE 重启场景留人工(B-3) | `kotlin/src/test/kotlin/com/codequest/infra/messaging/AuthApiHttpTest.kt`:`auth refresh reflects restored sessions across three outcomes`、`auth status passes through current session userId`;六平台 AuthTest 的 `restoreSession silently restores valid stored credentials` / `restoreSession stays silent returning null without stored credentials`(如 `LeetCodeAuthTest.kt`) |
| ⑤ | 模版门控:默认 Java / 未配置语言不显示 / 空配置拦截 | 自动化 ✅ | `TemplateAvailabilityTest.kt`(usableLanguages 纯函数);`ChallengeTemplateWiringTest.kt`:`language gate for platform lists usable languages in declaration order and defaults to java`、`language gate is empty when style has no configured templates`;`ChallengeCreateBridgeTest.kt`:`java is preferred when configured under the platform style`、`falls back to first usable language when java unconfigured`、`language decision fails when no language configured under the platform style` |
| ⑥ | 老用户升级:语言模版双风格可见 | 自动化 ✅ | `CodeQuestSettingsTest.kt`:`旧键(纯语言键,无冒号)双写归一迁移` 用例组(loadState 旧键 → `acm:L` + `leetcode:L` 双写) |
| ⑦ | 提交后焦点跳挑战窗提交记录 tab 并按 challengeId 过滤 | 人工(B-6) | 导航为 IDE 工具窗激活行为,无单测覆盖;子视图 JCEF URL 携 challengeId 已随 WP51 实现 |
| ⑧ | 本地搜索入口(题库页子视图) | 自动化 ✅ | `problems-page.test.ts`:`switches between 平台题库 and 本地搜索 subviews keeping both alive`(惰性挂载 + 双子视图保活);`pages-smoke.test.ts`:`['search', SearchApp, '本地搜索']` |

## B. 人工走查步骤(runIde 沙箱)

准备:`./gradlew runIde` 启动沙箱 IDE,语言切中文(Settings → Appearance & Behavior → System Settings → Language and Region → 简体中文,重启沙箱)。建议两类初始状态各走一遍:干净配置(首次)与带旧 1.1.x 配置的升级(可直接用本机已有配置目录)。

### B-1 三 tab 中文显示名 + 无英文残留(验收①)

1. 左侧主侧栏找到 CodeQuest 工具窗,确认只有 3 个 tab:题库 / 竞赛 / 统计(无 Search tab)。
2. 逐一打开三 tab,再打开题面工具窗(CodeQuest 题面)与挑战工具窗(CodeQuest 挑战)。
3. 挑战工具窗内确认两 tab:工作区 / 提交记录。
4. 全程检查按钮、下拉、空态、提示条均为中文;发现英文残留记录截图与位置。

预期:tab 名与各面板文案全中文;英文 IDE 下启动则应显示英文(bundle 双语)。

### B-2 题库页平台门控 + 账号区交互(验收②③)

1. 题库 tab 顶部账号区:六平台(力扣中国/力扣国际/CodeForces/AtCoder/洛谷/HackerRank)全部常驻;未登录行显示「登录」。
2. 点击某未登录平台「登录」→ 走既有 cookie 登录流程 → 成功后该行显示用户名 + 「退出」,平台下拉即时出现该平台。
3. 全部退出(逐个点「退出」)→ 平台下拉回落「力扣中国」,旁边出现提示条「当前无已登录平台,默认展示力扣中国…」。
4. 竞赛 tab 重复验证:平台下拉同样只列已登录平台;全未登录时列表区显示引导文案。

预期:下拉选项=已登录平台集合;账号区用户名与 status 一致;退出后平台从下拉消失。

### B-3 重启静默恢复(验收④)

1. 在 B-2 基础上保持 ≥1 平台已登录,完全关闭沙箱 IDE。
2. 再次 `./gradlew runIde`(同一用户配置目录)打开,进入题库 tab。
3. 观察首屏:先渲染 status 快照,后台 `/api/auth/refresh` 完成后已登录平台(含用户名)自动回到下拉。

预期:无需任何交互,重启前已登录的平台恢复显示;弱网下首屏不阻塞(refresh 异步增量刷新)。

### B-4 新建挑战模版门控(验收⑤)

1. 干净配置:挑战窗 → 「新建挑战」选任一平台 → 语言下拉应为空,面板显示「请先在设置中配置代码模版」+「打开设置」,创建按钮不可用。
2. 通过「打开设置」进入设置页,选 ACM 竞赛版 + Java,编辑器预填默认文本,点「应用」→ 回到新建挑战:语言下拉出现 Java 且**默认选中**。
3. 只配置 Python(清空 Java 文本后应用)→ 新建挑战下拉只有 Python,Java 不出现。
4. 题面页「一键开刷」:配置了 Java 优先 Java;无任何配置时报错并引导去设置。

### B-5 设置页双风格 + 帮助(验收⑤⑥)

1. Settings → CodeQuest:模版区有「风格」「语言」两个下拉与「? 帮助」按钮。
2. 「? 帮助」打开帮助对话框:变量表 / 提交区域标记说明 / 文件名规则 / 风格说明四节齐全,中文显示。
3. 老配置升级(带 1.1.x 配置目录启动):旧 `java` 键模版同时出现在 ACM 与 LeetCode 两风格下;「恢复默认文本」只重置编辑器文本,不改变启用状态;清空文本 + 应用后该语言从可用列表消失。

### B-6 提交后跳转提交记录 tab(验收⑦)

1. 打开任一挑战工作区,写好提交区域代码,点「提交」。
2. 提交完成后:焦点自动跳到挑战工具窗「提交记录」tab。
3. 确认列表按当前挑战 challengeId 过滤(只显示该挑战的提交);切换左侧挑战树选中项后,tab 内容随 challengeId 联动刷新。

### B-7 本地搜索子视图(验收⑧)

1. 题库 tab 顶部子视图切换「平台题库 | 本地搜索」。
2. 切到「本地搜索」:可按关键字/标签搜索本地题目;切回「平台题库」列表状态保留(双视图保活)。
3. 落地页(欢迎页入口)确认「本地搜索」入口仍可直达 `/search/` 页。
