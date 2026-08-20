# M7 集成验收手册(沙箱实测 + 手工 checklist)

- 日期:2026-08-21(WP43,M7 收官)
- 环境:门禁 JDK21 + Gradle 9.2.1;沙箱 `./gradlew runIde`(IntelliJ IDEA 2026.2),资源服务 `http://127.0.0.1:8115`(本轮实测),沙箱日志 `.intellijPlatform/sandbox/codequest/IU-2026.2/log/idea.log`(本轮自 5037 行起)
- 范围:M7 原版设计对齐九项(A 详情/B 状态列/C 账号/D 模板/F 代码回看/G CSS/H 排序/I 缩放/J 公司题;E 笔记经复核不做);M1-M6 链路见各自手册(`m3-manual-checklist.md`/`m4-manual-checklist.md`/`m5-manual-checklist.md`),本手册不重复
- 原则:自动化能验证的已验证并附证据;需要真实账号/人工 GUI 交互的项如实标注「留手工」并给出步骤
- 测试数据纪律:实测注入的 challenge/submission 测试行(`cq-*-wp43-*` 前缀,3 challenge + 5 submission)已全部清理,库恢复验收前基线(problem/challenge/solution/contest_reminder 与验收前一致,submission 归 0)

## A. 自动化已执行项(证据见 WP43 报告)

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| A1 | 全量门禁 `./gradlew clean check test buildPlugin --rerun-tasks` | 通过 | `BUILD SUCCESSFUL in 2m 18s`,24 tasks executed;test-results XML 汇总 `suites=91 tests=636 failures=0 errors=0 skipped=1` |
| A2 | frontend `npm run test` + `npm run build` | 通过 | Vitest `17 passed (17)` / `145 passed (145)`;vite `✓ built in 2.81s` |
| A3 | 产物 zip 完整性(M7 新能力入包) | 通过 | `codequest-1.0.0.zip`(26.3MB,27,624,980B,32 entries):主 jar 1025 entries 含 6 平台 Provider + plugin.xml + M7 新类(`SubmissionDetailCapable`/`CompanyQueryCapable`/`WebviewZoom`/`AuthApi`/`ProblemStatusStore`);lib 30 jars;webview 95 资源 7 页(index/problems/problem-view/contest/submissions/stats/search)齐备 |
| A4 | 沙箱插件加载 0 ERROR | 通过 | 本轮会话(2026-08-21 01:12 起)idea.log 657 行全文 0 SEVERE/0 ERROR;`CodeQuest resource server started on http://127.0.0.1:8115` |
| A5 | `GET /api/platforms` 返回 6 平台 | 通过 | leetcode/leetcodecn/codeforces/atcoder/luogu/hackerrank |
| A6 | **B 状态列注入核数**(与 challenge JOIN submission 聚合口径对照) | 通过 | 注入 challenge 3 + submission 5:two-sum(WA→AC 2 行)、add-two-numbers(WA+TLE 2 行)、luogu:P1001(AC 1 行)→ `/api/problems?platform=leetcodecn&limit=50`:two-sum `status=AC`(任一 Accepted 即 AC,AC 粘性)、add-two-numbers `status=TRIED`(全败有提交)、其余 48 题 `UNSOLVED`,分布 `{AC:1, TRIED:1, UNSOLVED:48}` 与注入逐题精确一致 |
| A7 | B 状态列同步到 search 页 | 通过 | `/api/search?keyword=两数之和` → 1 命中(two-sum),响应项携带 `status=AC` 与 `paidOnly`(字段与 problems 端点同构) |
| A8 | **A 详情**:洛谷(仅外链形态,匿名可用) | 通过 | 注入 luogu submission → `GET /api/submission/{id}/detail` 200:`viewUrl=https://www.luogu.com.cn/record/12345678`、`code`=本地快照、verdict/timeMs/memoryKb 本地与平台合并呈现 |
| A9 | A 详情错误契约(不静默失败) | 通过 | 不存在 id → 404 `not-found`;LC 系假 remote id(沙箱登录态)→ 上游 submission 详情 404 → 端点 502 `oj-error` 如实透传上游信息 |
| A10 | **C 账号状态快照** | 通过 | `GET /api/auth/status` → 6 平台快照;沙箱 leetcodecn `loggedIn=true`(历史登录残留)、其余 false;`userId` 全部 null(偏差 C1 实证:auth status 只有登录态缓存,不打平台) |
| A11 | **G 自定义 CSS** 端点默认值 | 通过 | `GET /api/custom.css` → 200 空文本(未设置时;端点按请求现读设置,改设置后新加载的页面即刻生效) |
| A12 | **H 题解排序**真实变化 | 通过 | two-sum `/solutions?orderBy=mostUpvote` vs `orderBy=oldest`:匿名各 200/20 条,首 3 条 slug 完全不同(`dong-hua-…`/`liang-shu-zhi-he-by-leetcode-solution`/`gpe3dbjds1` vs `python-3-ti-jie-…`/`by-lq_111`/`hazhaz`),排序真实生效 |
| A13 | **J 公司列表**端点 | 通过 | `/api/companies?platform=leetcodecn` → 184 家(bytedance/huawei/amazon/tencent/apple…,带题量);无能力平台 `platform=atcoder` → 404 `unknown-platform` 如实 |
| A14 | J 公司题单上游行为定位 | 说明 | 公司题单多公司/期间变体(company=bytedance/huawei/amazon × period 缺省/last-30-days/last-90-days/all-time)经端点一律 0 条;匿名直连 `leetcode.cn/graphql` 复现 `favoriteQuestionList` 同样 `total=0`(**假 slug 也是 0,不报错**)→ 实现链路正确(请求发出/解析无误,WP42 mock 单测锁定),上游该查询需有效登录才返回数据(见偏差 J4 与手工 B4) |
| A15 | J 公司模式 keyword 忽略(如实行为) | 通过 | `company=bytedance&keyword=array` → 仍返回公司题单路径结果(WP42 旧实现事实:公司查询恒发空 searchKeyword,UI 有提示条) |
| A16 | J leetcode.com 公司标签未登录降级 | 通过 | `/api/companies?platform=leetcode` → 401 `auth-expired`(COM 公司 GraphQL 需登录,如实降级不静默) |

