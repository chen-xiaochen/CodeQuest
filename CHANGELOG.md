## 1.9.0 - 2026-10-03
### Added
- 题面页收藏星标(默认收藏夹写):header 星标两态按钮(空心☆/实心★,CSS 变量两态),点击乐观切换→`POST /api/favorite/toggle`→失败回滚+错误提示(401 auth-expired「请先登录」,其余透传),成功保持并写 localStorage 本地记忆(`cq-fav-{platform}-{remoteId}`)。协议实测注记(2026-10-03 双站):`questionData` 的 QuestionNode 无任何收藏态字段(isFavorited 类)→初始态按中性空心+本地记忆降级,不引入新查询协议;默认夹经 `favoritesLists` 定位——FavoriteNode 无 isDefaultFavorite 标记,按 `name=="Favorite"` 认定+fallback 首个含 idHash 夹(插件源 getUser 锁定),idHash 进程内 @Volatile 缓存;mutation `addQuestionToFavorite(questionId: String!…)` 实为 String! 非 ID!,未登录 CN 站走 errors 信封「用户未登录」(401 透传),ok=false/errors 抛领域异常(502);题目不在夹中移除视为幂等成功。
- 题面页「提示」折叠(仅 LC 系平台渲染):与题解按钮同区,点击才拉取(懒加载,未点击零请求)→逐条 `<details><summary>提示 N</summary>正文</details>` 默认收起逐条展开,正文文本插值+HTML 实体解码(&lt;code&gt; 等六实体,双转义不二次解码);空数组「无提示」;再点收起整个区块再点展开不重拉。数据走 `GET /api/problem/{remoteId}/hints?platform=`,SPI `ProblemHintsCapable.fetchHints` 失败降级空表不抛(协议变动不致败页,questionHints 匿名可查:two-sum 3 条含 HTML 实体/add-two-numbers 空数组实测)。
- 编辑器浮动栏第四键「全示例测试」:挑战文件浮动栏在 提交测试/提交 之后新增一键(图标 RunAll,英中文案 bundle)——拉取题面全部 exampleTestcases(与 WP55 questionId 同通道:`fetchProblemDetail` 一次同取)→保序过滤空白元素→`join("\n")` 组装 dataInput→复用云测链路 `watchTestRun` 单次 interpret 收集至终态;门控照「提交测试」键(非挑战文件/平台无 TestEvaluationCapable 隐藏,未登录/同文件云测进行中置灰);空(或全空白)示例通知「无示例测试用例」不发起云测;结果推送右窗「测试用例」tab 展示 X/Y 汇总+每用例输入/期望/实际对比,页标题显示「全部示例测试结果」区分「云端测试结果」(payload 可选 mode 字段,缺省不携带零回归)。
- REST 端点新增:`POST /api/favorite/toggle`(成功 `{ok:true}`/参数 400/未登录 401 auth-expired/站方失败 502 oj-error/无能力平台 404)、`GET /api/problem/{remoteId}/hints?platform=`(`{hints:[...]}`/SPI 失败降级 200 空表/无能力平台 404)。
### Changed
- 数据库 schema v4 迁移(照 v2/v3 先例,幂等):`problem_content` 补 `question_id` 列——题目数字主档 id 随题面落库并经 `GET /api/problem/{remoteId}` 响应透出(前端星标/全示例测试寻址用;迁移前旧缓存行 null,首个 remote 重拉自动补全,旧行为零破坏)。spec 原声明「无迁移」,此为 questionId 前端寻址的最小改动替代案(备选为前端另发新查询),如实记录该适配。
### 注记(如实适配)
- 全示例测试结果展示:spec 写「复用 TestRunResultDialog」,该对话框自 1.5.0 起已删除(结果改走右窗「测试用例」tab 桥推送),本次照现状复用桥推送通道,payload 增可选 `mode` 字段区分标题,未新增对话框。

