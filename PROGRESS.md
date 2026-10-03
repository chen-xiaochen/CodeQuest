# Agentic Loop 进度

- **当前**: 无活跃 loop(最近完成 loop-20261003-lc-p2,achieved @ 2026-10-03,4/4 WP,定版 1.9.0)
- **上限**: max_iterations=8 / max_round_time_ms=1800000 / max_wall_time_ms=14400000 / divergence_threshold=3 / proximity_goal=0.9
- **调度顺序**: WP72 → WP73 → WP74 → WP75(72/73 收藏+Hints 链;74 独立纯接线;75 恒末)
- **lastUpdatedAt**: 2026-10-03

## History (loop-20261003-lc-p2)
<!-- 每轮追加 -->
| iter | wp | verdict | proximity | failed | 备注 |
|---|---|---|---|---|---|
| 1 | WP72 | wp-achieved→continue | 0.25 (1/4) | 0 | 后端收藏写+Hints 6/6:FavoriteWriteCapable/ProblemHintsCapable(主类实现两变体共用)、fetchDefaultFavoriteIdHash(专用查询+进程缓存+测试钩子)、mutation 骨架(ok=false/errors 抛)、fetchHints 降级空表、toggle/hints 两端点;四项协议实测(isDefaultFavorite 不存在→插件源夹名 Favorite 认定+fallback 首夹/mutation questionId String! 真实站反证 ID!/questionHints 双站匿名实测含 HTML 实体/questionData 无收藏态字段→中性降级如实记录);+16 测共 876 全绿;commit 0d52012;loop 层核验:commit stat+两测试类抽测绿 |
| 2 | WP73 | wp-achieved→continue | 0.5 (2/4) | 0 | 前端星标+Hints 6/6:星标中性初始+乐观切换+localStorage 记忆+失败回滚(401 请先登录/其他透传)、hints 懒加载零请求+逐条 details 折叠+六实体解码(双转义不二次)+缓存态收展、questionId 必答项选 detail 补透出最小案(core ProblemDetail 可选列+schema v4 幂等 ALTER+ProblemApi 落库透出+缓存命中同值,旧缓存 null 降级隐藏,不动 WP72 契约其 16 测原样绿);+9 vitest 共 248+build 2.15s+Kotlin 全量 876+check 双绿;commit eb62ef3;loop 层核验:commit stat+npm 全量复跑 248/248+迁移/ProblemApi 抽测绿 |
| 3 | WP74 | wp-achieved→continue | 0.75 (3/4) | 0 | 全示例第四键 6/6:RunExamplesTestAction(门控等价提取 updateCloudTestActionState 两键共用/能力纯函数)、TestRunPrep 纯函数(保序过滤空白 join)、空示例通知不发云测(有测锁定)、ExampleCloudTestFlowTest 端到端(data_input 按序 join 透传+多用例终态+payload mode);**如实适配:spec 引用 TestRunResultDialog 已 1.5.0 删除,改现行 testcases tab 桥推送通道+mode 字段标题区分**(spec 过时产物纠正);exampleTestcases 走 SPI fetchProblemDetail 一次拿 questionId+示例零新增通道;Kotlin +8 测 905(干净基线 stash 实测 897,WP73 报数 876 为旧口径差异如实记录)+前端 +1 共 249+build 双绿;commit c185305;loop 层核验:commit stat+两测试类抽测+npm 249 复跑 |
| 4 | WP75 | wp-achieved→loop-achieved | 1.0 (4/4) | 0 | 验收定版 6/6:clean --rerun-tasks 门禁 2m5s(Kotlin 116 类 884/前端 249)+zip 28,300,014B;沙箱 4 组端点(hints two-sum 匿名 3 条真测实体原样/toggle 无登录 401 auth-expired 端到端/detail 旧缓存 null 降级+reverse-integer questionId=7 v4 链路过/CF 404 对照)+Playwright 8/8(星标初始☆→点击回滚→真 401「请先登录」截图/hints 零请求→3 条→折叠收展不重拉截图)+沙箱杀净;第四键真机留人工 C-2(门控纯函数+集成链路单测覆盖);计数口径 884=876+8 clean XML 聚合核对(WP74 905 为 build 累积旧 XML 口径,清单注记);CHANGELOG 1.9.0+版本 bump+手动清单;commit 280032d;loop 层核验:commit 干净 3 文件+version grep+zip 存在+提交链完整 |
<!-- loop 初始化 @ 2026-10-03: goal.wpIds=[WP72..WP75], 计划 docs/plan/lc-p2-features.md, iteration=0 -->

