# 力扣 P2 三功能 集成验收清单(loop-20261003-lc-p2 / WP75)

- 日期:2026-10-03
- 规格:`docs/superpowers/specs/2026-10-03-lc-p2-features-design.md`(F1 收藏写 / F2 Hints 折叠 / F3 一键全示例测试)
- 结论速览:三功能后端+前端全量自动化(884+249 测试全绿);runIde 沙箱(IU-2026.2)四组端点真实数据探测全过(hints 匿名 3 条真实拉取、toggle 无登录态 401 auth-expired 透传、detail questionId remote 路径非 null 落库、无能力平台 404);题面页星标两态(401 回滚)与提示折叠交互经 Playwright 无头实测 8/8 全过;编辑器浮动栏第四键与收藏真机登录态链路留人工(§C)

## 0. 全量门禁结果(2026-10-03 实测,1.9.0 版本号下)

| 门禁 | 命令 | 结果 |
|---|---|---|
| Kotlin | `./gradlew clean test check buildPlugin --rerun-tasks` | BUILD SUCCESSFUL(2m 5s,24 任务全实跑零缓存);116 测试类 **884 tests / 0 failures / 0 errors / 0 skipped**;detekt + ktlint + 分层依赖检查(未发现反向/越层依赖或 platform OJ 子包互引)全过;zip 产物 `build/distributions/codequest-1.9.0.zip`(**28,300,014 bytes ≈ 27.0 MB**) |
| 前端 | `cd frontend && npm run test && npm run build` | 26 文件 **249 tests 全过**(17.35s);vite build 成功(6.97s) |

> 测试计数口径注记:884 = WP73 口径 876 + WP74 新增 8,`clean` 后 test-results XML 聚合逐类核对;WP74 commit 记录的「905」为含 build 目录累积旧 XML 的另一计数方式,本清单以干净构建 XML 聚合为准。

## A. 沙箱端点探测(runIde,IU-2026.2 沙箱,插件服务器 127.0.0.1:9626)

idea.log 定位插件内嵌服务器随机端口(本次 9626,日志行 `CodeQuest resource server started on http://127.0.0.1:9626`)。探测后沙箱进程已全部杀净(java 仅剩 Kotlin 编译守护非沙箱、cef_server 0 残留、端口 9626 监听撤销)。

| # | 探测 | 真实响应 |
|---|---|---|
| 1 | `GET /api/problem/two-sum/hints?platform=leetcodecn`(匿名) | HTTP 200(1.93s 真实网络往返)**3 条真实提示**:①暴力双扫→优化思路 ②固定 x 找 y=value-x「可以改变数组让查找更快吗?」(含 `&lt;code&gt;x&lt;/code&gt;`/`&lt;code&gt;y&lt;/code&gt;` HTML 实体,原样透出)③「不改数组能否用额外空间如哈希表?」——与 WP72 KDoc 实测(two-sum 3 条含 HTML 实体)逐条一致,匿名可查协议确认 |
| 2 | `POST /api/favorite/toggle`(body `{platformId:leetcodecn, questionId:1, add:true}`,沙箱无登录态) | HTTP **401** `{"error":"auth-expired","message":"LeetCode 收藏夹列表为空或均缺 idHash(未登录,匿名请求返回空数组;详见 fetchDefaultFavoriteIdHash KDoc)"}`——协议链路端到端验证:匿名 favoritesLists 返回空数组→AuthExpiredException→REST 401 auth-expired 透传,如设计(前端回滚+「请先登录」见 §B A2-A4) |
| 3 | `GET /api/problem/two-sum?platform=leetcodecn`(detail questionId 透出) | HTTP 200,响应含 `questionId` 字段=**null**——该行为 WP71 时代旧缓存行(v4 迁移前落库,ALTER 补列默认 NULL),命中缓存即返回,如实记录降级语义(前端星标中性隐藏);**remote 路径非 null 验证**:换未缓存题 `GET /api/problem/reverse-integer?platform=leetcodecn` → HTTP 200 `questionId="7"`(title 整数反转/MEDIUM,v4 落库回读)——schema v4 迁移+detail 补透出链路过 |
| 4 | `GET /api/problem/two-sum/hints?platform=codeforces`(无能力平台对照) | HTTP 404 `{"error":"unknown-platform","message":"no favorite-write-capable platform registered for id 'codeforces'"}`——CF 无该能力,404 属预期(前端按「无提示」兜底) |