## 1.8.0 - 2026-10-03
### Added
- 力扣中国每日一题:题库页(CN 平台)顶部「今日题目」常驻横幅卡片——日期+题号+标题+难度徽标(复用 diff-badge)+标签 chips,点击与题库行同源直达题面(nav:openProblem 携 titleSlug)。数据走 CN 站 `questionOfToday` GraphQL(2026-10-03 实测:`todayRecord` 为单元素数组,需 Referer/Origin 头,translatedTitle 优先作中文标题),新 SPI `DailyQuestionCapable` 三态降级(拉取失败/字段缺失返回 null),新端点 `GET /api/daily-question?platform=`(当日 volatile 进程内缓存跨日失效;无能力平台 404,降级 200 全字段 null);前端非 CN/404/全 null 照题单能力探测惯例静默不渲染,挂载+平台切换时刷新,卡片隐藏不致败题库页。
- 力扣竞赛回顾(仅 CN 变体):`LeetCodeContestService` 承载 `ContestCapable`——`contestHistory`(2026-10-03 实测:startTime/duration 均秒级)推导 phase 窗口(BEFORE/CODING/FINISHED,clock 注入),`contest/api/info` 解析题目行(snake_case,不存在抛领域异常、题目缺失降级空表、joinable 恒 false);竞赛 tab LC 平台经能力探测自动出现,行内「题目」详情展开与 CF 共用单槽(Q 序号+标题,点题 nav:openProblem 携 titleSlug),phase 中文映射对 LC 三值子集兼容。
- 题库排序五档+隐藏已解决:排序循环按钮扩为 默认→题号→难度→评分↑→评分↓——题号/难度为服务端档,`/api/problems` 新增 `sortBy` 查询参数(id/difficulty 映射协议枚举,2026-10-03 双站实测锁定白名单:CN V1 `filters.orderBy` / COM V2 `sortBy.sortField` 共用 FRONTEND_ID/DIFFICULTY,未知值回退默认或 CUSTOM;公司/题单模式天然忽略),进入/离开服务端档即 reload 带参且分页归零;评分↑↓为本地排序(仅对已加载行,按钮 title 标注);题单/公司模式循环跳过服务端两档,平台/题单/公司切换与重置均归位默认档。筛选行新增「隐藏已解决」checkbox(本地过滤已加载行,复用 statusOf 判定非 AC 隐藏,未登录无站内状态按本地提交聚合兜底 UNSOLVED 保留;localStorage 持久化默认关,与状态/评分区间叠加生效于 filter-note 提示与分页 x/总计数及空态)。
- REST 端点新增:`GET /api/daily-question?platform=`(其余竞赛端点为 WP23 既有,本次补 LC 平台实现)。

## 1.7.0 - 2026-10-02
### Added
- 题库页 zerotrac 难度评分:新增「评分」列(数值排序,无分显示 "-"),按用户竞赛分分差五档着色(绿<-200/蓝<-50/紫<50/黄<200/红≥200;无竞赛分自动退化绝对分档 <1400/1400-1799/1800-2199/2200-2599/≥2600,不着色情形保持中性),评分区间 min/max 数字过滤(本地过滤已加载行,同状态列模式),着色模式与列显隐存 localStorage;账号区显示竞赛分徽标(/api/rating/user-contest 会话缓存)+「刷新评分」按钮。数据源 `https://zerotrac.github.io/leetcode_problem_rating/data.json`,内置 2026-10 快照 2596 题兜底(`rating/leetcode-ratings.json`),启动 DB 优先/内置回退,在线刷新失败只提示不破坏 DB(schema v3 新表 `problem_rating`)。
- 站内提交历史同步:统计页「站内历史:已同步 N 条」+「同步历史」按钮——GraphQL `submissionList` 倒序分页拉取(页 20,遇到已存 (platform_id, remote_id) 即增量停止,200 页/4000 条上限保护),落 schema v3 新表 `remote_submission`(与本地 submission 独立,UNIQUE(platform_id, remote_id) 去重);统计 streak/热力图/AC 计数改为两表并集、同键本地优先,响应新增 `remoteSyncedCount`;新端点 `POST /api/submissions/sync`、`GET /api/submissions/remote`。
- 间隔重复复习:AC 后自动按 1/4/7/14/28/60 天排期(首 AC 生成,再 AC 移除今天及过去并记录 history;间隔经 `GET|PUT /api/review/config` 存 settings_kv,非法值回退默认);题库页新增「复习」子视图(已过期(红)/今日/未来三分组,入口徽标=今日到期数,行点击开题面),支持间隔配置与「标记完成」;题面页 header「加入复习」按钮(已排期显示目标日期,手动加入从明天零点起全量排)。AC 触发点 runCatching 不阻断提交通知。schema v3 新表 `review_schedule`(plan/history 两列 epoch 秒数组 JSON)。
- 题目计时器:题面页 header 计时角标 mm:ss(≥1h 显示 h:mm:ss),收到题面数据重置起表;AC 提交命中((platformId, problemRemoteId) 匹配且 verdict=Accepted)停表定格+本题耗时 toast;`enableTimer` localStorage 开关默认开。底层 SubmissionCoordinator 桥单槽改多播列表(单注册行为与旧版一致,多页可同时订阅),payload 增补 platformId/problemRemoteId。
- REST 端点新增清单:`GET /api/rating/status`、`POST /api/rating/refresh`、`GET /api/rating/user-contest?platform=`、`POST /api/submissions/sync`、`GET /api/submissions/remote`、`GET /api/review/today`、`POST /api/review/problems/{problemId}/add`、`POST /api/review/problems/{problemId}/complete`、`GET|PUT /api/review/config`。
- 数据库 schema v3 迁移(照 v2 先例,幂等):`problem_rating` / `remote_submission` / `review_schedule` 三表,既有表零改动,旧库自动升级不丢数据。