## History (loop-20261003-lc-p1)
<!-- 每轮追加 -->
| iter | wp | verdict | proximity | failed | 备注 |
|---|---|---|---|---|---|
| 1 | WP67 | wp-achieved→continue | 0.2 (1/5) | 0 | 后端每日一题+竞赛 9/9:DailyQuestionCapable/ContestCapable 仅 CN(LeetCodeCnPlatform 子类承载,COM is not 探测天然 404)、LeetCodeContestService(phase 窗口推导注入 clock)、/api/daily-question 当日缓存;三项协议真实站实测锁定(todayRecord 数组/contest/api/info 实字段 title_slug 与 spec 预估 question__title_slug 不符按实修正/不存在 slug=HTTP200+body error 判缺失);+21 测共 857 全绿;commit 6abe770;loop 层核验:三测试类抽测绿+headers 疑点下钻(graphql 助手 Referer+operationName 配方与实测成功一致,curl 无 origin 亦 200 复证) |
| 2 | WP68 | wp-achieved→continue | 0.4 (2/5) | 0 | 排序透传 6/6:ProblemFilter.sortBy 忽略语义 KDoc、双站枚举行为探测锁定(CN V1 filters.orderBy{FRONTEND_ID/DIFFICULTY/AC_RATE/FREQUENCY 无方向后缀}/COM V2 sortBy.sortField+sortOrder 恒 ASCENDING;两站 introspection 禁用靠 400 报错锁定)、白名单外回退 CUSTOM/不发、/api/problems id/difficulty 映射、题单公司天然忽略;+5 测共 862 全绿;commit ef668bd;loop 层核验:白名单函数 grep+两测试类抽测绿 |
| 3 | WP69 | wp-achieved→continue | 0.6 (3/5) | 0 | 前端卡片+竞赛接入 6/6:今日题目常驻横幅(字段全/CSS 变量/降级静默三态)、竞赛页平台 tab 能力探测自动出现 LC(零结构改动)、竞赛详情展开+点题新交互(核对结论:原无点题交互非缺陷,新增 platform.value 参数化 CF/LC 共用+LC payload 显式断言)、LC 提醒订阅通用 REST 零改动可用;+8 vitest 共 235+build 1.94s;commit 0d9d73a;loop 层核验:commit stat+npm 全量独立复跑 235/235 |
| 4 | WP70 | wp-achieved→continue | 0.8 (4/5) | 0 | 排序五档+隐藏已解决 4/4:五档循环不换控件(SortMode 联合类型,服务端档 reload 带 sortBy 分页归零/本地档零请求 title 标注,题单公司模式 serverSortAvailable 守门跳过服务端档——简单实现注明)、隐藏已解决复用 statusOf 非 AC 隐藏未登录兜底、cq-problems-hide-solved 持久化、localFilterActive 三条件叠加 filter-note;+4 vitest 共 239+build 2.01s;commit be8ae26;loop 层核验:commit stat+npm 全量独立复跑 239/239 |
| 5 | WP71 | wp-achieved→loop-achieved | 1.0 (5/5) | 0 | 验收定版 6/6:clean 门禁 862(Kotlin 114 类)+239(前端)双绿 zip 28,245,763B;沙箱 7 组端点真实数据(每日一题干净进程 1.65s 首拉+0.0036s 缓存 450x+官方站交叉一致/COM 404、竞赛列表 10 条真实+详情 Q1-Q4 中文标题、sortBy id/difficulty 首五行顺序实变)+Playwright UI(卡片全要素与官方站一致/竞赛 LC tab+详情,登录门控路由拦截模拟+数据真实)+沙箱进程杀净三端口撤销;CHANGELOG 1.8.0+版本 bump;手动清单三态+spec §6 五风险附证据;commit 327cb63;loop 层核验:commit 干净 3 文件+version grep+zip 存在+提交链完整 |
<!-- loop 初始化 @ 2026-10-03: goal.wpIds=[WP67..WP71], 计划 docs/plan/lc-p1-features.md, iteration=0 -->