## B. 沙箱 UI 探测(Playwright 无头 chromium,页面=沙箱静态 webview,数据=沙箱真实端点)

| 项 | 结果 |
|---|---|
| 题面页星标两态+401 回滚 | **自动化通过 4/4**:`/problem-view/?platform=leetcodecn&remoteId=reverse-integer`(questionId=7 缓存行)——A1 星标按钮渲染初始态空心 `☆`;A2 未登录点击→乐观切换后回滚空心 `☆`;A3 按钮旁 alert 文本恰为「请先登录」(401 auth-expired 映射);A4 页面真实发出 `POST /api/favorite/toggle` 且响应 status=401(协议链路非 mock);截图 `build/wp75-star-401-rollback.png` |
| 题面页「提示」懒加载折叠 | **自动化通过 4/4**:`remoteId=two-sum`——B1 未点击前零 `/hints` 请求(懒加载);B2 点击后拉取并渲染 3 条(`提示 1/2/3` summary 逐条);B3 逐条默认收起→展开第 2 条正文含解码后 `<code>y</code>` 字面文本且无 `&lt;` 残留(HTML 实体解码生效,文本插值不执行 HTML);B4 再点收起整个区块消失→再点展开累计请求数不变(1,不重拉);截图 `build/wp75-hints-expanded.png` |
| 编辑器浮动栏第四键「全示例测试」 | **留人工**(§C-2):需开刷真实挑战工作区+挑战文件打开才渲染浮动栏,自动化需 IDE 内驱动编辑器,成本高于收益;门控纯函数(`cloudTestCapable`)与全链路(FakeOJTransport 端到端)已由单测/集成测试覆盖(§D F3) |

## C. 留人工走查清单(需登录态或 IDE 内肉眼)

### C-1 收藏真链路(真机登录态)

IDE 内登录力拓 CN → 题面页星标点击:默认夹定位(name==Favorite 认定/fallback 首个含 idHash 夹)→ mutation 成功实心★保持+localStorage 记忆;重复加入/移除不存在的题目幂等成功;站方失败(502)回滚+透传消息;登录过期后点击 401「请先登录」回滚;跨重启记忆态恢复。

### C-2 编辑器浮动栏第四键(真机开刷)

开刷一道 LC 挑战并打开挑战文件:浮动栏出现第四键「全示例测试」(图标 RunAll);点击→右窗「测试用例」tab 显示「全部示例测试结果」标题+X/Y 汇总+逐用例对比;无示例题(或全空白)提示「无示例测试用例」不发起云测;CF/AtCoder 等无云测能力平台该键隐藏;未登录置灰。

### C-3 超长示例输入透传(边缘)

超长题面(示例输入 join 后超站方 interpret 长度限制)点击全示例测试:站方报错应原样透传结果区不白屏(常规题自动已验,超长属边缘留肉眼)。

## D. 自动化验收映射(三功能)

### F1 收藏写(星标两态+toggle)

| 项 | 结论 | 证据 |
|---|---|---|
| 默认夹定位(name==Favorite 认定/fallback 首夹/空夹抛) | 自动化 ✅ | `LeetCodeApiTest.'default favorite hash picks Favorite-named folder then falls back to first'` |
| mutation 两态与未登录信封(ok=true/ok=false 抛/errors 信封) | 自动化 ✅ + 沙箱实测(§A 2) | `LeetCodeApiTest.'add favorite mutation sends protocol variables and accepts ok'` / `'remove favorite mutation mirrors add…'` / `'favorite mutation ok false surfaces as OJException with site error'` / `'favorite mutation unauthenticated errors envelope surfaces as OJException'` |
| REST toggle(成功/401/502/404/400)与平台能力归属 | 自动化 ✅ + 沙箱实测(§A 2) | `FavoriteWriteApiHttpTest` 9 例 |
| 前端星标(中性/乐观切换/回滚/401 文案/记忆/questionId 缺失隐藏) | 自动化 ✅ + Playwright 实测(§B) | `frontend/tests/pages/problem-view-favorite-hints.test.ts` 星标 5 例 |

### F2 Hints 懒加载折叠