## 1.6.0 - 2026-08-26
### Added
- 启动恢复上次题目(题面会话):每次打开题目自动记录(项目级 codequest.xml),重开 IDE 后题面工具窗自动载入昨天的题目——题面走本地 SQLite 缓存,离线可用,不再需要重新搜索题目标题。
- 题库列表中英双语:中文标题为主、英文小字为辅(力扣中国 titleCn/title 双字段全保留;COM 站列表无中文字段,优雅降级英文);`problem` 表新增 `secondary_title` 列(schema v2 迁移,旧行为空、重新浏览自动回填)。
- 知识点筛选:题库页新增「知识点」多选 chips(内置力扣官方 72 个主题静态清单,如 二分查找/字符串/动态规划;值 = tag slug,普通列表/公司题单/学习计划三模式后端均按 slug 过滤);列表行与本地搜索的标签一律中文优先显示(tag 表新增 `slug`/`name_cn` 列,CN 数据自动 enrich,跨平台共享行不回退)。
### Fixed
- 学习计划为空(根因修复):学习计划数据源由 `favoriteQuestionList` 换为官方页同款 `studyPlanV2Detail`——旧查询对学习计划 slug 基本返回空集(双站匿名实测 19 个 slug 仅 2 个碰巧有数据)。新查询双站匿名可用(CN 15/19 计划直接有全量题目),全量返回后内存过滤(难度/知识点)+切片分页;计划条目附带站点/会员标记:COM 变体过滤掉 6 个 CN 专属计划,3 个会员计划名称带「(会员)」后缀且未订阅时题单空态显示可辨识文案(此前与普通筛选无匹配共用一条通用文案)。

## 1.5.1 - 2026-08-24
### Added
- 模板新变量 `${challenge.packageName}`:渲染时按工作区目录相对最近 source root 的路径算出(如 codequest.roman_to_integer_321bb012),模板写 `package ${challenge.packageName};` 即可自带包名;算不出(配置目录在项目外)时渲染为空,创建后的清理逻辑会移除残留的 `package ;` 空行(未写该变量的 Java 文件仍走自动注入兜底)。