## History (loop-20261002-lc-p0)
<!-- 每轮追加 -->
| iter | wp | verdict | proximity | failed | 备注 |
|---|---|---|---|---|---|
| 1 | WP59 | wp-achieved→continue | 0.125 (1/8) | 0 | v3 迁移三仓库 5/5:三表 raw DDL 幂等(UNIQUE platform+remote/AUTOINCREMENT 不赌方言)、三 Exposed 仓库+DatabaseManager 两层 wire、DbCodecs epoch 数组;+16 测共 771 全绿;commit aa8f797;loop 层独立核验(commit stat 纯追加+DDL 约束 grep+四测试类抽测绿);过程修正:DDL 首版漏 UNIQUE 自查补上、分页测试期望序修正(实现未动) |
| 2 | WP60 | wp-achieved→continue | 0.25 (2/8) | 0 | 后端评分 9/9:zerotrac 真实拉取(顶层数组 ID/Rating 结构实证)2596 条内置 59KB+SOURCE、RatingService 三态/refresh 失败不破坏 DB、ProblemSummary.frontendQuestionId(其他平台 null)、rating 三端点+user-contest 降级双 null、persistAndRespond 单点装饰(非 LC 反证覆盖);+19 测共 790 全绿;commit 73c8c25;首次 Teamee 429 中断零残留,重派一次过;loop 层核验:资源 2596 条 python 解析+GraphQL 字段 grep+两测试类抽测绿 |
| 3 | WP61 | wp-achieved→continue | 0.375 (3/8) | 0 | 历史同步 9/9:SPI+submissionList GraphQL(参考插件实无此 query,按任务给定协议实现+测试锁定,真实站验证留 WP66 沙箱——偏差如实记录)、runtime/memory 字符串解析脏行跳过、SyncService 吃完整页再停/200 页上限 EXISTING|LIMIT|END、sync/remote 端点、统计并集同键本地优先+remoteSyncedCount(旧装配缺省兼容 11 旧测零改);+18 测共 807 全绿;commit 673dc02;loop 层核验:query 文本 grep+三测试类抽测绿 |
| 4 | WP62 | wp-achieved→continue | 0.5 (4/8) | 0 | 复习调度 5/5:ReviewService(ReviewScheduleStore/ReviewIntervalsSource 端口注入,core 零 infra import,checkLayerDependencies 过)+onAccepted/addManual/completeToday/间隔非法回退/clock 注入/三组视图、AC 触发点 runCatching 不阻断、today/add/complete/config 四端点(today 联查 problem 元数据);+21 测共 828 全绿;commit 6c688d7;首 Teamee 429 中断留主代码(零测试跑过),续做 Teamee 核验语义无 bug+修 4 处编译缺陷(override 缺失/DTO 重名/suspend 包裹/ktlint)+补 HttpTest 零损失恢复;loop 层核验:commit stat+AC 触发 grep+两测试类抽测绿 |
| 5 | WP63 | wp-achieved→continue | 0.625 (5/8) | 0 | 前端评分 7/7:评分列(App.vue 实为行布局,行内 span 适配)+五档着色(lcRatingColor.ts 纯函数,CSS 变量 --cq-rating-* 亮暗双套)+排序循环钮/区间本地过滤(避开 select 断言既有测试零改)+竞赛分徽标+刷新评分按钮+api.ts 三函数+会话缓存;+27 vitest 共 200 过+build 绿(vue-tsc+vite 3.44s);commit 14c713f;loop 层核验:commit stat+npm test 全量独立复跑 200/200 |
| 6 | WP64 | wp-achieved→continue | 0.75 (6/8) | 0 | 前端复习 6/6:ReviewPanel(三分组过期红/今日/未来,行点击 nav:openProblem,epochDay.ts 本地时区格式化防 UTC 偏移)+tab 徽标+间隔配置/标记完成、统计页已同步 N+同步按钮三态、题面页两态按钮(problemId=platform:remoteId 确定性映射零新端点);授权最小 Kotlin diff ReviewRowDto.remoteId(nav 桥需,联查缺失禁点)+2 断言;+8 vitest 共 208 过(2 处旧断言合法更新)+build 绿+ReviewApiHttpTest 绿;commit 81b4add;loop 层核验:diff 范围 git stat+npm 全量独立复跑 208/208+gradle 抽测绿 |
| 7 | WP65 | wp-achieved→continue | 0.875 (7/8) | 0 | 计时器 6/6:SubmissionsBridgeRegistry(CopyOnWriteArrayList 多播,attach/detach 签名不变,单注册与现状逐字节一致)+payload 纯函数增补 platformId/problemRemoteId、problemTimer.ts(注入时钟可测)题面页角标/题面加载重置/AC 匹配停表定格+toast/enableTimer 开关;有据偏差:ProblemViewToolWindowFactory +4 行(题面 tab 桥挂接,spec §4 明文,不挂功能死路——认可);Kotlin +8(单注册/多注册遍历/detach/幂等/单桥失败不中断/payload 断言)共 836、前端 +19(格式化/起停/匹配矩阵/开关)共 227 双绿+build;commit 5d7d86c;loop 层核验:偏差 diff 原文+双端独立复跑 |
| 8 | WP66 | wp-achieved→loop-achieved | 1.0 (8/8) | 0 | 集成验收定版 7/7:lastKey 修复(SUBMISSION_LIST 去 null 字面量,真实站 A/B 实锤 200 vs 403,WP61 协议偏差闭环)+防回归断言;clean test check buildPlugin 836 全绿 zip 28,181,389B+前端 227+build 2.32s;沙箱 11 项端点探测(zerotrac 真实刷新 2596@2.16s/rating 题号关联三题实证/review add-complete 语义/config 400 双例/sync 未登录 401→502 透传如设计);留人工 C-1~C-4 入 docs/lc-p0-manual-checklist.md;CHANGELOG 1.7.0+版本 bump;commit 9236731;首 Teamee 429 中断(发现 lastKey 缺陷立功),续做 Teamee 修复+验收一次过;loop 层核验:commit stat+lastKey grep+zip 存在+LeetCodeApiTest 复跑绿 |
<!-- loop 初始化 @ 2026-10-02: goal.wpIds=[WP59..WP66], 计划 docs/plan/lc-p0-features.md, iteration=0 -->

