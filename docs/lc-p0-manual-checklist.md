# 力扣 P0 四功能 集成验收清单(loop-20261002-lc-p0 / WP66)

- 日期:2026-10-03
- 规格:`docs/superpowers/specs/2026-10-02-lc-p0-four-features-design.md`(F1 难度评分 / F2 站内历史同步 / F3 间隔复习 / F4 题目计时器)
- 结论速览:四功能后端+前端逻辑全量自动化(836+227 测试全绿);REST 新端点 9 组沙箱实测通过;`submissionList` 查询 `lastKey: null` 非法字面量已修复并经真实站 A/B 实证(200 vs 403);真机登录态交互(同步历史/计时器起停/AC 排期)留人工(§C)

## 0. 全量门禁结果(2026-10-03 实测)

| 门禁 | 命令 | 结果 |
|---|---|---|
| Kotlin | `./gradlew clean test check buildPlugin` | BUILD SUCCESSFUL in 1m 39s;112 测试类 **836 tests / 0 failures / 0 errors / 0 skipped**;detekt + ktlint 全过;zip 产物 `build/distributions/codequest-1.7.0.zip`(**28,181,389 bytes ≈ 26.9 MB**) |
| 前端 | `cd frontend && npm run test && npm run build` | 24 文件 **227 tests 全过**(14.35s);vite build 成功(2.32s) |

### lastKey 非法字面量修复(本次验收必修项)

前 Teamee 发现 `LeetCodeGraphQL.SUBMISSION_LIST` 查询文本带 `lastKey: null` 参数字面量——非布尔/null 字面量不可作为 GraphQL 参数值,真实站会拒绝。处置:

1. `kotlin/src/main/kotlin/com/codequest/platform/leetcode/LeetCodeGraphQL.kt`:查询文本去掉 `lastKey: null` 参数(分页纯 offset/limit);selection 里的 `lastKey` 返回字段合法保留。
2. `kotlin/src/test/kotlin/com/codequest/platform/leetcode/LeetCodeApiTest.kt`:新增防回归断言 `assertFalse(LeetCodeGraphQL.SUBMISSION_LIST.contains("lastKey:"))`(既有 query 文本锁定断言引用常量自动同步)。
3. 解析侧核验无需改动:`LeetCodeApi.kt` 的 hasMore 判定为「lastKey 非空字符串」,响应无该字段/为 null → `hasMore=false`,语义不变。
4. `LeetCodeApiTest` + `SubmissionHistoryApiHttpTest` 重跑全绿。

**真实站 A/B 实证(2026-10-03,curl 直发 `https://leetcode.com/graphql/`,无认证,同一 variables):**

| 查询文本 | 结果 |
|---|---|
| 修复后(无 `lastKey:` 参数) | **HTTP 200** `{"data":{"submissionList":{"submissions":null,"lastKey":null}}}`——语法被真实站接受,未登录 submissions=null 属正常语义 |
| 带 `lastKey: null` 字面量 | **HTTP 403 Forbidden**(Django 拒绝页)——实锤该字面量为站方拒绝的非法语法 |

## A. 沙箱端点探测(runIde,IU-2026.2 沙箱)

idea.log 定位插件内嵌服务器 **127.0.0.1:7185**(随机端口)。探测后沙箱 IDE 进程已全部杀净(java + 全部 cef_server,0 残留;端口监听撤销,仅 TIME_WAIT 尾巴;剩余 java 进程为 Gradle/Kotlin 构建守护,非沙箱)。