## 1.5.0 - 2026-08-24
### Added
- 右窗「题面」工具窗改顶部三 tab:题面 | 测试用例 | 提交记录——测试用例管理(列表/新增/行内编辑/删除,与编辑器浮动栏同源)与云端测试结果(X/Y 汇总+每用例对比+CE/RE 全文)全部在右窗展示;开刷与提交完成后自动联动当前挑战。
- 插件图标:蓝绿渐变 CQ 字母徽章(pluginIcon 亮/暗+工具窗图标同步)。
### Fixed
- 浮动工具栏三键 tooltip 显示 %原文 → 构造器显式本地化文本(中文随语言环境)。
- 工作区目录连字符改下划线(合法 Java 包段)+新建 Java 文件自动注入 package 声明(按相对最近 source root 计算);Excluded 标记逻辑移除,Java 语法错误检查恢复生效。
- 左右窗导航去重:删前端页内部导航,统一工具窗顶部 tab 格式。
### Removed
- 底部 CodeQuest Challenge 工具窗(挑战选择/多解法/本地运行/手填新建);开刷主链路不变。

## 1.4.0 - 2026-08-23
### Added
- 开刷自动带入题面示例测试用例:创建挑战时从力扣题面拉取 exampleTestcaseList(平台已按用例切好,多行输入完整保留)写入测试用例(source=平台样例,仅新建时,不覆盖既有)——编辑器「修改测试用例」对话框与「提交测试」开箱即用,不再是空表。
### Changed
- 工作区目录名 ASCII 化:新挑战目录为 '<题目slug>-<id>' 形态(如 palindrome-number-0525d717),中文标题字符一律过滤(slug 缺失时回退 ASCII 标题,仍空回退 challenge 前缀)——根因消除中文目录导致的 package 检查告警;此前创建的中文目录(如 回文数-0525d717)按旧命名规则探测兼容,存量挑战不丢失;Excluded 标记保留兜底。

## 1.3.1 - 2026-08-23
### Fixed
- 题库列表三条查询路径(普通/公司题单/题单)的**落库阶段异常**不再逃逸成无信息的服务器 500 文本——数据库层错误(如库文件损坏后的 no such table)现在如实映射为 JSON 错误响应,前端可显示具体错误而非裸 http-error。修复背景:库文件被外部清零后选题单触发列表 500、且无任何可读信息(数据无损,见 docs)。

## 1.3.0 - 2026-08-23
### Added
- Editor floating toolbar on challenge files: opening any file inside a challenge workspace shows three actions at the top-right of the editor — "修改测试用例" (test-case dialog with a two-column input/expected table, add/remove rows and bulk paste import, writing through to the same test-case store the challenge tool window reads), "提交测试" (cloud test run) and "提交" (the existing submission flow reused unchanged). The toolbar never appears on non-challenge files, "提交测试" is hidden on platforms without cloud-test capability (CodeForces / AtCoder / Luogu / HackerRank), both submission actions are disabled while the platform is not logged in, and unsaved editor changes are saved before any action runs.
- Cloud test runs: new `TestEvaluationCapable` SPI (`watchTestRun`) with a LeetCode implementation POSTing `interpret_solution` (snake_case body + CSRF) and polling `/submissions/detail/<interpret_id>/check/` with the shared exponential-backoff semantics. The result dialog shows the X/Y pass summary, per-case input/expected/actual rows, runtime/memory, and the full compile/runtime error text (full_* preferred, short fallback) replacing the table when present.
- Problems-page 题单 dropdown (LeetCode / LeetCode CN): an optgroup of the 18 built-in leetcode.cn study plans (热题 100, 面试经典 150 题, 高频 SQL 50 题, 动态规划系列, …) plus a "我的题单" group fetched live from the logged-in account (GraphQL `favoritesLists`, REST `list_favorites` fallback, silent empty-group degrade when both fail). Selecting a favorite switches the list via the new `favorite=<slug>` parameter on `/api/problems` (favorite takes precedence over company/period) and is mutually exclusive with the company/period filters in both directions; `GET /api/favorites?platform=` serves the two groups (404 semantics on non-capable platforms, same as `/api/companies`).

## 1.2.5
### Fixed
- Challenge workspace folders no longer trigger the IDE "missing package statement / invalid package name" inspection: the workspace base directory (`<project>/codequest/`, or `codequest/` under the configured database folder) is now automatically marked as Excluded when it sits inside a module content root — on challenge-panel open and after one-click creation (idempotent). Editing challenge files, local javac/java runs and submit-region extraction are unaffected; directories outside the project are left untouched.

