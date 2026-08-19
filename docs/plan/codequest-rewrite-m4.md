# CodeQuest Kotlin 重写 M4:四平台铺开(SPI 量产验证)

按 M3 已验证的 SPI 模式接入 CodeForces/AtCoder/洛谷/HackerRank 四平台,补齐 ContestCapable 能力与反爬公共组件。设计依据:spec §2(能力矩阵)/§5(迁移要点)/§7(M4)。每平台「逻辑参考旧 Scala 实现,代码重写」。旧实现只读:src/main/scala/.../codeforces|atcoder|luogu|hackerrank/。

## 补齐 M4 前置:SPI 增量与公共组件
- [ ] spi:ContestCapable{contests(): Flow<ContestSummary>; contestDetail(remoteId): ContestDetail}(DTO 在 spi 定义)+ contest_reminder 用例服务(落库+findDue 调度+通知,24h 提醒)
- [ ] 消灭 when 分发:authPrompt EP 化——新增 com.codequest.ojAuthPromptProvider EP,SubmissionPlatforms 改经 EP 解析平台登录 UI,删除按平台 id 的 when(WP22 技术债)
- [ ] infra/anti-bot 公共组件:Turnstile JCEF 弹窗(sitekey/选择器/结果表达式可配,参考旧 CloudflareTurnstileDialog)+ AntiBotChallengeException 处理通道(平台抛→UI 层弹窗→token 回填→重试)经 OJRuntime/钩子注入,平台零 UI 依赖
- [ ] /api/contests 端点 + contest 前端页真实化(列表/详情/参加按钮/提醒开关)
- [ ] [unit] EP 解析/提醒调度(findDue/通知去重)/Turnstile 配置单测

## 实现 CodeForces 适配器(含竞赛)
依赖:先完成「补齐 M4 前置:SPI 增量与公共组件」
- [ ] platform/codeforces:CodeForcesApi(REST 公开接口:problemset.problems/contest.list/contest.standings)、CodeForcesAuth(表单账号密码登录或 cookie 粘贴,参考旧实现选型)、Problem/Submit/Contest 服务(实现 ContestCapable:列表/详情/参加/排名——排名 API 限制如实处理)
- [ ] 竞赛提醒:contest_reminder 落库 + 24h 调度通知经前置用例服务
- [ ] OJPlatformProvider 注册(plugin.xml);语言 id 映射
- [ ] [unit] MockEngine 夹具:REST 解析/登录流程/提交轮询(经 BaseSubmissionFlow)/contest 解析

## 实现 AtCoder 适配器(Turnstile)
依赖:先完成「补齐 M4 前置:SPI 增量与公共组件」
- [ ] platform/atcoder:AtCoderApi(题目列表/详情爬取解析、提交 POST 带 CSRF)、AtCoderAuth(登录态校验)、提交经 infra/anti-bot Turnstile 通道(配置:sitekey/选择器,参考旧实现常量)
- [ ] 语言 ID 映射表(旧 AtCoderSettingsConfigurable.ATCODER_LANGUAGES 事实)
- [ ] OJPlatformProvider 注册;[unit] Mock 夹具:HTML 解析/CSRF+Turnstile 通道/轮询

## 实现洛谷适配器(题解列表)
依赖:先完成「补齐 M4 前置:SPI 增量与公共组件」
- [ ] platform/luogu:LuoguApi(_deprecated/md 接口:题目列表/详情/题解列表,参考旧 LuoGuApi)、Auth(登录校验)、Problem/Submit/SolutionView 实现
- [ ] 题面 MD 渲染走既有前端管线(marked+KaTeX 已就绪);题解列表真实数据(正文渲染留 M5)
- [ ] OJPlatformProvider 注册;[unit] Mock 夹具:MD 接口解析/提交/题解列表

## 实现 HackerRank 适配器
依赖:先完成「补齐 M4 前置:SPI 增量与公共组件」
- [ ] platform/hackerrank:HackerRankApi(REST/GraphQL:题目/提交)、Auth(登录校验,参考旧实现)、Problem/Submit 实现
- [ ] OJPlatformProvider 注册;[unit] Mock 夹具:接口解析/轮询

## M4 集成验收
依赖:先完成四个平台适配器
- [ ] ./gradlew check test buildPlugin + frontend npm test/build 全绿;产物 zip 含 6 平台 provider
- [ ] /api/platforms 返回全部 6 平台;沙箱实测各平台公开数据可拉(力扣CN/CF 公开列表/洛谷公开题面/AtCoder 题目页;登录+提交留手工 checklist)
- [ ] docs/m4-manual-checklist.md:四平台「登录→查题→题面→提交→回看」手工步骤
- [ ] SPI 量产结论追加 docs/spi-validation.md:四平台各自的文件数/行数/是否触碰 core-ui(预期:零触碰,when 分发删除后)
- [ ] 竞赛提醒沙箱验证(时间模拟)

## 成功标准
- 全部工作单元 checklist 通过
- 6 平台全部经统一 SPI 注册;新平台零 core/ui 改动(when 分发已消灭)
- CodeForces 竞赛列表/详情/参加/提醒可用;AtCoder 提交走 Turnstile 公共组件;洛谷题解列表可见
- check/test/buildPlugin + CI + Vitest 全绿;旧 Scala 工程零改动
