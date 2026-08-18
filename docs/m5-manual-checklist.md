# M5 集成验收手册(沙箱实测 + 手工 checklist)

- 日期:2026-08-19(WP32)
- 环境:`./gradlew runIde` 沙箱(IntelliJ IDEA 2026.2,IU-262.8665.258),资源服务 `http://127.0.0.1:5265`(本轮实测),沙箱日志 `.intellijPlatform/sandbox/codequest/IU-2026.2/log/idea.log`(本轮自行 3146 起)
- 范围:M5 三件套——统计可视化(WP29)/跨题全文检索与标签筛选(WP30)/题解文章正文渲染(WP31);M1-M4 链路见各自手册,本手册不重复
- 原则:自动化能验证的已验证并附证据;需要真实账号/人工 GUI 交互的项如实标注「留手工」并给出步骤
- 测试数据纪律:实测注入的 submission/challenge/solution 测试行已全部清理,库恢复验收前基线(problem 16 / submission 0 / challenge 0 / solution 0,contest_reminder 1 条为 M4 遗留)

## A. 自动化已执行项(证据见 WP32 报告)

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| A1 | 全量门禁 `./gradlew clean check test buildPlugin --rerun-tasks` | 通过 | `BUILD SUCCESSFUL in 1m 44s`,24 tasks executed;test-results XML 汇总 `suites=79 tests=517 failures=0 errors=0 skipped=1` |
| A2 | frontend `npm run test` + `npm run build` | 通过 | Vitest `14 passed (14)` / `95 passed (95)`;vite `✓ built in 1.83s` |
| A3 | 产物 zip 完整性(M5 新增页入包) | 通过 | `codequest-0.1.0-SNAPSHOT.zip`(26.1MB):主 jar 929 entries 含 6 平台 Provider + plugin.xml;webview 93 资源中 **stats 2 + search 2 新页** 与 index/problems/problem-view/contest/submissions 五页齐备,assets 79 |
| A4 | 沙箱插件加载 0 ERROR | 通过 | 本轮会话(2026-08-19 00:01 起)idea.log 全文 0 ERROR;`CodeQuest resource server started on http://127.0.0.1:5265` |
| A5 | `/api/stats` 空库零值 | 通过 | `days=365 summary={0,0,0.0,0,0}`;heatmap 365 天(2025-08-20→2026-08-19 含今天)全零占位、trend 30 天(2026-07-21→2026-08-19)全零占位 |
| A6 | `/api/stats` 注入测试提交后核数(与 submission 表对照) | 通过 | 注入 6 行(3 个平台日分布 × leetcodecn/codeforces;4 Accepted + 1 WrongAnswer + 1 PartialScore):全量 `total=6 accepted=4 rate=0.667 streak=2/2`,heatmap 当日计数 3/2/1 与表逐日 count 一致;`platform=leetcodecn` → `4/2/0.5`(PartialScore 不计 AC,口径验证);`platform=codeforces` → `2/2/1.0 streak=1`(今天有/昨天无) |
| A7 | `/api/stats` 参数契约 | 通过 | `days=731` → 200(heat 731 / trend 仍 30,min 口径);`days=732`、`days=abc` → 400 `bad-request`;未知平台 `platform=leetcode`(本地无该平台提交)→ 零值不 404 |
| A8 | `/api/search` 浏览模式(无 keyword/tags) | 通过 | `total=16 items=16 hasMore=false`,matchedSources 全空数组(浏览模式无来源概念) |
| A9 | `/api/search` 中文关键词命中与来源标记 | 通过 | 「过河卒」→ P1002 `['title']`;「超级」→ P1000 `['title']`;「整数」(2 字,LIKE 降级)→ P1001 `['content']`;「两个整数」(4 字,FTS)→ P1001 `['content']`;「溢出」(仅题解笔记含)→ P1001 `['note']`(三源各自独立命中验证);「求和」→ `['note']`;「A+B」→ `['title','content']` 双源聚合 |
| A10 | `/api/search` 标签 AND 与组合 | 通过 | `tags=dp,matrices` → 2(2257F1/F2,均含双标签);`tags=dp` → 3;`tags=dp,Hash Table`(无交集)→ 0;`keyword=整数&tags=递归` → 0(交叉过滤生效) |
| A11 | `/api/tags` 与分页/参数契约 | 通过 | 全量 15 标签(dp=3 居首,题数降序);`?platform=leetcodecn` 收窄 7 个、`?platform=luogu` 空;`limit=5` → items 5/hasMore true,`offset=15` 尾页 1 条/hasMore false;`limit=0/101/x`、`offset=-1` → 400 |
| A12 | 题解端点链路(沙箱未登录态) | 通过 | 洛谷 `P1001/solutions?platform=luogu` 列表与正文 → 401 `auth-expired`(含平台侧「请先登录」信息,如实降级);力扣CN `two-sum/solutions` 匿名 → 200 真实列表(官方题解等中文条目),正文 `liang-shu-zhi-he-by-leetcode-solution` 匿名 → 200 MARKDOWN 4970 字符;leetcode.com 匿名 → 401(站点事实);非题解平台 `platform=atcoder` → 404 `unknown-platform`;缺 platform → 400 |
| A13 | 题解正文不落库 | 通过 | 拉取列表+正文后复查:problem_content 3 行 / solution 1 行(注入)/ problem 16 行均无增长(重复查看重复打上游为 YAGNI 预期,见 spi-validation §7) |
| A14 | 检索数据前置(题目缓存) | 说明 | 本轮未新增拉题(直接消费 M4 沙箱留存 16 题 5 平台缓存);检索端点消费本地库,题目入库路径(problems 端点缓存)已在 M4 手册 A10 验证 |