## 1.2.4
### Fixed
- Challenge entry files now carry the platform's per-language starter code: LeetCode / LeetCode CN detail queries fetch `codeSnippets`, and the one-click "start coding" flow injects the selected language's snippet into the template's `${challenge.code}` — e.g. the real `public int[] twoSum(int[] nums, int target)` signature instead of the bare `class Solution {}` stub. Fetch failures (premium problems, network) fall back to the language default stub; stdin-style (ACM) platforms are unaffected. The manual New Challenge dialog still uses the default stub (start from the problem view for real starter code).

## 1.2.3
### Fixed
- Settings template editor rewired to eliminate cross-key text leaks: the style/language dropdowns no longer use the UI DSL two-way `bindItem` binding, whose programmatic selection events during init/reset could fire the switch handler while the internal style/language state was still stale — flushing the editor text into the wrong `(style, language)` key. This was the root cause of "configuring Java makes every language show the same Java template" and "start-coding creates a .kt file": the saved text landed on the wrong keys while `leetcode:java` stayed empty, letting the leftover polluted `leetcode:kotlin` entry win the default-language pick.
- Dropdowns are now initialized to match the internal selection before listeners attach, programmatic selections are suppressed during reset, and only genuine user selections trigger the flush/refill sequence.

## 1.2.2
### Fixed
- Settings template browsing no longer implicitly enables a language: switching the style/language dropdown away from an untouched built-in default (never configured, text unchanged) keeps it unconfigured, instead of silently persisting the default text as "configured" and enabling that language. Explicitly clicking OK/Apply on a tab still enables it as before. This also fixes the confusing flow where configuring Java (from the default Kotlin tab) accidentally enabled Kotlin too.
- The settings page now opens on a configured language for the initial style (Java preferred, per template availability) instead of always landing on the first language (Kotlin), so returning to settings after saving a Java template shows the Java tab.

## 1.2.1
### Fixed
- Problems list status now reflects the actual solve state from the platform: LeetCode / LeetCode CN pass the logged-in user's per-question GraphQL `status` through and merge it with local submission history (AC > TRIED > UNSOLVED), so problems solved on the website show as solved even when never submitted from the plugin. Field-tested caveat: the CN list API does not distinguish "not attempted" from "attempted but not AC" (both arrive as non-"ac" values), so only "ac" maps to solved — the "tried" state remains driven by local submissions.
- Unsolved problems now render a visible grey hollow-circle badge with a tooltip, replacing the previous blank marker, so all four states (solved ✓ / tried ● / unsolved ○ / premium 🔒) are distinguishable at a glance on the problems and local-search lists.

## 1.2.0
### Added
- Panel consolidation: tool windows 4→3 — the submissions window is merged into the challenge window as a "Submissions" tab (JCEF, filtered by the selected challenge and refreshed as the selection changes), and the main sidebar keeps Problems / Contest / Statistics only. Cross-problem search moves into a "Local search" subview of the Problems page (the `/search/` route and landing entry remain).
- Platform login gating: platform dropdowns on the Problems and Contest pages only list logged-in platforms; when none are logged in the page falls back to LeetCode CN with a hint bar. The account area keeps all six platforms with usernames and explicit login/logout buttons, and refreshes the dropdown immediately after login/logout.
- Silent session restore on IDE restart: new `POST /api/auth/refresh` endpoint concurrently re-verifies stored credentials for all platforms without any prompts, and `GET /api/auth/status` now passes through the current session userId. Page load renders from status first, then refreshes incrementally after restore.
- Code templates by style × language: ACM (stdin/stdout) and LeetCode (Solution class) styles mapped per platform, stored under `style:language` keys with one-time dual-write migration from the old language-only keys (existing configs appear under both styles). Only explicitly saved templates are enabled at runtime — unconfigured languages no longer appear in the new-challenge language dropdown (Java is the default when configured), and one-click "start coding" prefers Java, then the first usable language, or guides to settings when nothing is configured.
- Settings page template editor upgraded to style + language dropdowns with a help dialog (template variables, submit-region markers, file naming rules, style-to-platform mapping) and "restore default text" as an editing start point that does not change the enabled set.
- UI localization to Chinese: all Kotlin-side hardcoded UI strings moved into the plugin bundle (Chinese IDEs get Chinese, English IDEs get English) covering the three sidebar tabs, challenge workspace, dialogs and error messages; the six webview pages are fully localized.
### Fixed
- Challenge code files now follow the configured database folder: with a folder set, new workspaces are generated under `<folder>/codequest/` next to `codequest.db` (previously they always went under the project root regardless of the setting). Workspaces created before the change keep resolving at their existing location. Default (empty) landing spot is now the tidier `<project>/codequest/` subdirectory.
- New Solution files re-render with the current settings template override (generation-time read, matching the legacy LanguageSettings behavior) instead of the template stored when the workspace was created.
- A database-folder switch failure no longer fails the whole settings apply: templates/CSS stay saved, the failure is logged, and the new path takes effect after restart.
- Problems/search list rows are single-line (status badge / id / title / tags / difficulty) so titles align across rows, and solved problems show a filled green ✓ badge (tried = hollow ring) for at-a-glance status.