> 测试工具注记:HTTP 探测用 python urllib(UTF-8 参数编码,M5 手册同款纪律);数据库核数/注入用 python sqlite3 直连 `~/.codequest/codequest.db`。

## B. 留手工项(未自动执行,按下列步骤逐项过)

以下链路需要真实账号 cookie 与人工 GUI(JCEF 视觉/IDE 设置面板/桥交互),自动化未执行。通用约定:M1-M6 手册的登录/提交链路继续有效。

### B1. A 提交详情(LC 错误用例三件套)+ F 代码回看(需真实提交)

1. **制造一次 WA**:登录力扣CN(见 M3 手册)→ 任一题 challenge 写一份必然 WA 的代码 → Submit → 等终态
2. **详情三件套**:submissions 页点该行 → Details tab
   - 预期:判定/耗时/内存/语言徽标 + **错误用例三件套**(输入/期望输出/实际输出对比渲染,数据来自 LC submissionDetails,WP36 mock 已锁定解析);洛谷/CF/AtCoder 行预期只有 viewUrl 外链 + 本地快照(A8 已验证端点形态)
3. **代码回看**:同行 Code tab
   - 预期:只读代码视图(hljs 按语言高亮,零网络)、Copy 按钮可用、Details/Code 可来回切换(每行独立保持)
4. **空快照兜底**:如遇无 codeSnapshot 的旧记录 → 空态文案非报错

### B2. C 账号菜单登录/登出(GUI 桥交互)

