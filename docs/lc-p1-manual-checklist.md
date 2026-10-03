# 力扣 P1 三功能 集成验收清单(loop-20261003-lc-p1 / WP71)

- 日期:2026-10-03
- 规格:`docs/superpowers/specs/2026-10-03-lc-p1-features-design.md`(F1 每日一题 / F2 LC 竞赛回顾 / F3 排序五档+隐藏已解决)
- 结论速览:三功能后端+前端逻辑全量自动化(862+239 测试全绿);runIde 沙箱 7 组端点真实数据探测全过(每日一题与官方站交叉一致、竞赛列表/详情真实数据、sortBy 双档顺序实变);题库页每日一题卡片与竞赛页 LC 行渲染经 Playwright 无头实测(竞赛登录门控用路由拦截模拟登录态,数据真实);真机登录态交互与 IDE 内肉眼走查留人工(§C)

## 0. 全量门禁结果(2026-10-03 实测)

| 门禁 | 命令 | 结果 |
|---|---|---|
| Kotlin | `./gradlew clean test check buildPlugin` | BUILD SUCCESSFUL(全量 1m 4s;1.8.0 定版后 `test check` 复跑 47s 再绿);114 测试类 **862 tests / 0 failures / 0 errors / 0 skipped**;detekt + ktlint + 分层依赖检查全过;zip 产物 `build/distributions/codequest-1.8.0.zip`(**28,245,763 bytes ≈ 26.9 MB**) |
| 前端 | `cd frontend && npm run test && npm run build` | 25 文件 **239 tests 全过**(14.87s);vite build 成功(2.80s) |

## A. 沙箱端点探测(runIde,IU-2026.2 沙箱)

idea.log 定位插件内嵌服务器随机端口(三次运行:127.0.0.1:8035 / 10673 / 10816,末次为干净进程首请求计时实验)。探测后沙箱 IDE 进程已全部杀净(java 0 残留 + cef_server 0 残留,三个端口监听全部撤销;剩余 java 进程为 Gradle 构建守护,非沙箱)。