## 1.1.0
### Added
- Submission result details panel (`/api/submission/:id/detail`): LeetCode / LeetCode CN include the error-testcase triple (input / expected / actual output), HackerRank shows testcase counts; CodeForces / AtCoder / Luogu link to the site as before (no public API). Submissions rows expand into Details / Code tabs — code review renders the stored snapshot with syntax highlighting and copy, no network needed.
- Problem status column on the problems and search pages: 🔒 paid / ✓ AC / ● tried icons aggregated from local submission history (AC is sticky; multiple challenges take the highest), with a local status filter. `paidOnly` now flows through from platform data so the lock icon is real.
- Account menu in the problems page header: per-platform login state badge with login / logout (`/api/auth/status`, `auth:logout` bridge).
- Settings: per-language code template editors (default = built-in, with reset) wired into every challenge-creation path, plus custom CSS injected into the problem view.
- Solution ordering dropdown on LeetCode / LeetCode CN (hot / newest / oldest / most upvoted, matching the legacy orderBy semantics).
- Webview zoom toolbar on the problem view (− / 100% / +), 1.2× per step, applied to the whole page.
- LeetCode company question lists: company and interview-period dropdowns on the problems page (`questionCompanyTagsV2` + `favoriteQuestionList`).
- Login entry from browse flows (e.g. HackerRank statement without credentials now offers login instead of a bare 401) and one-click "Start coding" from the problem view.
### Fixed
- LeetCode CN problem list showed English titles; `titleCn` now takes precedence when present (as the legacy implementation did).

## 1.0.0
### Changed
- Full rewrite from Scala/sbt to Kotlin 2.x + Gradle 8 (intellij-platform-gradle-plugin 2.x), package renamed to `com.codequest`; the legacy Scala/sbt codebase and the four old webview apps are removed, CI is converged to a single Gradle track.
- IDE baseline raised to IntelliJ 2026.2+ (build 262) only, with JCEF declared as an explicit plugin dependency (`com.intellij.modules.jcef`).
- UI rebuilt as a Swing shell plus one unified JCEF frontend (a single Vite/Vue3/TS app with six per-page entries); page data is served by an in-plugin ResourceHttpServer (REST endpoints such as `/api/problems`, `/api/stats`, `/api/search`) while JCEF messaging is reserved for commands/events, removing large-payload serialization stalls.
### Added
- Capability-based OJ SPI (`AuthCapable` / `ProblemQueryCapable` / `SubmissionCapable` / `ContestCapable` / `SolutionViewCapable`) with Extension-Point registration; adding an OJ = one `platform/<oj>/` package + plugin.xml declarations, zero core/ui changes (validated on all six platforms, see `docs/spi-validation.md`).
- Six platforms on the new SPI: LeetCode, LeetCode CN, CodeForces, AtCoder, Luogu, HackerRank — cookie-paste login persisted via PasswordSafe, and a shared submission polling state machine (exponential backoff) replacing per-platform polling copies.
- Cloudflare Turnstile anti-bot challenges handled through a shared JCEF dialog (CodeForces/AtCoder submit channels).
- Challenge workspace: multiple solutions per problem with per-solution language/template and begin/end submit regions; test-case management.
- Submission history stored with code snapshots (review does not depend on workspace files).
- CodeForces contest list/detail with subscribable 24h reminders (join via external link; rankings not promised — no public API).
- Statistics view: summary cards (total/accepted/AC rate/streak), GitHub-style heatmap and 30-day trend (`/api/stats` with platform filter).
- Cross-problem full-text search over title/statement/solution notes (SQLite FTS5 with LIKE fallback for short CJK keywords) plus tag AND filtering.
- In-app solution article reader (back/forward/refresh toolbar) for Luogu and LeetCode/CN.