| 项 | 结论 | 证据 |
|---|---|---|
| questionHints 三态(多条 HTML 原样/空/失败降级空表) | 自动化 ✅ + 沙箱实测(§A 1) | `LeetCodeApiTest.'questionHints parses string array with html kept verbatim'` / `'questionHints empty array and failures degrade to empty list'` |
| REST hints(200/降级 200 空表/404/400) | 自动化 ✅ + 沙箱实测(§A 1/4) | `FavoriteWriteApiHttpTest` hints 4 例 |
| 前端懒加载/折叠/实体解码/空态/不重拉/非 LC 隐藏 | 自动化 ✅ + Playwright 实测(§B) | `problem-view-favorite-hints.test.ts` hints 4 例 |
| questionId detail 补透出(v4 迁移读写列/缓存同值) | 自动化 ✅ + 沙箱实测(§A 3) | `ProblemApiHttpTest` questionId remote/cache 双路径断言;`SchemaV3MigrationTest` v4 语义(降级去列/升级补列/版本 4) |

### F3 一键全示例测试(第四键)

| 项 | 结论 | 证据 |
|---|---|---|
| 用例组装纯函数(保序 join/空白过滤/空判定+noExamples 文案) | 自动化 ✅ | `TestRunPrepTest` 4 例 |
| 全链路(FakeOJTransport detail→组装→interpret join 透传→多用例终态→payload mode/空示例拒绝/门控 LC 显 CF 隐 null 隐) | 自动化 ✅ | `ExampleCloudTestFlowTest` 3 例[integration] |
| 结果桥推送(mode 标题区分/缺省零回归) | 自动化 ✅ | `TestRunResultStateTest` 1 例(mode 仅显式携带)+ vitest mode 标题 1 例 |
| 浮动栏渲染与真实点击 | 留人工(C-2) | 门控纯函数+集成链路已自动化覆盖 |

## E. spec §5 六条风险结论

| 风险 | 结论 | 证据 |
|---|---|---|
| favoritesLists 无 idHash/默认夹标记 | 已按对策覆盖:实测锁定——响应项含 idHash 但 **FavoriteNode 无 isDefaultFavorite 标记**,按 `name=="Favorite"` 认定+fallback 首个含 idHash 夹(插件源 getUser 锁定),如实记录进 KDoc | WP72 双站实测(commit 0d52012)+ `LeetCodeApiTest.'default favorite hash picks Favorite-named folder then falls back to first'`(三态:具名夹/无具名回退首夹/空抛) |
| mutation 为写操作,协议/权限变动 | 已按对策覆盖:错误透传+前端回滚;实测 questionId 为 `String!` 非 `ID!`,CN 未登录 mutation 走 errors 信封「用户未登录」(401 透传);幂等语义 KDoc 锁定(不在夹中移除视为成功) | `LeetCodeApiTest` mutation 4 例(§D F1)+ vitest 502 回滚/401 文案 2 例 + 沙箱端到端 401 实测(§A 2 / §B A2-A4) |
| questionData 无收藏态字段 | 已按设计降级:双站实测 QuestionNode 无 isFavorited 类字段→前端中性空心星标+localStorage 本地记忆,**未引入新查询协议**;旧缓存行 questionId null 时星标中性隐藏(§A 3 沙箱亲测) | WP72 KDoc 实测记录 + vitest `hides the star when the detail carries no questionId` / `starts solid from localStorage memory…` + Playwright A1 |
| questionHints 私有协议变动 | 已按对策覆盖:SPI 失败降级空表不抛(200 `{hints:[]}`),前端「无提示」兜底不致败页;沙箱匿名实测 3 条真实拉取(§A 1) | `LeetCodeApiTest.'questionHints empty array and failures degrade to empty list'` + `FavoriteWriteApiHttpTest` 降级 200 空表例 + vitest `shows 无提示 for an empty hints array` |
| 全示例 join 后超站方输入长度限制 | 已按对策覆盖:常规题链路实测可行;站方报错经 watchTestRun 既有错误面原样透传结果区(FakeOJTransport 端到端含 interpret 透传断言);超长属边缘留人工走查(C-3) | `ExampleCloudTestFlowTest.'examples flow assembles ordered dataInput from statement and maps multi-case result'`(data_input 按序 join 透传断言)+ C-3 |
| 沙箱无登录态验不了收藏真链路 | 已按对策执行:单测锁协议形态+沙箱记录 401 透传(§A 2/§B A2-A4,Playwright 连真实 401 响应非 mock);真机收藏留人工清单 C-1 | `FavoriteWriteApiHttpTest` 401 例 + `LeetCodeApiTest` 未登录信封例 + §C-1 边界清单 |
