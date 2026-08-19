# CodeQuest UI 与功能优化 — WP 清单

- 来源计划: `docs/plan/codequest-ui-optimization.md`
- loopId: `loop-20260803-codequest-ui`
- 调度序(依赖+优先级): WP1→WP2→WP3→WP4→WP5→WP6→WP7→WP8

## WP1: 前端性能与依赖瘦身 [pending]
highlight.js 全量→按需语言 + 删 mathjax 死依赖。验收: leetCodeSolutionArticle-*.js bundle < 150KB(当前 1004KB)

## WP2: challengeDescription 去 jQuery [pending]
移除 jquery-1.9.1 改原生 DOM。验收: 页面渲染/交互行为一致

## WP3: Markdown 渲染管线统一 [pending]
建议先 WP1。统一前后端渲染引擎。验收: 各 OJ 描述渲染一致

## WP4: 刷题统计可视化 [pending]
统计 toolwindow + 数据层 + 前端图表/热力图。验收: 数据与 submission 表一致

## WP5: OJ 公共基类抽象 [pending]
BaseOjService/BaseSubmissionService。验收: 各 OJ 行为不变、重复减少

## WP6: 竞赛功能 [pending]
LeetCode/CodeForces 竞赛列表/详情/提醒/排名。验收: 至少一个 OJ 竞赛可用

## WP7: 跨题检索与标签 [pending]
全文检索 + 标签筛选。验收: 可按关键词/标签检索定位

## WP8: 更多 OJ 支持 [pending] [blocked by WP5]
牛客 NowCoder 等。验收: 新 OJ 可查询/提交

---

# CodeQuest Kotlin 重写 M1+M2 — WP 清单(loop-20260814-codequest-rewrite-m1m2)

- 来源计划: `docs/plan/codequest-rewrite-m1-m2.md`
- 调度序(依赖+优先级): WP9→{WP10,WP11,WP12,WP13}→WP14→WP15→WP16

## WP9: 搭建 Gradle/Kotlin 插件工程骨架 [x] (done @ iter 1, commits 7719e37+072024f)
- [x] 新建 settings.gradle.kts + build.gradle.kts,intellij-platform-gradle-plugin 2.x、Kotlin 2.x、JVM 21
- [x] 配置 IntelliJ Platform 2025.2 (252) 依赖,since-build=252,无多版本兼容分支
- [x] 新建 Kotlin 包结构:com.codequest.{core,spi,platform,infra,ui}(kotlin/src/main/kotlin,各包占位 .kt 说明职责)
- [x] 新建最小 plugin.xml:plugin id com.codequest、名称 CodeQuest、vendor、description
- [x] Gradle wrapper 就位(9.2.1,腾讯镜像),.gitignore 补充 build/ 与 .gradle/
- [x] [acceptance] ./gradlew buildPlugin 产出 codequest-0.1.0-SNAPSHOT.zip,zip 内 plugin.xml 与 since-build=252 实测正确

## WP10: 配置代码质量门禁与 CI [x] (done @ iter 2, commit 7f31191)
- [x] 配置 detekt(CLI 1.23.8)+ ktlint(CLI 1.8.0),纳入 check 任务
- [x] checkLayerDependencies 任务:分层方向禁令 + platform OJ 子包互引禁令(动态识别)
- [x] ci-main.yml 新增 gradle job(check test buildPlugin),既有 sbt job 零改动
- [x] [acceptance] 正反实测:注入 core→ui 与 leetcode→codeforces 违规 import 时 BUILD FAILED,移除后全绿

## WP11: 实现 JCEF 工具窗口壳与 CQBridge 协议骨架 [x] (done @ iter 3, commits 351b627+e9a4f44)
- [ ] Kotlin UI DSL 左侧 CodeQuest 主工具窗口壳(可切换面板容器,无 .form)
- [ ] ResourceHttpServer:本地静态资源 + REST 数据端点骨架(/api/health)
- [ ] CQBridge:Kotlin 侧 postMessage(topic, jsonPayload)/on(topic, handler),消息仅承载命令/事件
- [ ] 编辑器侧题面/提交结果两个工具窗口占位壳
- [ ] [unit] 桥协议单测:topic 注册/分发/未知 topic 不抛异常
- [ ] [acceptance] IDE 沙箱打开工具窗口,/api/health 拉到数据且桥 echo 往返可用