## History (loop-20260822-editor-actions-favorites)
<!-- 每轮追加 -->
| iter | wp | verdict | proximity | failed | 备注 |
|---|---|---|---|---|---|
| 6 | WP58 | wp-achieved→loop-achieved | 1.0 (6/6) | 0 | 验收定版 6/6:clean 全量门禁(749+165+build);沙箱 Playwright/端点实测(我的题单 favoritesLists 真实拉通);CHANGELOG 1.3.0+zip 27.8MB;spec 六条三态表;Swing 交互留人工 C 节五项 |
| 5 | WP55 | wp-achieved→continue | 0.833 (5/6) | 0 | 浮动栏三键 9/9:AbstractFloatingToolbarProvider+ChallengeFileMatcher+ChallengeEditorGate(TTL 缓存);三 action 全链路(对话框/云端测试门禁/提交复用);resolveTarget 单一定位收敛;+13 测共 749 全绿+buildPlugin 产物核验;429 中断,续做零补齐纯核验恢复 |
| 4 | WP57 | wp-achieved→continue | 0.667 (4/6) | 0 | 前端题单下拉 7/7:fetchFavorites+favorite 参数、两组 optgroup+互斥双向+平台归位、能力探测式渲染(404 隐藏,等价力扣系限定)、myFavorites 降级分层;前端 +6 测共 165+build 绿,gradlew 无回归,loop 层独立核验 |
| 3 | WP54 | wp-achieved→continue | 0.5 (3/6) | 0 | 两对话框 5/5:TestCasesEditState/TestRunResultState 纯函数核(按 id diff/位图判定/CE-RE 分支)+两 DialogWrapper 薄壳;bundle +17 键;+17 测共 737 全绿,loop 层独立核验 |
| 2 | WP56 | wp-achieved→continue | 0.333 (2/6) | 0 | 后端题单 8/8:FavoriteQueryCapable 三方法+18 学习计划(官方索引页实证)+我的题单(实测 favoritesLists 查询,REST 备选,双失败降级)+/api/favorites+favorite 分支(优先 company);+12 测共 720 全绿;429 中断一次零残留,重派注入匿名 API 教训一次过 |
| 1 | WP53 | wp-achieved→continue | 0.167 (1/6) | 0 | SPI 云端测试 6/6:TestEvaluationCapable+三模型、interpret_solution(轮询复用 PollConfig 参数自持循环)、full_* 归一、双变体平台实现;+9 测共 708 全绿,check 过;loop 层独立核验;待 commit |