1. **徽标**:problems 页头部账号区 → 各平台徽标两态(未登录灰/已登录亮 + 用户名;userId 现为 null,见偏差 C1)
2. **登录**:点未登录徽标 → 弹 cookie 对话框(既有 auth:login 链路)→ 粘贴有效 cookie → 徽标转登录态
3. **登出**:点已登录徽标 → 登出 → `auth:logout` 桥 → AuthCapable.logout + 清缓存 → 徽标回未登录;problems 列表随之刷新
4. **快照口径**:auth/status 不打平台(设计行为);若 cookie 在 IDE 外失效,徽标仍显示登录态,直到下次真实请求失败(见偏差 J4 关联现象)

### B3. D 模板改后新 challenge 生效 + G CSS 注入视觉

1. **改模板**:Settings → CodeQuest → Templates → 选语言(如 Kotlin)→ 编辑模板正文(加一行注释标记)→ Apply
2. **生效验证**:problems 页任一题 → Start(或题面页一键开刷)→ 新 challenge 打开的编辑器
   - 预期:初始代码 = 改后的模板(两条创建链路均读设置);Restore default 按钮恢复内置
3. **CSS 注入**:Settings → CodeQuest → 自定义 CSS 文本域填入(如 `.cq-problem h2 { color: red; }`)→ Apply → **重新打开题面页**(下次加载生效,见偏差 G1)
   - 预期:题面 h2 变红;亮/暗主题下均生效;清空 CSS → 恢复默认
4. **缩放(I)**:题面页工具栏 −/+ / 100%
   - 预期:步进缩放整页生效(文字+代码+公式同缩)、连按不破版式、100% 一键复位;重开页面回 100%(不持久化,见偏差 I1)

### B4. J 公司题单(需有效登录,见 A14/J4)

1. **登录力扣CN**(有效 cookie;登录态快照可能过期,先做一次真实列表请求确认凭据有效)
2. **公司下拉**:problems 页选 力扣CN → Company 下拉出现(带题量,数据源 A13 的 184 家)
3. **切题单**:选 bytedance → 列表切公司题单;Period 下拉默认 30 天,切 all-time 题量应变多
4. **keyword 提示条**:公司模式下输入关键字 → 预期出现「公司模式下关键字被忽略」提示条(列表不因关键字收窄,A15 已验证端点行为)
5. **难度/状态列兼容**:公司题单内难度下拉/状态图标列仍工作(WP42 尽力兼容范围)
6. **Load more / Reset**:翻页加载更多;Reset 清公司选择回普通列表
7. **未登录入口**:leetcode.com 未登录选公司 → 401 → Login 入口(A16 已验证端点 401;GUI 确认入口可点)
8. **空列表观察**:若选公司后列表为空且无报错 → 大概率凭据过期(A14:上游匿名一律空),重新登录后再试——此为 J4 已知边界,不视为 bug

### B5. H 排序下拉 GUI

1. 力扣CN 题面页题解区块 → 排序下拉(最新/最热/投票等旧版选项)
   - 预期:切换后题解列表顺序真实变化(A12 已验证 API 层 mostUpvote/oldest 顺序不同;GUI 确认下拉只在 LC 系平台出现)
2. 洛谷题面页 → 无排序下拉(洛谷接口无排序参数,见偏差 H1)

## C. 九项与原版行为对照偏差记录(终版)

spec 九项逐项对照旧 Scala 实现(git `a8f69a0^` 为事实源)。已清偿项不列(paidOnly 恒 false 偏差 WP42 已清偿:改真值透传,🔒 图标生效)。loop 层提示中的「牛客」经核实为笔误——本工程六平台为 LC/力扣CN/CodeForces/AtCoder/洛谷/HackerRank,无牛客平台,偏差按工程事实记录。

### A 提交结果详情
- **A1. CF/AtCoder/洛谷提交详情仅 viewUrl 外链**:旧 Form 事实即无结构化详情接口(平台不提供);HackerRank 有 testcases 计数、LC/力扣CN 错误用例三件套完整。WP36 如实,无错误用例的回归靠 mock 单测。
- 达成度:LC 系对齐原版三件套;其余平台与原版等价(原版同样拿不到)。