## WP12: 搭建 webview 统一前端脚手架 [x] (done @ iter 4, commit 8caa636)
- [ ] webview/ 单一 Vite + Vue3 + TS 应用,/problems /contest /stats /search /problem-view /submissions 独立 entry
- [ ] 主题跟随:IDE 注入 JB 颜色 CSS 变量,亮/暗模式均可用
- [ ] highlight.js(按需语言)+ KaTeX(本地资源)
- [ ] JS 侧 CQBridge 客户端(postMessage/on 对称 API)
- [ ] 各页面空壳 + 导航占位,构建产物输出到插件资源目录
- [ ] [unit] Vitest:桥客户端 mock 测试 + 一条页面渲染冒烟

## WP13: 实现 core 领域模型与仓库接口 [x] (done @ iter 5, commit e28f5af)
- [ ] 领域模型:Problem/ProblemDetail/Challenge/ChallengeLanguage/Solution/TestCase/Submission/Contest/SubmissionResult(value class id、kotlinx.serialization)
- [ ] 平台无关仓库接口:Problem/Submission/Challenge/Solution/TestCase/Tag/ContestReminder Repository(接口在 core,实现归 infra)
- [ ] [unit] 领域模型单测(序列化往返、SubmissionResult 终态判定)

## WP14: 实现 Exposed 数据层(schema + DAO + FTS5) [x] (done @ iter 6, commit 8aaf4cd)
- [ ] Exposed + JDBC SQLite,建全量表 + schema_version
- [ ] problem_content 与 solution.note_md 建 FTS5 虚拟表,LIKE 降级路径
- [ ] core 仓库接口的 Exposed DAO,单写协程(Mutex),WAL 模式
- [ ] 数据库文件位置遵循"数据库文件夹"设置
- [ ] [integration] 临时 SQLite 库集成测试:建表/CRUD/FTS5 中文检索/并发读写不损坏
- [ ] [unit] DAO 单测(内存假实现对照)

## WP15: 实现 settings 与数据库文件夹管理 [x] (done @ iter 7, commit 6ddb978)
- [x] 数据库文件夹路径设置(Kotlin UI DSL 面板,含空路径校验)
- [x] 设置变更经消息总线通知数据层重建连接
- [x] i18n:properties + Kotlin 类型化包装(中英双语)
- [x] [acceptance] 修改数据库文件夹后重启,数据在新位置生效(自动化:ManagedDatabaseTest 重启模拟;GUI runIde 手工步骤见 WP15 报告)

