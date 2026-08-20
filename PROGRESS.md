# Agentic Loop 进度

- **当前**: 无活跃 loop(最近完成 loop-20260819-codequest-rewrite-m7,achieved @ 2026-08-21,8/8 WP)
- **lastUpdatedAt**: 2026-08-21

## History
<!-- 每轮追加 -->
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

### loop-20260819-codequest-rewrite-m7(achieved):原版设计对齐九项——提交详情/状态列/账号/模板CSS/代码回看/题解排序/缩放/公司题单(WP36-43,commits 6ecbd62..2017c23)
### 1.0.x 直修(非 loop):浏览链路登录入口(a42a105)/力扣CN 中文化(eb28532)/一键开刷(db898bd)
### loop-20260819-codequest-rewrite-m6(achieved):删旧工程/CI 单轨/文档/1.0.0 发布
### loop-20260818-codequest-rewrite-m5(achieved):统计/检索/题解正文
### loop-20260816-codequest-rewrite-m4(achieved):四平台铺开,SPI 量产验证
### loop-20260815-codequest-rewrite-m3(achieved):SPI 契约+LeetCode 闭环
### loop-20260814-codequest-rewrite-m1m2(achieved):骨架/门禁/JCEF/前端/模型/数据层/settings/工作区
### loop-20260803-codequest-ui(实质达成):旧 Scala 侧 UI 优化(旧工程已删)
