---
title: CodeQuest 原版设计对齐(九项)设计
date: 2026-08-19
status: done
source: 用户「全面参考原版的设计」需求(brainstorming 差距矩阵圈定九项,E 笔记已有等价物不做)
scope: 原版体验全面对齐(提交详情/状态列/账号/模板/代码回看/CSS/排序/缩放/公司题)
---

# CodeQuest 原版设计对齐(九项)设计

## 背景与动机

重写版(M1-M6)已对齐原版核心链路,但考古旧 Scala 实现(git `a8f69a0^`)发现九项原版设计缺失,拉低了刷题体验:看不到提交错在哪、列表不知道哪些刷过、账号无处管理、模板写死等。本轮(编号 M7)全面补齐。

**总原则**:交互形态参考原版、实现走新架构(SPI/桥/统一前端);每项的原版行为以旧 Scala 代码为事实源(下表「原版事实」列文件);分层规则与既有门禁不变。

## 九项设计(差距矩阵定稿)

### A. 提交结果详情(最高价值)

- **原版事实**:`toolwindows/sidebar/submissionlog/`——每平台 SubmissionResultForm 结构化展示:判定/耗时/内存 + **错误详情**(LeetCodeErrorResultForm:失败用例的输入/期望输出/实际输出);编辑器侧侧栏 SubmissionLog 视图
- **新设计**:`SubmissionCapable` 平台包内加「提交详情拉取」(SPI 增量最小化:不加接口,详情作为平台包公开服务供端点按需调用;或经 `watchSnapshots` 扩展——实现时按事实源定,原则:**LC 系必须含错误用例三件套**,其余平台按各自 Form 的真实字段);端点 `GET /api/submission/:id/detail`(本地 submission.remoteSubmissionId → 平台拉详情);submissions 页行点击展开详情面板(verdict/耗时/内存/**错误用例 input/expected/output 对比**/代码快照联动 F);缓存不落库
- 验收:mock 夹具锁定各平台详情解析;LC 错误用例三件套渲染

### B. 列表状态列与状态筛选

- **原版事实**:`KeywordQueryPresenter` 列定义——Status 列:🔒(paidOnly && 非会员)/✓(Solved)/已尝试图标;`StatusParameterAction` 状态筛选
- **新设计**:`/api/problems` 响应每项加 `status: 'AC' | 'TRIED' | 'UNSOLVED'`(本地聚合:该题 linked challenge 的 submission 有 AC→AC,有提交→TRIED;纯本地零平台请求)+ `paidOnly`(平台列表数据有则带);problems 页状态图标列 + 状态下拉筛选(本地过滤,列表页内实现,不打到平台);search 页同步
- 验收:聚合单测(AC/Tried/未做/多 challenge 取最高);前端图标与筛选测试

### C. 账号菜单

- **原版事实**:`AccountActionGroup`(顶栏)+ `UserAccountInfoAction`(用户名/头像)+ 每平台 `LoginAction`/`LogoutAction`
- **新设计**:problems 页头部(AppShell 内)账号区:各平台 `登录态+用户名` 徽标(端点 `GET /api/auth/status`:同步快照 isLoggedIn+缓存 userId;不主动打平台);点徽标→登录(经既有 `auth:login` 桥)/登出(新桥 `auth:logout` → AuthCapable.logout + 清缓存);登录成功事件后刷新
- 验收:logout 桥命令单测;前端徽标/登录/登出测试

### D. 语言模板设置

- **原版事实**:`settings/LanguageSettingsPanel`——每语言代码模板可在设置页编辑
- **新设计**:Settings → CodeQuest 加「Templates」区:每 WorkspaceLanguage 一个多行编辑器(默认值=内置模板,恢复默认按钮);持久化走既有 PersistentStateComponent;`ChallengeWorkspaceService.createChallenge(template=...)` 参数已有——接线:创建时(含一键开刷)读设置覆盖内置模板
- 验收:设置读写单测;创建链路读取设置的单测

### F. 提交代码回看

- **原版事实**:`OpenSubmissionCodeAction`——从提交历史打开该次提交的代码
- **新设计**:submissions 页行操作「View code」→ 展开只读代码视图(hljs 高亮,数据=本地 codeSnapshot,零网络);与 A 的详情面板同区切换
- 验收:前端展开/高亮测试

### G. 题面自定义 CSS

- **原版事实**:`ChallengeDescriptionStyle` + settings「描述视图自定义 CSS」
- **新设计**:Settings 加 CSS 文本域(带校验:仅存文本);题面页加载后 Kotlin 侧 `bridge.evaluate` 注入 `<style>`(或端点 `/api/custom.css` 供页面引入——选实现简单者并写明);亮暗主题均生效
- 验收:注入链路测试(CSS 文本透传)

### H. 题解排序

- **原版事实**:`ArticleOrderByAction`(LC 题解列表排序选项)
- **新设计**:`/solutions` 端点加 `orderBy` 参数(选项按旧实现事实,如 最新/最热/投票);前端题解区块排序下拉
- 验收:参数透传+解析单测

### I. 题面 webview 缩放

- **原版事实**:`ZoomIn/ZoomOut/ActualZoom` actions
- **新设计**:题面/阅读器区缩放:桥命令 `webview:zoom` {delta 或 level} → Kotlin `cefBrowser.setZoomLevel`(JCEF 原生);题面页工具栏 +/−/100% 三键
- 验收:桥命令接线(Kotlin 侧可测缩放值计算,executeJ 挂 JCEF)

### J. LeetCode 公司题单

- **原版事实**:`LeetCodeCompanyQueryPresenter`/`LeetCodeCompanyChallengeList`(公司筛选题单,GraphQL companyTags)
- **新设计**:LC/CN 平台包加公司题查询(端点/GraphQL 按旧实现事实);problems 页 LC 系平台时出现「公司」下拉(数据经 `/api/leetcode/companies` 或并入 platforms 元数据——按简洁定);选中后列表走公司题单查询
- 验收:公司列表/公司题单 mock 夹具

## 不做(E 笔记独立视图)

solution.noteMd(多解法笔记+FTS 检索)已等价覆盖原版 Notes 侧栏;不做重复视图。

## 里程碑与验收

M7 一轮 loop 交付九项(A-F-G-H-I 单元可并行度低,按序)。总验收:
- `./gradlew clean check test buildPlugin` + `npm test/build` 全绿;产物 zip 完整
- 沙箱实测:B 状态列(注入提交数据)/C 账号徽标与登出/A 详情(公开数据或 mock)/D 模板设置改后新 challenge 生效
- docs/m7-manual-checklist.md:真实账号链路手工步骤(A 详情/C 登录登出/J 公司题)
- 每项与原版行为对照的偏差如实记录

## 成功标准

1. 九项全部落地且各有自动化测试;E 明确不做并记录理由
2. 刷题闭环体验对齐原版:列表知状态→开刷→提交→**看错在哪**→改码→再提交;账号可管理;模板可定制
3. 门禁全绿;旧 Scala 工程零残留状态不变;spec 4 条成功标准不受损
