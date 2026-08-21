# Agentic Loop 进度

- **当前**: 无活跃 loop(最近完成 loop-20260821-ui-revamp,achieved @ 2026-08-21,9/9 WP)
- **上限**: max_iterations=12 / max_round_time_ms=600000 / max_wall_time_ms=7200000 / divergence_threshold=3 / proximity_goal=0.9
- **lastUpdatedAt**: 2026-08-21

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
| 9 | WP52 | wp-achieved→loop-achieved | 1.0 (9/9) | 0 | 集成验收 4/4:clean check test buildPlugin 全绿(696 测/27MB zip)+前端 159+build;八条验收三态结论(⑦纯人工 B-6/④重启人工 B-3,余自动化或静态核验);ui-revamp-manual-checklist.md;CHANGELOG 1.1.1 并入 1.2.0(定版留用户);commit 7e66e92 |
| 8 | WP51 | wp-achieved→continue | 0.889 (8/9) | 0 | Kotlin 面板重组 8/8:PANELS 4→3、挑战面板 45 条 bundle 化、stripeTitle 编程式显示名(2026.2 无 XML displayName,反编译核实)、挑战窗双 tab(JCEF 惰性+challengeId 联动+桥推送随迁)、Navigator 净新增导航、删 Submissions 窗零残留;696 测+check+buildPlugin 三绿,产物 xml 恰 3 toolWindow;commit 146bad6;第二次 429 中断(零残留),重派一次过 |

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

### loop-20260821-ui-revamp(achieved):UI 重构——面板精简 4→3/平台登录门控+力扣中国回落/模版 ACM·LeetCode 双风格×语言显式启用+默认Java/全界面中文化(WP44-52,commits eb7002f..7e66e92;人工走查 docs/ui-revamp-manual-checklist.md;1.2.0 待定版)
### loop-20260819-codequest-rewrite-m7(achieved):原版设计对齐九项——提交详情/状态列/账号/模板CSS/代码回看/题解排序/缩放/公司题单(WP36-43,commits 6ecbd62..2017c23)
### 1.0.x 直修(非 loop):浏览链路登录入口(a42a105)/力扣CN 中文化(eb28532)/一键开刷(db898bd)
### loop-20260819-codequest-rewrite-m6(achieved):删旧工程/CI 单轨/文档/1.0.0 发布
### loop-20260818-codequest-rewrite-m5(achieved):统计/检索/题解正文
### loop-20260816-codequest-rewrite-m4(achieved):四平台铺开,SPI 量产验证
### loop-20260815-codequest-rewrite-m3(achieved):SPI 契约+LeetCode 闭环
### loop-20260814-codequest-rewrite-m1m2(achieved):骨架/门禁/JCEF/前端/模型/数据层/settings/工作区
### loop-20260803-codequest-ui(实质达成):旧 Scala 侧 UI 优化(旧工程已删)
