# M4 集成验收手册(沙箱实测 + 手工 checklist)

- 日期:2026-08-18(WP28)
- 环境:`./gradlew runIde` 沙箱(IntelliJ IDEA 2026.2,IU-262.8665.258),沙箱日志 `.intellijPlatform/sandbox/codequest/IU-2026.2/log/idea.log`
- 范围:M4 四平台(CodeForces / AtCoder / 洛谷 / HackerRank)+ 竞赛能力;LeetCode/力扣CN 链路见 M3 手册(`m3-manual-checklist.md`),本手册不重复
- 原则:自动化能验证的已验证并附证据;需要真实账号/人工交互的项如实标注「留手工」并给出步骤

## A. 自动化已执行项(证据见 WP28 报告)

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| A1 | 全量门禁 `./gradlew clean check test buildPlugin --rerun-tasks` | 通过 | `BUILD SUCCESSFUL in 1m 49s`,24 tasks executed;test-results XML 汇总 `suites=74 tests=467 failures=0 errors=0 skipped=1` |
| A2 | frontend `npm run test` + `npm run build` | 通过 | Vitest `12 passed (12)` / `73 passed (73)`;vite `✓ built in 1.76s` |
| A3 | 产物 zip 完整性 | 通过 | `codequest-0.1.0-SNAPSHOT.zip`(26.0MB):主 jar 含 6 平台 Provider class(leetcode/leetcodecn/codeforces/atcoder/luogu/hackerrank)+ 5 authPromptProvider;lib 30 个依赖 jar(ktor 3.5.2 / exposed 1.4.0 / sqlite-jdbc / kotlinx 系);webview 94 个资源(index/problems/problem-view/contest/submissions 五页)+ META-INF/plugin.xml |
| A4 | 沙箱插件加载 0 ERROR | 通过 | 本轮(2026-08-18 18:13 起)idea.log 全文 0 ERROR;`CodeQuest resource server started on http://127.0.0.1:5093` |
| A5 | `GET /api/platforms` 返回 6 平台 | 通过 | `{"platforms":[{"id":"leetcode",...},{"id":"leetcodecn",...},{"id":"codeforces",...},{"id":"atcoder",...},{"id":"luogu","displayName":"洛谷 Luogu"},{"id":"hackerrank",...}]}` |
| A6 | `GET /api/contests`(ContestCapable 平台发现) | 通过 | `{"platforms":[{"id":"codeforces","displayName":"CodeForces"}]}`(六平台中唯一实现 ContestCapable,符合 spec 矩阵) |
| A7 | `GET /api/contests?platform=codeforces`(沙箱内真实网络) | 通过 | 返回真实竞赛(Round 1117 (Div. 2) finished / ICPC 2026 Online Challenge coding,含 startTime/durationMinutes/phase/url) |
| A8 | 竞赛订阅落库 + 时间模拟提醒调度 | 通过 | `POST /api/contests/reminders`(codeforces/2251,leadTime 50000min → remindAt=2026-07-10 已过期)→ 落库 notified=false;调度器线程 `cq-contest-reminders` jstack 存活(loop 中);一轮调度周期后复查 SQLite `contest_reminder` 该条 `notified=1`(findDue 命中 → 通知 → markNotified 去重,时间模拟验证通过) |
| A9 | 公开端点直连实测(沙箱外 curl,验证网络可达与数据形态) | 通过 | 力扣CN GraphQL 列表(two-sum 真实数据)/ CF `problemset.problems`(2.26MB)+ `contest.list`(407KB)/ AtCoder kenkoooo merged-problems(4.5MB)+ 题面页 HTML / 洛谷 C3VK 通道过反爬后列表(lentille JSON)+ P1001 题面(A+B Problem)/ HackerRank master/algorithms 列表(REST models) |
| A10 | 沙箱内四平台完整链路(插件端点 → SPI → 平台包 → 真实网络) | 通过 | `/api/problems?platform=` codeforces(2257F2 等真实题)/ atcoder(DEGwer2023 系)/ luogu(P1000 超级玛丽游戏、P1001 A+B Problem,C3VK 反爬在插件内自动过)/ hackerrank(solve-me-first);`/api/problem/` 洛谷 P1001 返回 MD 题面(KaTeX 原文)、CF 4A 返回 HTML 题面(Watermelon) |

## B. 留手工项(未自动执行,按下列步骤逐项过)

以下链路需要真实账号 cookie 与人工 GUI 交互,自动化未执行。通用约定:
- 登录形态四平台一致:**粘贴 Cookie**。浏览器登录对应 OJ → DevTools → Network → 任意请求 → 复制完整 Cookie 请求头;IDE 内触发提交/查题时自动弹出「登录 <平台>」对话框(通用 cookie 对话框,经 ojAuthPromptProvider EP 注入),粘贴 → OK;cookie 校验通过后持久化(PasswordSafe),重启免再登
- 未登录/过期时错误以 JSON(`auth-expired`)或对话框如实返回,不静默失败

### B1. CodeForces(cookie 粘贴 + userbox 校验)