## History (loop-20260821-ui-revamp)
<!-- 每轮追加 -->
| iter | wp | verdict | proximity | failed | 备注 |
|---|---|---|---|---|---|
| 1 | WP44 | wp-achieved→continue | 0.111 (1/9) | 0 | SPI 模版风格+会话快照 8/8:TemplateStyle(storageKey 助 WP45)、PlatformInfo.templateStyle 必填+16 fake 补齐、AuthCapable currentSession/restoreSession 默认 null、五 auth 恢复段归一(ensureLoggedIn 改调 restoreSession)、entryTemplate 双风格 LEETCODE 字节不变+ACM 五语言 stdin 骨架/旧单参委托;+25 测共 669,check 全绿;commit eb7002f;loop 前置:遗留 1.1.1 直修批次验证后独立落盘 67bcbc3(Kotlin+前端 145 双绿)+loop 初始化 655c0ae,基线干净 |
| 2 | WP45 | wp-achieved→continue | 0.222 (2/9) | 0 | 设置层迁移 7/7:storageKey 键+normalizeTemplateKeys(双写/移除/幂等/新键优先)、双签名+@Deprecated 委托、TemplateDraftsState 双键三语义(续做 Teamee 修 stored 基线空白归一 bug+detekt 降嵌套)、TemplateAvailability、Configurable 编译级适配挂 LEETCODE;+15 测共 684,check 全绿;commit 8ec9636;首个 Teamee 429 限流中断,残留 4 文件落盘,续做 Teamee 凭落盘核验+补齐零损失恢复 |
| 3 | WP48 | wp-achieved→continue | 0.333 (3/9) | 0 | 认证端点 7/7:userId 透传 currentSession(恒 null 契约兑现)、POST /api/auth/refresh 并发 restoreSession(异常吞按未登录)+同构响应+ResourceHttpServer 挂载(与 status 同源实例)、前端 refreshAuthStatus、AuthLoginBridge 三错误串入 bundle 英中(测试 locale 无关);+4 测共 688,check 全绿,前端 145 无回归;commit 5a64b41 |
| 4 | WP46 | wp-achieved→continue | 0.444 (4/9) | 0 | 设置页 4/4:风格下拉+switchSelection 切换 flush 纯函数(时序抽离可测)、TemplateHelpDialog 四节纯文本(避 html 转义,守 WP22 教训)、bundle 英中(旧 keys 改新语义值)、面板接线测试边界如实声明;+2 测共 690,check 全绿;commit c07dbc3 |
| 5 | WP47 | wp-achieved→continue | 0.556 (5/9) | 0 | 创建链路 7/7:templateStyleOf 兜底 LEETCODE、Wiring 双参+压制清偿、Dialog 门控(可用语言/默认 Java/空配置拦截+打开设置)、Bridge 三态自动选语言(替代旧弹菜单,规格行为)、createNewSolution 风格化;+6 测共 696,check 全绿;commit fd3abb8;偏差候选:core null 最后防线仍 LEETCODE 单参(正常链路 UI 已拦截) |
| 6 | WP49 | wp-achieved→continue | 0.667 (6/9) | 0 | 前端题库页 6/6:账号区显式按钮+用户名、平台门控+回落 leetcodecn(真实 id,计划 leetcode-cn 有误已核源码修正)+提示条、status→refresh 静默恢复+status 失败不门控防线、SearchPanel 抽组件+子视图切换、两页中文化;前端 +11 测共 156,build 通过;commit fc9c80e |
| 7 | WP50 | wp-achieved→continue | 0.778 (7/9) | 0 | 前端其余页 6/6:contest 门控(复用 platformGate+防线,不回落力扣)+五阶段中文、stats/problem-view 全文案、submissions 三件套/双 tab+verdict.ts 中文(与 zh bundle 对齐)、landing+AppShell NAV;前端 +3 测共 159,build 通过;commit c838518 |
| 8 | WP51 | wp-achieved→continue | 0.889 (8/9) | 0 | Kotlin 面板重组 8/8:PANELS 4→3、挑战面板 45 条 bundle 化、stripeTitle 编程式显示名(2026.2 无 XML displayName,反编译核实)、挑战窗双 tab(JCEF 惰性+challengeId 联动+桥推送随迁)、Navigator 净新增导航、删 Submissions 窗零残留;696 测+check+buildPlugin 三绿,产物 xml 恰 3 toolWindow;commit 146bad6;第二次 429 中断(零残留),重派一次过 |
| 9 | WP52 | wp-achieved→loop-achieved | 1.0 (9/9) | 0 | 集成验收 4/4:clean check test buildPlugin 全绿(696 测/27MB zip)+前端 159+build;八条验收三态结论(⑦纯人工 B-6/④重启人工 B-3,余自动化或静态核验);ui-revamp-manual-checklist.md;CHANGELOG 1.1.1 并入 1.2.0(定版留用户);commit 7e66e92 |