> 测试工具注记:HTTP 探测用 python urllib(UTF-8 参数编码);git-bash 下 curl 传中文参数会因本地编码产生假阴性,复测时勿直接用 curl 传中文。

## B. 留手工项(未自动执行,按下列步骤逐项过)

以下链路需要真实账号 cookie 与人工 GUI 交互(JCEF 内公式/代码高亮/主题色阶的视觉正确性),自动化未执行。

### B1. 统计页(GUI 渲染 + 亮暗主题)

1. **空库占位**:清空 submission 数据(或新库)→ 打开 CodeQuest 工具窗口 → Statistics 面板
   - 预期:「还没有提交记录」类空态占位(前端 Vitest 已覆盖文案);API 侧零值见 A5
2. **注入数据核对**(复现 A6 步骤):用 SQLite 工具向 `~/.codequest/codequest.db` 的 `submission` 表插入若干行,`verdict` 列为多态 JSON(`{"type":"Accepted"}` / `{"type":"WrongAnswer"}` / `{"type":"PartialScore","score":0.5}`),`submitted_at` 为 9 位小数秒 ISO 串(如 `2026-08-19T02:00:00.000000000Z`);刷新 stats 页
   - 预期:汇总卡片 数字 = SQL `SELECT COUNT(*)` / `COUNT(verdict='{"type":"Accepted"}')` 直查结果;AC 率 = 两者相除;热力图对应日期格子计数、趋势条形与逐日 `GROUP BY substr(submitted_at,1,10)` 一致
   - **与 submission 表对照的通用步骤**(验收口径):
     a. `SELECT COUNT(*) FROM submission` → totalSubmissions;
     b. `SELECT COUNT(*) FROM submission WHERE verdict = '{"type":"Accepted"}'` → acceptedSubmissions(注意仅 Accepted 计入,PartialScore 不算);
     c. `SELECT platform_id, substr(submitted_at,1,10), COUNT(*) FROM submission GROUP BY 1,2` → 热力图/趋势逐日计数(日期按本地时区);
     d. streak:今天有提交从今天倒数,今天没有从昨天倒数,连续日历天即 currentStreakDays
3. **亮暗主题热力图**:IDE 切亮色/暗色主题 → 热力图色阶 `--cq-heat-0..4` 五档应各自适配、图例与 tooltip 可读
4. **桥刷新**:提交一条(或注入后触发 `submission:updated`)→ stats 页不手动刷新自动更新

### B2. 检索页(GUI + 首次索引)

1. **空库**:全新库(无任何题目缓存)→ Search 面板
   - 预期:「No problems indexed yet」空态(API 侧空结果路径已由 A10 不相交组合验证为 total=0 不 500;真·空库未在沙箱单独构造,如实说明)
2. **建立索引**:Problems 页选平台拉若干题(经 problems 端点缓存落库+FTS 触发器);洛谷/力扣CN 建议都拉(中英文题面 + 标签)
3. **中文关键词**:搜索「过河卒」「整数」等 → 命中卡片带来源 chips(题面/题解笔记/标题);点结果卡片 → 跳转对应题面(nav:openProblem)
4. **2 字关键词**:搜 2 字中文词 → LIKE 降级路径仍能命中题面(A9 已验证 API;GUI 确认无异常)
5. **标签筛选**:多选 `dp` + `matrices` → 结果 AND 收窄;平台下拉切换 → 标签列表随平台收窄
6. **空结果**:搜不存在的词 → 空态文案(非报错)

### B3. 题解正文链路(需登录平台 + GUI 渲染)

1. **力扣CN(匿名可验一半)**:题面页打开 two-sum → 题解区块列出官方题解 → 点开
   - 预期:应用内阅读器渲染 MARKDOWN(方法一/方法二标题、代码高亮、图);API 侧匿名 200 见 A12
2. **洛谷(需登录)**:浏览器登录 luogu.com.cn → 复制 Cookie(`_uid=...; _client_id=...`)→ IDE 内触发题解列表时粘贴(经 `user/setting` 校验,见 M4 手册 B3)→ 题面页题解区块出列表
   - 预期:未登录时 IDE 内报 auth-expired 降级文案(A12 已验证 401);登录后列表正常
3. **正文渲染**:点任一题解 → 应用内阅读器
   - 预期:洛谷题解为 MARKDOWN,KaTeX 公式正确渲染、代码块 highlight.js 高亮;重复查看同一篇 → 每次打上游(A13 不落库预期,观察加载指示)
4. **导航工具栏**:连续打开 2-3 篇 → 工具栏「后退/前进/刷新」按浏览器语义工作;「站外打开」保留为兜底出口
5. **LC 会员文章**(需 LC 会员账号):打开会员专属题解 → 预期 502 `oj-error` 文案如实(平台侧限制),不白屏
6. **已知限制观察**:洛谷题解**第二页楼层不可达**(实现复用列表页 payload 按楼层 id 筛 content,翻页参数未打通;详见 spi-validation §7.3)——登录后翻到列表第二页尝试打开,确认现象与记录一致

## C. 已知限制(如实,详见 docs/spi-validation.md §7)

- 题解正文不落库:重复查看重复请求上游(YAGNI 取舍)
- 洛谷题解第二页楼层不可达(平台接口形态限制)
- leetcode.com 题解列表匿名 401(站点事实,需登录);LC 会员文章正文 502 如实报错
- stats 前端页无平台选择器(后端 `/api/stats?platform=` 已支持,前端展示全量口径)
- summary 汇总卡片为全量口径,不随 days 窗口变化(设计意图,见 StatisticsService KDoc)
- 中文 2 字关键词走 LIKE 降级(FTS5 trigram 最小 3 字符),功能等价、索引效率差异
- AtCoder/CodeForces/HackerRank 无题解能力(SolutionViewCapable 矩阵内),端点 404 如实