### B 列表状态列与筛选
- 无偏差。AC 粘性/多 challenge 取最高/本地过滤与原版 Status 列语义一致(A6/A7 实测三态精确)。本地过滤仅作用于已加载项(分页列表页内实现,WP37 已注明)。

### C 账号菜单
- **C1. userId 恒 null**:SPI 无只读 session 接口(auth status 只有登录态缓存,不打平台),徽标显示平台名+登录态、无用户名。WP38 如实;本轮沙箱实证(loggedIn=true 而 userId=null)。

### D 语言模板设置
- 无已知偏差。每语言模板编辑器/恢复默认/两条创建链路接线与原版 LanguageSettingsPanel 等价(D 生效路径留手工 B3)。

### F 提交代码回看
- 无已知偏差。本地 codeSnapshot 零网络 + hljs 高亮 + 与详情同区切换,对齐原版 OpenSubmissionCodeAction。

### G 题面自定义 CSS
- **G1. 下次加载生效,非即时热更新**:端点 `/api/custom.css` 按请求现读设置(apply 后无需重启),但已加载的页面不自动重注入,需重开/刷新题面页。WP39 如实(A11 验证端点现读)。

### H 题解排序
- **H1. 洛谷 orderBy 如实忽略**:洛谷题解接口无排序参数,委托链透传但不生效,前端洛谷页不渲染排序下拉(能力探测式 UI)。WP41 如实;LC 系排序完整对齐旧 ArticleOrderByAction(A12 实测真实变化)。

### I 题面 webview 缩放
- **I1. zoom 不持久化**:重开页面回 100%(YAGNI 取舍,无跨会话记忆);缩放值计算/钳制([−7,+8] 级)由 WebviewZoom 单测锁定(WP41),GUI 步进留手工 B3-4。

### J LeetCode 公司题单
- **J1. 公司模式 keyword 被忽略**:旧实现恒发空 `searchKeyword`(公司查询面板无关键字入口),UI 有提示条如实告知;普通模式 keyword 正常。WP42 如实(A15 端点实测)。
- **J2. 公司 UI 单选**(旧版多选):端点协议层支持多公司(favoriteSlug=head + filtersV2.companySlugs=tail 拆分),UI 仅单选下拉。WP42 如实。
- **J3. position/岗位、tag 服务端筛选未接入**:GraphQL filtersV2 的 positionFilter/topicFilter 骨架已支持(协议就绪),UI 无入口。WP42 如实。
- **J4. 公司题单需有效登录(本轮验收新发现)**:LC `favoriteQuestionList` 匿名/过期凭据下一律返回 `total=0`(连不存在的 slug 也返回 0,不报错);沙箱内登录态快照 true 但题单空 → 凭据实际过期。端点如实呈现空列表(无「需登录」区分提示);与 auth/status 不打平台的设计叠加,用户需自行判断凭据有效性(A14 定位,手工 B4-8 观察)。
- **J5. leetcode.com 公司标签匿名 401**:COM 站 companyTags GraphQL 需登录,`/api/companies?platform=leetcode` 匿名 → 401 auth-expired 如实降级(A16 实测;WP42 报告一致,UI 有 Login 入口)。

### E 笔记独立视图——不做(复核维持)
- 复核结论:`solution.noteMd`(多解法笔记)+ FTS 检索已等价覆盖原版 Notes 侧栏能力(M5 验收 A9 已实证 note 来源可被 `/api/search` 命中,阅读器内可写可搜);再建独立视图为重复功能。维持 spec「不做」决定。

## D. 结论

- 门禁全绿(Kotlin 636 / 前端 145 / build 双绿 / zip 完整),九项全部落地且各有自动化测试,E 明确不做并复核
- 刷题闭环对齐原版:列表知状态(B)→开刷(D 模板)→提交→看错在哪(A 三件套)→改码回看(F)→再提交;账号可管理(C)、题面可定制(G/I)、题解可排序(H)、公司题单可刷(J)
- 新发现边界 1 条(J4)已记录;其余偏差与各 WP 报告一致,无新增