## History (loop-20260819-codequest-rewrite-m7,已归档)
| iter | wp | verdict | proximity | failed | 备注 |
|---|---|---|---|---|---|
| 1 | WP36 | wp-achieved→continue | 0.125 (1/8) | 0 | 提交详情 4/4:SubmissionDetailCapable(SPI,签名带 problemRemoteId;无详情返 null 不抛——旧 Form 既成事实)、LC/CN WA 三件套必含、CF/AtCoder/洛谷如实仅外链、HR testcases 计数;详情响应含 code/language 为 WP40 铺路;27 Kotlin+5 前端新测(共 560/106);429 中断一次恢复 |
| 2 | WP37 | wp-achieved→continue | 0.25 (2/8) | 0 | 状态列+筛选 4/4:ProblemStatusStore 端口先例(AC 粘性/多 challenge 取最高)、两页图标(🔒SVG/✓/●)本地过滤(激活注明仅已加载项)、paidOnly 字段先行恒 false(SPI 透传边界→偏差候选);12 Kotlin+15 前端新测(共 571/121) |
| 3 | WP38 | wp-achieved→continue | 0.375 (3/8) | 0 | 账号菜单 4/4:authPlatformSourceProvider EP 反转注入(infra 读 ui 缓存实例零竞态)、performLogout 逻辑核、problems 账号条(徽标两态/防重/结果刷新)、userId 恒 null 如实(SPI 无只读 session→偏差候选);11 Kotlin+5 前端新测(共 582/126);首次派发遇分类器超时重试成功 |
| 4 | WP39 | wp-achieved→continue | 0.5 (4/8) | 0 | 模板设置+自定义 CSS 5/5:templates map+customCss 持久化、语言下拉+单编辑器(TemplateDraftsState 草稿核)、两条创建链路共用 createChallengeWithTemplate(core 零改动)、/api/custom.css 端点+页面注入(下次加载生效如实记录);19 Kotlin+4 前端新测(共 601/130) |
| 5 | WP40 | wp-achieved→continue | 0.625 (5/8) | 0 | 代码回看 4/4:Details/Code 双 tab(per-row 保持)、hljs 高亮+别名表+未知降级、空快照文案、Copy 按钮;纯前端零 Kotlin;5 前端新测(共 135) |
| 6 | WP41 | wp-achieved→continue | 0.75 (6/8) | 0 | 题解排序+缩放 3/3:orderBy 端点不透明透传(空白→null)、SPI 默认参数最小增量(LC 映射 mostUpvote/hot/newest/oldest,COM oldest 发 JsonNull 如旧;洛谷如实忽略)、webview:zoom 桥(delta 步进/level 直设,±1 级 ×1.2 钳制 [−7,+8]≈28%–430%,不持久化);题面页排序下拉(仅 LC 系)+缩放三键;Kotlin 623(22 新)+前端 139(4 新)全绿;commit 8a12ca0;429+用户停止两次中断,新派 Teamee 凭落盘零损失恢复 |
| 7 | WP42 | wp-achieved→continue | 0.875 (7/8) | 0 | LC 公司题单 3/3:SPI=CompanyQueryCapable 新能力接口(fetchCompanies+queryCompanyProblems,不污染 ProblemFilter);公司列表 questionCompanyTagsV2(CN companyTagInfoNodes/COM companyTags)/公司题单 favoriteQuestionList(favoriteSlug=公司[-期间后缀],filtersV2 完整骨架,searchKeyword 恒空如旧);problems 页能力探测式公司下拉+期间下拉(默认 30 天);paidOnly 改真值透传(WP37 偏差候选清偿,🔒 图标生效);不兼容如实记录:公司模式 keyword 忽略(UI 提示条)/UI 单选公司(端点协议层支持多公司 head/tail 拆分)/position·tag 筛选未接入;Kotlin 636(13 新)+前端 145(6 新)全绿;commit a175e06;另修 FakeOJTransport GraphQL 信封引号转义 |
| 8 | WP43 | wp-achieved→loop-achieved | 1.0 (8/8) | 0 | M7 集成验收 4/4:clean check test buildPlugin --rerun-tasks 全绿(Kotlin 636/前端 145/zip 26.3MB 完整);runIde 沙箱 16 项端点探测(B 注入 3 challenge+5 submission 三态核数精确/A 详情错误契约/C userId=null 实证/G 端点/H 排序真实变化/J 184 公司列表)0 ERROR;docs/m7-manual-checklist.md(自动化 A1-A16+手工 B1-B5+偏差 C 节);九项偏差终版 11 条入档(新发现 J4 公司题单上游需有效登录,匿名一律 total=0 无区分提示);E 复核维持不做(noteMd+FTS 等价);loop 层「牛客」为笔误,按六平台工程事实记录;commit 2017c23 |