| # | 探测 | 真实响应 |
|---|---|---|
| 1 | `GET /api/rating/status` | HTTP 200 `{"source":"db","count":2596,"updatedAt":null}`——bundled 快照载入入库 |
| 2 | `POST /api/rating/refresh` | HTTP 200 `{"count":2596,"updatedAt":"2026-10-03T10:29:18.092Z"}`,2.16s——真实网络拉 zerotrac 成功 |
| 3 | `GET /api/rating/user-contest?platform=leetcodecn` | HTTP 200 `{"rating":null,"attendedContestsCount":null}`——沙箱本次无登录态,双 null 属预期退化 |
| 4 | `GET /api/review/today` | HTTP 200 三分组 `{"overdue":[],"today":[],"upcoming":[…1 行],"totalTracked":1}`——沙箱库既有残留行 leetcodecn:happy-number(2026-10-04 到期),空今日/过期分组结构正确 |
| 5 | `GET /api/review/config` | HTTP 200 `{"intervals":[1,4,7,14,28,60]}` 默认 |
| 6 | `PUT /api/review/config` 合法 | `[1,3,7,21]` → HTTP 200 回显,GET 读回一致;已恢复默认 |
| 7 | `PUT /api/review/config` 非法 | `[0,5]` 与 `[7,3]` 均 HTTP 400 `{"error":"bad-request","message":"intervals must be positive strictly-ascending integers, e.g. 1,4,7,14,28,60"}` |
| 8 | `POST /api/review/problems/leetcode:two-sum/add` | HTTP 200 `planDates` 6 项(明日零点起 1/4/7/14/28/60 天,epoch 1791043200…1796140800);`/api/review/today` upcoming 出现该行(title 无题库元数据回退 problemId 原文,符合设计) |
| 9 | `POST /api/review/problems/leetcode:two-sum/complete` | HTTP 200 `historyDates:[当日]`;complete 语义=移除「今天及过去」排期日——手动 add 从明天起排,故明日行仍在 upcoming(与设计一致;到期日 complete 移除逻辑由 `ReviewServiceTest.'completeToday removes today and past…'` 覆盖) |
| 10 | `GET /api/problems?platform=leetcodecn&limit=5` | HTTP 200 每行含 `rating` 字段;前 5 行(hot 基础题)rating null 属预期(非竞赛题不在 zerotrac 覆盖内);`keyword=merge-stones` 定向验证:`minimum-cost-to-merge-stones` rating=2422.53 / `merge-strings-alternately` 1166.53 / `largest-merge-of-two-strings` 1828.64——题号关联生效,竞赛题有分 |
| 11 | `POST /api/submissions/sync`(重点) | **HTTP 502** `{"error":"sync-failed","message":"auth expired or missing at HTTP 401: {\"detail\":\"Authentication credentials were not provided.\"}"}`,0.89s——沙箱未登录,站方 401 如实透传为领域错误(错误链路=设计对策「错误透传+按钮失败提示」的行为验证);配合 §0 A/B 实验(修复后查询文本被真实站 200 接受)共同构成 lastKey 修复后对真实站的最终验证 |

## B. 自动化验收映射(四功能)

### F1 难度评分

| 项 | 结论 | 证据 |
|---|---|---|
| 内置快照/DB 优先/刷新 upsert/失败保库 | 自动化 ✅ | `RatingServiceTest.kt` 6 例(`load prefers db snapshot…` / `load falls back to bundled…` / `load reports empty…` / `refresh upserts db…` / `refresh fetch failure keeps db…` / `refresh parse failure keeps db intact`) |
| REST 三端点 | 自动化 ✅ + 沙箱实测(§A 1-3) | `RatingApiHttpTest.kt` 11 例(status 两态/refresh 成败/user-contest 三退化+能力门控/problems 行携带 rating/题单模式同装饰/非力掠톡 null) |
| 前端五档着色/区间过滤/排序 | 自动化 ✅ | `frontend/tests/lib/lcRatingColor.test.ts` 15 例(五档左闭右开/小数口径/无竞赛分退化绝对档/无分恒 null/标签/显示/双边过滤/默认序零开销/无分沉底/不改原数组) |
| 着色/过滤/列显隐 UI 肉眼 | 留人工(C-1) | — |

### F2 站内提交历史同步

| 项 | 结论 | 证据 |
|---|---|---|
| 分页拉取/解析/hasMore | 自动化 ✅ | `LeetCodeApiTest.kt` submissionList 4 例(offset/limit 变量与 query 文本锁定+**lastKey 防回归断言**/脏行跳过/未登录领域异常) |
| 增量停止/页上限保护/结束判定/能力门控 | 自动化 ✅ | `SubmissionHistorySyncServiceTest.kt` 5 例(`first page fully existing stops…` / `mixed page persists…` / `page limit protection caps sync…` / `last page hasMore false ends normally` / `unknown or incapable platform…`) |
| REST sync/remote 端点 | 自动化 ✅ + 沙箱实测(§A 11) | `SubmissionHistoryApiHttpTest.kt`(sync 计数/二次零增量/脚本化分页) |
| 登录态真实拉取落库 | 真机留人工(C-2) | 沙箱无登录态,站方 401 透传已实测 |
| 统计两表合并/streak/热力图 | 自动化 ✅ | `SubmissionHistorySyncServiceTest` + 统计合并用例(两表并集、同键本地优先) |

### F3 间隔重复复习