| # | 探测 | 真实响应 |
|---|---|---|
| 1 | `GET /api/daily-question?platform=leetcodecn` | HTTP 200 `{"date":"2026-10-03","frontendQuestionId":"32","title":"最长有效括号","titleSlug":"longest-valid-parentheses","difficulty":"Hard","tags":[Stack,String,Dynamic Programming,括号序列]}`。**真实拉取三重实证**:① 干净进程轮询日志至「server started」瞬间首请求 **1.65s**(真实网络往返,经 graphql() 助手自动带 Referer/Origin 协议配方),第二请求 0.0036s(当日 volatile 缓存命中,~450x);② curl 直发 `https://leetcode.cn/graphql/` 同配方(operationName=questionOfToday+Referer)返回与端点 payload 完全一致(date/题号 32/中文标题 translatedTitle 优先/titleSlug/difficulty/tags);③ Playwright 渲染卡片字段与上逐项一致(§B) |
| 2 | `GET /api/daily-question?platform=leetcode` | HTTP 404 `{"error":"unknown-platform","message":"no daily-question-capable platform registered for id 'leetcode'"}`——COM 无能力,404 属预期(前端静默不渲染卡片) |
| 3 | `GET /api/contests` | HTTP 200 `{"platforms":[{leetcodecn,力扣},{codeforces,CodeForces}]}`——CN 变体承载 ContestCapable 后能力探测即含双平台 |
| 4 | `GET /api/contests?platform=leetcodecn` | HTTP 200 真实竞赛列表 **10 条**(weekly-contest-521/520/519/518/517/516/515、biweekly-contest-192/191/190),phase 全 FINISHED(今日 10-03,最近一场 09-27 已结束,推导正确);首条 `weekly-contest-521` startTime `2026-09-27T02:30:00Z`(UTC 实例化,前端渲染为本地 2026年9月27日 10:30,+8 时区正确) |
| 5 | `GET /api/contest/weekly-contest-521?platform=leetcodecn` | HTTP 200(0.27s 真实拉取)详情含 4 道题目行:Q1 移除不同值重排数组 / Q2 至多一次替换后的最大相邻相等元素对数 / Q3 数对和受限的最长子数组 / Q4 考虑空闲时间的会议最大收益(中文标题直出),joinable=false |
| 6 | `GET /api/contests?platform=leetcode` | HTTP 404 `no ContestCapable platform registered for id 'leetcode'`——COM 竞赛无能力属预期 |
| 7 | `GET /api/problems?platform=leetcodecn&limit=5` × `sortBy=id`/`sortBy=difficulty` | 两档均 HTTP 200,**首 5 行顺序实变即证透传生效**:`sortBy=id` → two-sum(#1)/add-two-numbers(#2)/longest-substring(#3)/median-of-two-sorted-arrays(#4)/longest-palindromic-substring(#5),题号严格升序;`sortBy=difficulty` → length-of-last-word(#58)/plus-one(#66)/add-binary(#67)/sqrtx(#69)/climbing-stairs(#70),全 EASY 优先且题号乱序;无参默认与 id 序一致(CN 站默认即题号序) |

## B. 沙箱 UI 探测(Playwright 无头,页面=沙箱静态 webview,数据=沙箱真实端点)

| 项 | 结果 |
|---|---|
| 题库页每日一题卡片 | **自动化通过**:Playwright 打开 `http://127.0.0.1:8035/problems/`,「今日题目」卡片渲染——按钮文本含 `今日题目 / 2026-10-03 / 32 / 最长有效括号 / Hard / Stack / String / Dynamic Programming / 括号序列` 全要素(日期+题号+标题+难度徽标+标签 chips),与官方站数据一致;页面同时含「隐藏已解决」checkbox 文案与题库首行「两数之和」;截图 `wp71-problems.png` |
| 竞赛页 LC 平台 tab 与详情 | **自动化通过(注记:登录门控用 Playwright 路由拦截 /api/auth/status 模拟 leetcodecn 已登录,竞赛数据全部真实)**:力扣 tab 出现,10 行真实竞赛(标题+本地化时间 2026年9月27日 10:30+时长 1 小时 30 分钟+phase 已结束+打开/题目/提醒按钮);行内「题目」展开后 Q1-Q4 全渲染(Q1 移除不同值重排数组/Q4 考虑空闲时间的会议最大收益);无登录态时不拦截则显示引导文案「当前无已登录平台…」——登录门控行为本身也实测正确;截图 `wp71-contest-login.png` / `wp71-contest-detail.png` |

## C. 留人工走查清单(需登录态或 IDE 内肉眼)

### C-1 每日一题卡片直达(真机)

IDE 内题库页切力拓 CN:卡片点击应经 nav:openProblem 打开题面(titleSlug 同题库行链路);跨日后首请求重拉(缓存失效);CN 站协议变动/接口失败时卡片消失但题库列表正常加载。

### C-2 竞赛提醒订阅与点题直达(真机登录态)

竞赛页 LC tab 登录后可见(真实登录门控);行内「题目」详情点题行应开题面(remoteId=titleSlug);「提前 24 小时提醒」订阅/退订走通用 REST 真机验证。

### C-3 排序五档与隐藏已解决肉眼(IDE 内 JCEF)

排序循环按钮五档切换:题号/难度档触发带 sortBy 的 reload(网络面板可见 `sortBy=` 参数,分页归零);评分↑↓仅本地排序已加载行;题单/公司模式跳过服务端两档;「隐藏已解决」勾选后已 AC 行即时隐藏、状态/评分区间叠加、重开 IDE 保持(localStorage)。

### C-4 竞赛详情真实排期边界(可选)

CODING/BEFORE phase 的实时推导需在比赛进行中或临近时肉眼验证(沙箱期间全为 FINISHED;窗口推导逻辑已由 clock 注入单测覆盖 BEFORE/CODING/FINISHED 三态)。

## D. 自动化验收映射(三功能)

### F1 每日一题

| 项 | 结论 | 证据 |
|---|---|---|
| questionOfToday 三态解析(translatedTitle 优先/字段缺失 null/errors 信封) | 自动化 ✅ | `LeetCodeApiTest.kt` 3 例(`parses with translated title preferred` / `falls back to english title and degrades on missing fields` / `errors envelope degrades to null`) |
| DailyQuestionCapable SPI+CN 承载/COM 不承载 | 自动化 ✅ + 沙箱实测(§A 1-2) | `DailyQuestionApiHttpTest.kt` 7 例之 `contests capability lists cn variant only` 等;`requires capable platform` 404 |
| REST 端点字段/当日缓存/降级全 null | 自动化 ✅ + 沙箱实测(§A 1) | `DailyQuestionApiHttpTest.kt`(`returns fields for cn platform` / `caches payload within same day` / `degrades to null fields on fetch failure`) |
| 前端卡片三态/字段/点击 payload/静默隐藏 | 自动化 ✅ + Playwright 实测(§B) | `frontend/tests/pages/daily-question.test.ts` 4 例;contest LC 部分 4 例见 F2 |

### F2 LC 竞赛回顾

| 项 | 结论 | 证据 |
|---|---|---|
| contestHistory 秒级解析+phase 三态推导(clock 注入) | 自动化 ✅ + 沙箱实测(§A 4) | `LeetCodeContestServiceTest.kt`(`derive BEFORE before start` / `derive CODING inside window including exact start` / `derive FINISHED past window` / `map summary fields duration and url`) |
| contest/api/info 题目行/不存在异常/缺失降级 | 自动化 ✅ + 沙箱实测(§A 5) | `LeetCodeContestServiceTest.kt`(`contestDetail maps question refs with Q index and joinable false` / `throws OJException for nonexistent slug` / `degrades missing questions to empty list`) |
| REST 列表/详情/COM 拒绝 | 自动化 ✅ + 沙箱实测(§A 3-6) | `DailyQuestionApiHttpTest.kt`(`contests capability lists cn variant only` / `contests list serves cn platform and rejects com variant` / `contest detail serves question refs for cn platform`) |
| 前端 LC tab/行模板 phase 中文/详情点题/提醒订阅 | 自动化 ✅ + Playwright 实测(§B) | `frontend/tests/pages/daily-question.test.ts` contest 4 例 + `contest-page.test.ts` 7 例(行模板共用:CF/LC 同受益) |

### F3 排序五档+隐藏已解决

| 项 | 结论 | 证据 |
|---|---|---|
| sortBy 协议枚举映射(CN V1 orderBy/COM V2 sortField,CUSTOM 回退) | 自动化 ✅ + 沙箱实测(§A 7) | `LeetCodeApiTest.kt` 2 例(`cn question list maps sortBy to filters orderBy and omits when unsupported` / `com question list maps sortBy to sortField snapshot with CUSTOM fallback`) |
| /api/problems sortBy 查询参数(非法缺省不传) | 自动化 ✅ + 沙箱实测(§A 7) | 请求快照测试 5 例(WP68)+ §A 顺序实变 |
| 前端五档循环/服务端档 reload 带参/题单跳档/平台切换重置 | 自动化 ✅ | `frontend/tests/pages/problems-rating.test.ts`(`cycles sort 默认→题号→难度→评分↑→评分↓…` / `favorite mode: server sort levels skipped…` / `resets sort to 默认 on platform switch`) |
| 隐藏已解决(AC 隐藏三态保留/持久化/多条件叠加) | 自动化 ✅ | `problems-rating.test.ts`(`hides solved rows locally…persists the flag` / `restores hide-solved from storage and stacks with status filter + rating range`) |
| 排序按钮/checkbox 肉眼 | 留人工(C-3) | — |

## E. spec §6 五条风险结论

| 风险 | 结论 | 证据 |
|---|---|---|
| questionOfToday 协议变动/字段缺失 | 已按对策覆盖:null 降级卡片隐藏,不致败题库页 | `LeetCodeApiTest.kt` questionOfToday 3 例(translatedTitle 优先/字段缺失 null/errors 信封 null);`DailyQuestionApiHttpTest.'daily question degrades to null fields on fetch failure'`;沙箱实测卡片真实渲染且与官方站交叉一致(§A 1/§B),vitest 静默隐藏 404/全 null 两例 |
| contest/api/info 字段名与预期不符 | 已按对策覆盖:WP 内真实响应样例锁定;题目拉取失败空列表不致败详情 | `LeetCodeApiTest.'contestInfo parses questions…'` / `'degrades missing questions to empty list'`;`LeetCodeContestServiceTest.'contestDetail degrades missing questions to empty list'`;沙箱真实详情 Q1-Q4 中文标题直出(§A 5) |
| sortBy 枚举值与协议不符 | 已按对策覆盖:实测锁定+未知值回退 CUSTOM;请求参数快照 | `LeetCodeApiTest.kt` 双站 2 例(CN orderBy 映射+不支持时省略/COM sortField 快照 CUSTOM 回退);WP68 请求快照 5 例;沙箱实测两档顺序实变(§A 7) |
| 竞赛 tab 现有行模板对 LC 数据展示不全 | 已按对策覆盖:共用行模板补齐字段(CF/LC 同受益),vitest 覆盖 | `frontend/tests/pages/contest-page.test.ts` 7 例(行渲染/时间/phase 徽标/提醒开关/错误面)+ daily-question.test.ts LC 集成 4 例(phase 中文映射 LC 三值子集/时长格式化);Playwright 真实数据行渲染+详情展开实测(§B) |
| phase 推导时区错位 | 已按对策覆盖:startTime epoch 秒实测锁定,统一 Instant 比较 | `LeetCodeApiTest.'contestHistory parses with epoch-second start time and duration'`;`LeetCodeContestServiceTest` BEFORE/CODING/FINISHED 三态 clock 注入 3 例(含 exact start 边界);沙箱实测 02:30:00Z→本地 10:30(+8)渲染正确(§A 4/§B) |