---

## 已完成 loop 归档

### loop-20261003-lc-p2(achieved):力扣 P2 三项——收藏写(FavoriteWriteCapable 默认夹按名 Favorite 认定+mutation questionId String! 实测纠正+星标乐观切换本地记忆+detail v4 补透出 questionId)+Hints(questionHints 双站匿名可查+懒加载逐条折叠)+全示例测试(浮动栏第四键,TestRunResultDialog 已删如实适配桥推送 mode 区分)——定版 1.9.0(WP72-75,commits 0d52012..280032d;沙箱真测 hints 3 条/toggle 401 端到端/Playwright 8/8;留人工 docs/lc-p2-manual-checklist.md C-1~C-3;协议四项实测两处纠正 spec 预估)
### loop-20261003-lc-p1(achieved):力扣 P1 三项——每日一题(CN 常驻卡片直达/questionOfToday 协议实测 Referer+operationName 配方)+LC 竞赛回顾(仅 CN ContestCapable/contestHistory+contest/api/info 实测锁定/详情点题)+排序五档(sortBy 双站枚举实测 FRONTEND_ID/DIFFICULTY+隐藏已解决)——定版 1.8.0(WP67-71,commits 6abe770..327cb63;沙箱真实数据+Playwright UI;留人工 docs/lc-p1-manual-checklist.md C-1~C-4;三轮协议字段与 spec 预估不符均按实测修正)
### loop-20261002-lc-p0(achieved):力扣 P0 四项——zerotrac 难度评分(列/五档着色/区间/刷新/竞赛分)+站内提交历史同步(submissionList GraphQL/增量/统计合并)+间隔重复复习(AC 排期 1/4/7/14/28/60/复习子视图/加入复习)+题目计时器(多播桥/AC 停表)——定版 1.7.0(WP59-66,commits aa8f797..9236731;真实站 A/B 实锤 lastKey 修复;沙箱 11 项端点探测;留人工 docs/lc-p0-manual-checklist.md C-1~C-4)
### loop-20260822-editor-actions-favorites(achieved):编辑器三键(修改测试用例/云端提交测试/提交浮动栏)+题库页题单下拉(学习计划 18+我的题单)——定版 1.3.0(WP53-58,commits ab912f7..2c93b1b+本批;沙箱 Playwright 实测题单两组+我的题单 favoritesLists 真实拉通;人工走查 docs/editor-actions-manual-checklist.md)
### loop-20260821-ui-revamp(achieved):UI 重构——面板精简 4→3/平台登录门控+力扣中国回落/模版 ACM·LeetCode 双风格×语言显式启用+默认Java/全界面中文化(WP44-52,commits eb7002f..7e66e92;人工走查 docs/ui-revamp-manual-checklist.md C 节(沙箱+Playwright 实测 B-1/2/3/7+端点恢复全 PASS);定版 1.2.0)
### loop-20260819-codequest-rewrite-m7(achieved):原版设计对齐九项——提交详情/状态列/账号/模板CSS/代码回看/题解排序/缩放/公司题单(WP36-43,commits 6ecbd62..2017c23)
### 1.0.x 直修(非 loop):浏览链路登录入口(a42a105)/力扣CN 中文化(eb28532)/一键开刷(db898bd)
### 2026-10-03 直修(非 loop,brainstorming bounded 路径):提交记录页混合展示站内历史——/api/submissions/remote platform 放开可缺省(跨平台页+countAll)、submissions 页本地+站内合并(倒序/同键本地优先/站内徽标/轻量展开外链/挑战定向纯本地/失败静默降级);前端 +5 测共 254+Kotlin check 全绿
### loop-20260819-codequest-rewrite-m6(achieved):删旧工程/CI 单轨/文档/1.0.0 发布
### loop-20260818-codequest-rewrite-m5(achieved):统计/检索/题解正文
### loop-20260816-codequest-rewrite-m4(achieved):四平台铺开,SPI 量产验证
### loop-20260815-codequest-rewrite-m3(achieved):SPI 契约+LeetCode 闭环
### loop-20260814-codequest-rewrite-m1m2(achieved):骨架/门禁/JCEF/前端/模型/数据层/settings/工作区
### loop-20260803-codequest-ui(实质达成):旧 Scala 侧 UI 优化(旧工程已删)
