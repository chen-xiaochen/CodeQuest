# CodeQuest M7:原版设计对齐九项

按 spec(docs/superpowers/specs/2026-08-19-legacy-parity-design.md)补齐九项原版设计。交互参考原版(git a8f69a0^ 为事实源)、实现走新架构(SPI/桥/统一前端);每项原版行为以旧 Scala 代码为准,Teamee 执行时先考古对应旧文件再动手。E 笔记不做(noteMd 已等价)。

## 实现提交结果详情(A)
- [ ] 平台包加提交详情拉取(LC/CN 必含错误用例三件套 input/expected/output,字段按旧 LeetCodeErrorResultForm/SubmissionResultFormHelper 事实;CF/AtCoder/HackerRank/洛谷按各自旧 Form 真实字段;SPI 是否加接口由实现按 spec 原则定并写明)
- [ ] GET /api/submission/:id/detail 端点(本地 remoteSubmissionId → 平台拉详情;不落库)
- [ ] submissions 页行点击展开详情面板(verdict/耗时/内存/错误用例对比渲染)
- [ ] [unit] 各平台详情解析 mock 夹具 + 端点单测;[integration] LC 错误用例三件套渲染冒烟

## 实现列表状态列与筛选(B)
依赖:先完成「实现提交结果详情(A)」
- [ ] /api/problems 与 /api/search 响应加 status('AC'|'TRIED'|'UNSOLVED',本地 submission 聚合,多 challenge 取最高)+ paidOnly(平台数据有则带)
- [ ] problems/search 页状态图标列(🔒/✓/已尝试)+ 状态下拉筛选(本地过滤)
- [ ] [unit] 聚合单测(AC/Tried/未做/多 challenge);前端图标与筛选测试

## 实现账号菜单(C)
- [ ] GET /api/auth/status(各平台同步快照 isLoggedIn+缓存 userId,不打平台)
- [ ] problems 页头部账号区:平台徽标(登录态+用户名);登录走既有 auth:login 桥;登出新桥 auth:logout → AuthCapable.logout
- [ ] [unit] logout 桥命令单测(照 AuthLoginBridge 模式);前端徽标/登录/登出/刷新测试

## 实现模板设置与自定义 CSS(D+G)
- [ ] Settings 加 Templates 区:每 WorkspaceLanguage 模板编辑器(默认=内置,恢复默认按钮);持久化进既有 state;创建链路(含一键开刷)读设置覆盖内置模板(接 ChallengeWorkspaceService.template 参数)
- [ ] Settings 加自定义 CSS 文本域;题面页注入(端点 /api/custom.css 或 evaluate,选简者并写明);亮暗主题均生效
- [ ] [unit] 设置读写/创建链路读取设置/CSS 透传测试

## 实现提交代码回看(F)
依赖:先完成「实现提交结果详情(A)」(详情面板区共用)
- [ ] submissions 页行操作「View code」:展开只读代码视图(hljs 高亮,数据=本地 codeSnapshot 零网络);与详情面板同区切换
- [ ] [unit] 前端展开/高亮/切换测试

## 实现题解排序与 webview 缩放(H+I)
- [ ] /solutions 端点加 orderBy 参数(选项按旧 ArticleOrderByAction 事实);前端题解区块排序下拉
- [ ] 题面/阅读器缩放:桥命令 webview:zoom {level 或 delta} → cefBrowser.setZoomLevel;前端工具栏 +/−/100% 三键
- [ ] [unit] orderBy 透传解析/缩放值计算测试

## 实现 LeetCode 公司题单(J)
- [ ] LC/CN 平台包加公司列表与公司题单查询(GraphQL 按旧 LeetCodeCompanyQueryPresenter/companyTags 事实)
- [ ] problems 页 LC 系平台出现公司下拉;选中后列表走公司题单查询(状态列/难度筛选尽力兼容,不兼容处如实记录)
- [ ] [unit] 公司列表/公司题单 mock 夹具

## M7 集成验收
依赖:先完成全部七项
- [ ] gradlew clean check test buildPlugin + npm test/build 全绿;产物 zip 完整
- [ ] 沙箱实测:B 状态列(注入提交数据)/C 账号徽标登出/A 详情(mock 或公开数据)/D 模板改后新 challenge 生效/G CSS 注入/I 缩放
- [ ] docs/m7-manual-checklist.md(真实账号:A 详情/C 登录登出/J 公司题)
- [ ] 九项与原版行为对照偏差记录(spec 九项逐项);E 不做的理由复核

## 成功标准
- 九项全部落地且各有自动化测试;E 明确不做并记录
- 刷题闭环对齐原版:列表知状态→开刷→提交→看错在哪→改码再提交;账号可管理、模板可定制
- check/test/buildPlugin + CI + Vitest 全绿;旧工程零残留状态不变