## WP16: 实现 challenge 工作区(编辑器开题/多解法/测试用例) [x] (done @ iter 8, commits 0e07623+17f77ec+43790ef+d56eda2+0a66372)
- 附带(自 WP11/WP12 顺延): JCEF 壳挂载前端 URL(主窗口四标签+problem-view/submissions 指向 http://127.0.0.1:<port>/<page>/),并实机验证 bridge:echo 往返
- [ ] 从题目创建 challenge 工作区:按语言模板生成入口文件(模板上下文沿用 spec 2026-08-03 变量约定)
- [ ] 多解法管理:多 solution,编辑/切换/比较入口
- [ ] 测试用例面板:用例增删改、本地运行(参考旧 TestCases 实现重写)
- [ ] [unit] 模板渲染与用例解析单测
- [ ] [acceptance] 手工 checklist:创建 challenge → 多解法 → 测试用例 → 本地运行 → 落库可回看

---

# CodeQuest Kotlin 重写 M3 — WP 清单(loop-20260815-codequest-rewrite-m3)

- 来源计划: `docs/plan/codequest-rewrite-m3.md`
- 调度序(依赖+优先级): WP17→WP18→WP19→{WP20,WP21}→WP22

## WP17: 定义 OJ 能力型 SPI 与注册机制 [x] (done @ iter 1, commit 1e63eba)
- [ ] spi 包:OJPlatform(id/domain/info) + AuthCapable/ProblemQueryCapable/SubmissionCapable/SolutionViewCapable(suspend+Flow)
- [ ] OJPlatformRegistry:EP 注册 + find(id)/allWith<T>();plugin.xml 声明 extensionPoint
- [ ] BaseSubmissionFlow 提交轮询状态机(指数退避+超时),平台只实现 fetchRemoteStatus()
- [ ] 领域异常体系:OJException/AuthExpiredException/AntiBotChallengeException/RateLimitedException
- [ ] [unit] 状态机单测(节奏/终态/超时/异常传播)+ Registry 单测

## WP18: 实现 HTTP 会话层与凭据管理 [x] (done @ iter 2, commit 87d62ce)
- [ ] infra/http:ktor-client(OkHttp)+ 按平台隔离 SessionStore(独立 CookieJar)
- [ ] infra/secrets:凭据/cookie 加密持久化(旧 SensitiveDataStore 语义)
- [ ] HTTP 错误→领域异常映射(401/403→AuthExpired、429→RateLimited、反爬→AntiBot)
- [ ] [unit] MockEngine 单测:cookie 隔离/异常映射/请求头

## WP19: 实现 LeetCode/力扣CN 平台适配器 [x] (done @ iter 3, commit 30b0e5d)
- [ ] platform/leetcode:LeetCodeApi(GraphQL 双域名)/LeetCodeAuth(cookie 粘贴+校验)/LeetCodeProblemService/LeetCodeSubmissionService(消费 BaseSubmissionFlow)
- [ ] 语言 id 映射表;CN 端提交区域标记约定(spec 2026-08-03)
- [ ] OJPlatformProvider 注册 plugin.xml(leetcode/leetcodecn)
- [ ] [unit] MockEngine 回放夹具:GraphQL 构造/解析/轮询状态转换

## WP20: 打通题面渲染与题目列表真实数据 [x] (done @ iter 4, commit dad53dd)
- [ ] /api/problems 与 /api/problem/:id 端点(SPI 查询+本地库缓存)
- [ ] problems 页真实列表(筛选/搜索/分页)+ nav:openProblem
- [ ] problem-view 页真实题面渲染(HTML/MD+KaTeX+hljs,LC HTML 清洗)
- [ ] 题面落 problem_content
- [ ] [unit] 前端渲染冒烟 + 端点单测

## WP21: 实现提交流态与编辑器提交动作 [x] (done @ iter 5, commit 7677ba8)
- [ ] 编辑器提交 action:提取区域标记代码→SPI submit→轮询→落库(含快照)
- [ ] AuthExpired→重登流程(cookie 粘贴对话框)后自动重试一次
- [ ] submissions 页/面板真实数据(端点+提交完成事件刷新)
- [ ] 提交结果通知(i18n)
- [ ] [unit] 代码提取单测;提交流程集成测试(mock)

## WP22: M3 集成验收 [x] (done @ iter 6, commits 304f589+f56b7dd)
- [ ] gradlew check test buildPlugin + npm test/build + CI 全绿
- [ ] [acceptance] 沙箱实测:登录→查题→题面→challenge→提交→轮询→落库回看(自动化+手工步骤)
- [ ] SPI 验证结论:新增 OJ 需写哪些文件/不碰哪些层(实证记录)
- [ ] 遗留修复:WP15 settings 面板 html 标签 UiDslException

---

# CodeQuest Kotlin 重写 M4 — WP 清单(loop-20260816-codequest-rewrite-m4)

- 来源计划: `docs/plan/codequest-rewrite-m4.md`
- 调度序(依赖+优先级): WP23→{WP24,WP25,WP26,WP27}→WP28

## WP23: 补齐 M4 前置:SPI 增量与公共组件 [x] (done @ iter 1, commit 71e475d)
- [ ] spi:ContestCapable + DTO + contest_reminder 用例服务(落库+findDue 调度+24h 通知)
- [ ] 消灭 when 分发:ojAuthPromptProvider EP 化,SubmissionPlatforms 改造(WP22 技术债)
- [ ] infra/anti-bot Turnstile JCEF 弹窗 + AntiBotChallenge 处理通道(平台零 UI 依赖)
- [ ] /api/contests 端点 + contest 前端页真实化(列表/详情/参加/提醒开关)
- [ ] [unit] EP 解析/提醒调度(findDue/通知去重)/Turnstile 配置单测

## WP24: 实现 CodeForces 适配器(含竞赛) [x] (done @ iter 2, commit 8e4da9d)
- 遗留缺口:表单 POST 不在 OJTransport → ✅已由 WP25 清偿(postForm 提升+CF 完整迁移,FormPostCapable 删除)
- [ ] platform/codeforces:Api(REST problemset.problems/contest.list/contest.standings)/Auth(参考旧实现选型)/Problem/Submit/Contest 服务(ContestCapable:列表/详情/参加/排名如实处理)
- [ ] 竞赛提醒落库+24h 调度通知(消费 WP23 用例服务)
- [ ] OJPlatformProvider 注册;语言 id 映射
- [ ] [unit] MockEngine 夹具:REST 解析/登录/提交轮询/contest 解析

## WP25: 实现 AtCoder 适配器(Turnstile) [x] (done @ iter 3, commits 83cb1bb+079bbea)
- 首项授权:postForm 提升进 OJTransport,CF 迁移完成、FormPostCapable 删除(commit 83cb1bb)
- [x] platform/atcoder:Api(kenkoooo 题库/难度 JSON+题面/提交状态 HTML/提交表单 POST 带 CSRF)/Auth(cookie 粘贴+登录态校验)/提交经 anti-bot Turnstile 通道(旧 sitekey/选择器常量)
- [x] 语言 ID 映射表(旧 ATCODER_LANGUAGES 51 条逐字;Python 族 CPython 优先作工作区默认)
- [x] OJPlatformProvider+authPrompt EP 注册;[unit] Mock 夹具:HTML 解析/CSRF+Turnstile 通道/轮询(47 测试)

## WP26: 实现洛谷适配器(题解列表) [x] (done @ iter 4, commit d55eefc)
- [ ] platform/luogu:Api(_deprecated/md 接口)/Auth/Problem/Submit/SolutionView 实现
- [ ] 题面 MD 渲染走既有前端管线;题解列表真实数据(正文 M5)
- [ ] OJPlatformProvider 注册;[unit] Mock 夹具:MD 接口解析/提交/题解列表

## WP27: 实现 HackerRank 适配器 [x] (done @ iter 5, commit 056b402)
- [ ] platform/hackerrank:Api(REST/GraphQL)/Auth/Problem/Submit 实现
- [ ] OJPlatformProvider 注册;[unit] Mock 夹具:接口解析/轮询

## WP28: M4 集成验收 [x] (done @ iter 6, commits 8577665+2e45628)
- [ ] gradlew check test buildPlugin + npm test/build 全绿;产物含 6 平台 provider
- [ ] /api/platforms 返回 6 平台;沙箱实测各平台公开数据(CF 列表/洛谷题面/AtCoder 题目页)
- [ ] docs/m4-manual-checklist.md 四平台手工步骤
- [ ] SPI 量产结论追加 docs/spi-validation.md(四平台文件数/零 core-ui 触碰实证)
- [ ] 竞赛提醒沙箱验证(时间模拟)

---

# CodeQuest Kotlin 重写 M5 — WP 清单(loop-20260818-codequest-rewrite-m5)

- 来源计划: `docs/plan/codequest-rewrite-m5.md`
- 调度序(依赖+优先级): WP29→WP30→WP31→WP32

## WP29: 实现刷题统计可视化 [x] (done @ iter 1, commit c8d419b)
- [ ] core:StatisticsService 聚合(总数/AC/AC率/streak,submissionRepository 数据源)
- [ ] /api/stats 端点(平台过滤+时间范围:汇总+热力图数据+趋势)
- [ ] stats 前端页真实化(汇总卡片+GitHub 风格热力图纯 CSS/SVG+趋势条形)
- [ ] [unit] 聚合单测(空库/单日多条/平台过滤/streak 断续)+端点单测+前端冒烟

## WP30: 实现跨题全文检索与标签筛选 [x] (done @ iter 2, commit 89bae42)
- [ ] /api/search:FTS5(题面+题解笔记+标题,复用 FtsSupport/SolutionNoteSearch)+标签+平台过滤,命中来源标记
- [ ] search 页真实化(搜索框+标签多选+结果列表→nav:openProblem)
- [ ] [integration] 中文关键词/标签组合/空结果/降级路径
- [ ] [unit] 前端搜索页渲染测试

## WP31: 实现题解文章正文渲染 [x] (done @ iter 3, commit 851c041)
- [ ] SPI 增量:fetchSolutionContent(ref): SolutionArticleContent;洛谷+LeetCode/CN 实现(旧实现端点事实)
- [ ] /api/problem/:remoteId/solutions/:sid 端点(不落库,YAGNI 取舍说明)
- [ ] 前端题解正文页(MD/HTML 管线复用)+导航工具栏(后退/前进/刷新)+列表→正文跳转
- [ ] [unit] 正文解析单测(洛谷 MD/LC HTML 夹具)+前端冒烟

## WP32: M5 集成验收 [x] (done @ iter 4, commit 9e339ea)
- [ ] gradlew clean check test buildPlugin --rerun-tasks + npm test/build 全绿;产物完整
- [ ] 沙箱实测:stats 真实渲染(测试数据注入)/search 命中/题解正文渲染
- [ ] docs/m5-manual-checklist.md(统计与 submission 表对照/检索/题解手工步骤)
- [ ] M5 与 spec 偏差记录

---

# CodeQuest Kotlin 重写 M6 — WP 清单(loop-20260819-codequest-rewrite-m6)

- 来源计划: `docs/plan/codequest-rewrite-m6.md`
- 调度序(依赖+优先级): WP33→WP34→WP35
- ⚠️ 用户已批准:旧工程连同工作区未提交改动一并清理

## WP33: 删除旧 Scala/sbt 工程 [pending]
- [ ] 删除旧源码与构建(src/{main,test} 的 Scala 部分、build.sbt、project/、jooq-codegen.xml、gen/、testResources/、兼容层残留、垃圾文件;逐项甄别白名单制)
- [ ] 删除旧前端 webview/(node_modules 直接 rm)
- [ ] 保留白名单:kotlin/、frontend/、docs/、database/、.github/、CLAUDE.md、LICENSE、gradle 全套、scripts/(甄别)
- [ ] 验证:gradlew clean check test buildPlugin --rerun-tasks 全绿;产物完整
- [ ] [acceptance] git status 除 M6 提交外干净;全仓 grep 无 com.wenjunhuang 残留

## WP34: CI 收敛与文档更新 [pending] [blocked by WP33]
- [ ] ci-main.yml 移除 sbt job 仅留 gradle;其他 workflow 旧引用统一收敛
- [ ] README.md/README_en.md/DESCRIPTION.md 重写(Kotlin 版:功能矩阵/架构/SPI 指南/安装/开发;Apache 2.0 与原项目声明保留)
- [ ] CHANGELOG.md 1.0.0 条目(Keep a Changelog 格式)
- [ ] docs/release-checklist.md 更新新构建流程;spec frontmatter status plan→done
- [ ] 验证:YAML 校验/文档链接自查/门禁绿

## WP35: 定版发布与最终验收 [pending] [blocked by WP34]
- [ ] 版本定版 1.0.0(去 -SNAPSHOT;plugin.xml change-notes)
- [ ] 构建 codequest-1.0.0.zip 并验证内容
- [ ] 最终验收:沙箱 runIde 冒烟(0 ERROR/6 平台/页面可开)+ 全量门禁终态绿
- [ ] docs/rewrite-final-report.md:M1-M6 总览/spec 成功标准 4 条终版对照/遗留边界汇总/新旧工程对比
- [ ] git log 抽查(Conventional Commits/无 Co-Authored-By);提交不 push