## 0.1.0
### Changed
- Fork from LeetCode Epiphany 1.12.1 (Apache-2.0). Rebrand plugin id/name to `com.chenxiaochen.codequest` / CodeQuest.
### Fixed
- Declare `com.intellij.modules.jcef` dependency so the plugin loads on IntelliJ 2026.2+ (JCEF was split into a standalone plugin since build 262, causing `NoClassDefFoundError: org/cef/handler/CefLoadHandlerAdapter` without this declaration).
### Added
- IntelliJ 2026.2 (build 262) build target; `untilBuild` is now bounded per version (252.\*, 262.\*) instead of open-ended `null`.
- `JBCefApp.isSupported()` guard in `BaseJCefWebView` for a friendlier error when JCEF is unavailable.
- Local packaging script `scripts/package.sh` and manual release checklist `docs/release-checklist.md`.

## 1.12.1
### Fixed
- Using local katex for luogu and atcoder description rendering to avoid network issues.
- Change http referer to no-referer in description and leetcode solution webview to avoid image loading failure.
 
## 1.12.0
### Added
- Add run configuration creation support for ACM-liked challenges with test case as input.
 
## 1.11.0
### Added
- Add custom style support for challenge description rendering.
- Add go-back button in LuoGu solution webview.
 
## 1.10.2
### Fixed
- Resolve Cloudflare Turnstile verification issue on CodeForces submissions.
 
## 1.10.1
### Fixed
- Fix rendering issues in some LeetCode problem descriptions that use Markdown format.

## 1.10.0
### Added
- File names can be paths and support Velocity templates.
 
## 1.9.3
### Fixed
- Fix illegal characters in file name which come from some challenge titles   
 

## 1.9.1
### Fixed
- LeetCode: Use v2 api to query questions 

## 1.9.0
### Added
- HackerRank submission result support
- HackerRank user profile view support
### Fixed
- HackerRank login issue
 
## 1.8.0
### Added
- Add leetcode sql question support
 
## 1.7.4
### Fixed
- Fix luogu template document issue
 
## 1.7.3
### Fixed
- Fix luogu 2-factor-login issue
 
## 1.7.2
### Fixed
- Fix leetcode Golang question issue
 
## 1.7.0
### Added
- Add submission result view in embedded browser for AtCoder
### Fixed
- Automatically create missing directories when creating new code file
 