1. **登录**:浏览器登录 codeforces.com → 复制 Cookie(`JSESSIONID=xxx; 39ce7=yyy` 形态)→ IDE 内触发 CF 操作弹出对话框粘贴
   - 预期:经 `GET /settings/general` 校验,HTML 出现 `div.userbox` 即通过;无效 cookie 报「cookie 无效或已过期」
2. **查题(匿名可验)**:Problems 页选 CodeForces → 列表(problemset.problems REST,含 acmsguru 合并)
   - 预期:未登录即出全量列表;关键字/难度筛选、翻页正常
3. **题面(匿名可验)**:点任一题 → 题面工具窗口渲染 CF HTML 题面
4. **竞赛(匿名可验,WP24 新增)**:contest 页 → 平台下拉仅 CodeForces → 列表(contest.list)→ 详情(contest.standings 题目引用 + joinable 标记)→ 「参加」经 url 外链到浏览器(参加需登录 POST,平台内交互留后续)
5. **竞赛订阅**:contest 列表打开提醒开关 → 落库;赛前 24h 气球通知(15 分钟轮询粒度);重复订阅覆盖刷新,关闭开关删除
6. **提交(需登录)**:challenge 文件右键 Submit → **Cloudflare Turnstile**:预期 JCEF 弹窗出验证码,人机通过后表单 POST 提交,进度通知 → 终态气球
7. **回看**:浏览器 `https://codeforces.com/contest/{id}/my` 对照;IDE submissions 页出现记录(判定/语言/时间)

### B2. AtCoder(cookie:REVEL_SESSION + Turnstile 提交通道)

1. **登录**:浏览器登录 atcoder.jp → 复制 Cookie(须含 `REVEL_SESSION`)→ IDE 内粘贴
   - 预期:经 `GET /settings` 校验,HTML 含 `#user-nav-tabs` 即通过
2. **查题(匿名可验)**:Problems 页选 AtCoder → kenkoooo 题库列表(IRT 难度换算/色档筛选、分页)
3. **题面(匿名可验)**:点任一题 → 题面渲染(atcoder.jp tasks 页 HTML)
4. **提交(需登录)**:Submit → **Turnstile JCEF 弹窗**(sitekey/challengeSelector 用旧常量)→ 通过后表单 POST(旧字段逐字,CSRF token + turnstile 二元组)→ 轮询 WJ/WR 映射终态
5. **回看**:浏览器 `https://atcoder.jp/submissions/me` 对照
   - 已知限制:**重启 IDE 后旧提交不可续轮**(轮询状态机不持久化,重启后仅能看已落库终态)

### B3. 洛谷(C3VK 反爬 + 题解列表)

1. **查题(匿名可验)**:Problems 页选 洛谷 → 列表(_contentOnly JSON,C3VK 反爬自动过)
2. **题面(匿名可验)**:点任一题(P1001 等)→ 题面以 **Markdown 渲染**(KaTeX 公式),走既有前端管线
3. **题解列表(需登录)**:题面页题解区块 → 预期列出题解引用(标题/作者);点击经 `nav:openUrl` 用系统浏览器外链打开洛谷题解页(正文渲染 M5)
4. **登录**:浏览器登录 luogu.com.cn → 复制 Cookie(`_uid=123; _client_id=xxx`)→ IDE 粘贴;经 `user/setting` 校验
5. **提交(需登录)**:Submit → JSON 提交(CSRF token);**可能触发易盾/图片验证码**:403 按旧实现 JCEF 弹窗链路,本期如实报错兜底(见 C)
6. **回看**:浏览器 `https://www.luogu.com.cn/record/list` 对照

### B4. HackerRank(cookie 粘贴 + 3s 轮询)

1. **查题(匿名可验)**:Problems 页选 HackerRank → master 列表(REST tracks 分页;appsearch 关键字跨题库;projecteuler 带前缀)
2. **题面(需登录)**:详情经 initialData(`__NEXT_DATA__`)拉取 body_html → HTML 渲染;匿名访问预期如实报 auth-expired
3. **登录**:浏览器登录 hackerrank.com → 复制 Cookie → IDE 粘贴
   - 预期:经 `banner-dashboard` 2xx 校验(唯一判定);用户名取 dashboard prefetchedData.profile.username,不可得兜底 "unknown"
4. **提交(需登录)**:Submit(JSON POST,x-csrf-token 头)→ 轮询 3s 固定间隔至 `score_processed == 3` → 按 status 映射终态
5. **回看**:浏览器 HackerRank Submissions 页对照

## C. 已知限制(如实)

- 洛谷提交的易盾/图片验证码(403):旧实现有 JCEF 弹窗链路,本期以 AuthExpiredException 如实报错兜底,稳定复现依赖真机触发,留手工观察
- AtCoder 提交重启后不可续轮:轮询状态机内存态,重启后旧提交只能以已落库终态呈现(与旧实现对齐的事实,如实记录)
- CodeForces「参加」动作为 url 外链兜底(参加需登录 POST,平台内交互留后续迭代)
- kenkoooo 为第三方镜像(旧实现事实),AtCoder 官方无公开题目列表 API
- 各平台提交链路的反爬/Turnstile 人机验证无法自动化,自动化仅覆盖到「弹窗通道触发」层(单测 Mock 验证表单字段与状态机)