| 项 | 结论 | 证据 |
|---|---|---|
| 首 AC 排期/再 AC 移除/幂等/过期重生成/completeToday/今日分组/零点边界/间隔回退 | 自动化 ✅ | `ReviewServiceTest.kt` 16 例 |
| 仓储 round-trip/替换/缺失/全列 | 自动化 ✅ | `ReviewRepositoryIntegrationTest.kt` 4 例 |
| REST 四端点 | 自动化 ✅ + 沙箱实测(§A 4-9) | `ReviewApiHttpTest.kt` 6 例(三分组+元数据 join+回退/add 幂等/complete 404/config 默认+合法+非法 400) |
| AC 触发点不阻断提交 | 静态核验 ✅ | `SubmissionCoordinator.kt:151-155`:`verdict.isSuccessful` → `runCatching { reviewService().onAccepted(...) }` 失败仅告警 |
| 前端复习子视图/加入复习两态 | 自动化(渲染逻辑)✅;肉眼确认留人工(C-3) | vitest problems 页用例 |

### F4 题目计时器

| 项 | 结论 | 证据 |
|---|---|---|
| bridge 单槽改多播 | 自动化 ✅ | `SubmissionCoordinatorTest.kt` 8 例(单注册恰一次/多注册各一次/解绑不再收/同键幂等/空注册 no-op/单失败不阻塞他者/payload 保旧字段+增补 platformId/problemRemoteId/非 AC verdict 原样) |
| 前端起表/停表/mm:ss 与 h:mm:ss 切换/开关 | 自动化 ✅ | `frontend/tests/lib/problemTimer.test.ts` 14 例 |
| 题面加载重置/AC 定格+耗时 toast 真机 | 留人工(C-4) | — |

## C. 留人工走查清单(需登录态或 IDE 内肉眼)

### C-1 评分列 UI(力扣中国)

题库页「评分」列:竞赛题五档着色(绿/蓝/紫/黄/红)、无分显示「-」、min/max 区间过滤、着色模式与列显隐存 localStorage 重开保持;账号区竞赛分徽标+「刷新评分」按钮成功/失败提示。

### C-2 站内历史同步真机(重点)

登录力扣中国 → 统计页「同步历史」→ 应真实分页拉取并提示同步 N 条;再次点击零增量;streak/热力图/AC 计数并入站内记录。(沙箱无登录态,仅验证到 401→502 透传;查询语法已经真实站 A/B 实证。)

### C-3 复习子视图与加入复习

题库页「复习」子视图三分组(过期红/今日/未来)渲染、入口徽标=今日到期数、行点击开题面、间隔配置保存;题面页「加入复习」两态(未排期→加入;已排期→显示目标日期)。

### C-4 计时器与 AC 排期真机

题面页计时角标起表(mm:ss,≥1h 切 h:mm:ss)、切换题目重置、真实 AC 后停表定格+本题耗时 toast、`enableTimer` 开关;AC 后复习子视图今日/未来出现该题、次日到期后「标记完成」移除。

## D. spec §7 前四条风险结论

| 风险 | 结论 | 证据 |
|---|---|---|
| zerotrac 数据格式变动/下线 | 已按对策覆盖:内置兜底+刷新失败不破坏 DB | `RatingServiceTest` 6 例(db 优先/bundled 回退/refresh upsert/拉取与解析失败双路径保库);沙箱实测 refresh 200(§A 2) |
| `submissionList` 站方私有协议可能变动 | 已按对策覆盖:错误透传+SPI 隔离 | `SubmissionHistorySyncServiceTest` 5 例(增量停止/页上限保护/正常结束/能力门控);沙箱实测 401→502 透传(§A 11);查询语法修复后经真实站 200 实证(§0) |
| CN 站 `userContestRankingInfo` 未参赛/未登录无数据 | 已按对策覆盖:返回 null,着色退化绝对分档 | `RatingApiHttpTest.'user contest degrades to double null…'` 2 例;沙箱实测双 null(§A 3);`lcRatingColor.test.ts`「无用户竞赛分时按绝对分五档」15 例着色体系 |
| bridge 单槽改多播 | 已按对策覆盖:兼容改造,单注册行为与现状一致 | `SubmissionCoordinatorTest` 8 例(`single registration receives exactly once` 锁定旧行为,多播/解绑/幂等/失败隔离全绿);前端 `problemTimer.test.ts` 14 例回归计时消费端 |

(spec §7 另两条——历史同步量大的页上限 4000 条与 pooled 线程:前者 `page limit protection caps sync and reports LIMIT` 覆盖;时区零点:`ReviewServiceTest.'midnight boundary keeps today grouping until zero point crosses'` 覆盖。)