## 1.6.2
### Fixed
- Fix AtCode submission with cloudflare turnstile issue. [#62](https://github.com/WenjunHuang/CodeEpiphany/issues/62) 
 
## 1.6.1
### Fixed
- Fix proxy username and password not working issue
### Changed
- Optimize compatibility with JetBrains IDEs 2025.2
 
## 1.6.0
### Added
- Add submission result view in embedded browser for CodeForces, LuoGu
### Fixed
- Optimize api infrastructure

## 1.5.0
### Added
- Add user profile view for LeetCode, LeetCodeCN, LuoGu, CodeForces, AtCoder and HackerRank
- Add submission result view in embedded browser for LeetCode, LeetCodeCN
 
## 1.4.0
### Added
- Add plugin update notification
- Add plugin donation link
 
## 1.3.4

### Fixed
- Support LuoGu's netease yidun captcha code when submitting answer. fix [#60](https://github.com/WenjunHuang/CodeEpiphany/issues/60)
 
## 1.3.3

### Fixed
- LuoGu question query api changed, fix query issue
  
## 1.3.2

### Added

- Add LuoGu question solution

## 1.3.1

### Fixed

- Fix leetcode/leetcodecn test cases data not correctly parsed
  issue [#48](https://github.com/WenjunHuang/CodeEpiphany/issues/48)
- [#49](https://github.com/WenjunHuang/CodeEpiphany/issues/49)

## 1.3.0

### Added

- Add TestCase support for LeetCode, LeetCodeCN, LuoGu, CodeForces, AtCoder and HackerRank
- Add TestCase variable in code file template for LeetCode, LeetCodeCN, LuoGu, CodeForces, AtCoder and HackerRank

### Fixed

- [#16](https://github.com/WenjunHuang/CodeEpiphany/issues/36)
- [#29](https://github.com/WenjunHuang/CodeEpiphany/issues/29)
- [#37](https://github.com/WenjunHuang/CodeEpiphany/issues/37)
- [#38](https://github.com/WenjunHuang/CodeEpiphany/issues/38)
- [#46](https://github.com/WenjunHuang/CodeEpiphany/issues/46)

## 1.2.3

- Fix webview scrollbar style not compatible with IDE theme issue
- Fix user avatar icon size is too big for leetcode solution view in macos
- Fix codeforces code submission result issue

## 1.2.2

### Modified

- Fix chinese translation issues
- Modify readme and description documentation

## 1.2.0

### Added

- LeetCode and LeetCodeCN official solutions view support

### Modified

- Rename the old 'solutions' feature to 'my notes'

## 1.1.0

### Added

- Add [Browser Competitive-Companion](https://github.com/jmerle/competitive-companion) support
  to [CodeForces](https://codeforces.com/)

## 1.0.4

## Fixed

- Fix CodeForces login issue
- Fix code file template return empty string issue

## 1.0.2

### Changed

- Change leetcode icons

## 1.0.1

### Fixed

- Fix luogu user cookie expiration causing login failure
  issue.[#17](https://github.com/WenjunHuang/CodeEpiphany/issues/17)

### Changed

- Change description

## 1.0.0

### Added

- Automatically save search queries
- Save latest opened codedojo toolwindow and automatically open it when the IDE is restarted

## 0.9.8

### Changed

- Fix luogu login change

## 0.9.3

### Changed

- Fix leetcode company combobox dropdown slow

## 0.9.2

### Changed

- Fix a few bugs

## 0.9.0

### Added

- Add [LuoGu/洛谷](https://www.luogu.com.cn/) support

## 0.8.1

### Added

- Implement company question search for LeetCodeCN and LeetCode

## 0.8.0

### Changed

- Support Idea Platform from version 2023.3

## 0.7.1

### Added

- Implement [#5](https://github.com/WenjunHuang/CodeEpiphany/issues/5)

## 0.7.0

### Added

- Add [AtCoder](https://atcoder.jp) support

### Changed

- Fix [#10](https://github.com/WenjunHuang/CodeEpiphany/issues/10)
- Refine codeforces's language version
- Add 'view in browser' link for LeetCode, LeetCodeCN and HackerRank submissions
- Repair some UI problems

## 0.6.6

### Changed

- Fix the issue of missing text for action 'Surround Region'
- Fix leetcode result table display issue

## 0.6.5

### Added

- Implement [#6](https://github.com/WenjunHuang/CodeEpiphany/issues/6)
- Implement [#4](https://github.com/WenjunHuang/CodeEpiphany/issues/4)
- Add start and end markers for code regions to templates.

### Changed

- Offer simple templates for Code Forces challenges.
- Optimize some UI elements, including the difficulty color scheme and the login waiting indicator.

### Deprecated

### Removed

## 0.6.4

### Added

### Changed

- Fix [#3](https://github.com/WenjunHuang/CodeEpiphany/issues/3)
- Fix a database unique index constraint that prevented adding python and python3 simultaneously.
- Refactor code structure

### Deprecated

### Removed

## 0.6.3

### Added

### Changed

- optimize challenge submission result message
- Resolve the issue of missing language information when opening a challenge.
- fix [#2](https://github.com/WenjunHuang/CodeEpiphany/issues/2)

### Deprecated

### Removed

