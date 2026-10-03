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

## WP33: 删除旧 Scala/sbt 工程 [x] (done @ iter 1, commits a8f69a0+1ce74b0+529252c+8873df1;721 files,-57534 行,白名单甄别+快照留证;偏差:保留路径 M 改动含合规声明与 262 决策,提交保留而非丢弃)
- [ ] 删除旧源码与构建(src/{main,test} 的 Scala 部分、build.sbt、project/、jooq-codegen.xml、gen/、testResources/、兼容层残留、垃圾文件;逐项甄别白名单制)
- [ ] 删除旧前端 webview/(node_modules 直接 rm)
- [ ] 保留白名单:kotlin/、frontend/、docs/、database/、.github/、CLAUDE.md、LICENSE、gradle 全套、scripts/(甄别)
- [ ] 验证:gradlew clean check test buildPlugin --rerun-tasks 全绿;产物完整
- [ ] [acceptance] git status 除 M6 提交外干净;全仓 grep 无 com.wenjunhuang 残留

## WP34: CI 收敛与文档更新 [x] (done @ iter 2, commits 939d5bd+936a18b;关键修复:CI gradle job 补前端 npm build 否则首跑必红)
- [ ] ci-main.yml 移除 sbt job 仅留 gradle;其他 workflow 旧引用统一收敛
- [ ] README.md/README_en.md/DESCRIPTION.md 重写(Kotlin 版:功能矩阵/架构/SPI 指南/安装/开发;Apache 2.0 与原项目声明保留)
- [ ] CHANGELOG.md 1.0.0 条目(Keep a Changelog 格式)
- [ ] docs/release-checklist.md 更新新构建流程;spec frontmatter status plan→done
- [ ] 验证:YAML 校验/文档链接自查/门禁绿

## WP35: 定版发布与最终验收 [x] (done @ iter 3, commits 1595db2+de334bd;codequest-1.0.0.zip 26.1MB;冒烟 2 新发现记入待办:/api/health 版本硬编码、HEAD 请求 SEVERE)
- [ ] 版本定版 1.0.0(去 -SNAPSHOT;plugin.xml change-notes)
- [ ] 构建 codequest-1.0.0.zip 并验证内容
- [ ] 最终验收:沙箱 runIde 冒烟(0 ERROR/6 平台/页面可开)+ 全量门禁终态绿
- [ ] docs/rewrite-final-report.md:M1-M6 总览/spec 成功标准 4 条终版对照/遗留边界汇总/新旧工程对比
- [ ] git log 抽查(Conventional Commits/无 Co-Authored-By);提交不 push

---

# CodeQuest M7 — WP 清单(loop-20260819-codequest-rewrite-m7)

- 来源计划: `docs/plan/codequest-rewrite-m7.md`(spec: 2026-08-19-legacy-parity)
- 调度序: WP36→WP37→WP38→WP39→WP40→WP41→WP42→WP43

## WP36: 实现提交结果详情(A) [x] (done @ iter 1, commit 6ecbd62;SPI 取舍:SubmissionDetailCapable 签名带 problemRemoteId,无详情返 null 不抛)
- [ ] 平台包提交详情拉取(LC/CN 错误用例三件套必含;各平台按旧 Form 事实;SPI 取舍按 spec 原则写明)
- [ ] GET /api/submission/:id/detail 端点(不落库)
- [ ] submissions 页详情面板(verdict/耗时/内存/错误用例对比)
- [ ] [unit] mock 夹具+端点单测;[integration] LC 三件套渲染冒烟

## WP37: 实现列表状态列与筛选(B) [x] (done @ iter 2, commit 3d08710;ProblemStatusStore 端口先例,AC 粘性/多 challenge 取最高;paidOnly 恒 false 偏差候选)
- [ ] /api/problems 与 /api/search 加 status+paidOnly(本地聚合)
- [ ] problems/search 页状态图标列+筛选(本地过滤)
- [ ] [unit] 聚合单测+前端测试

## WP38: 实现账号菜单(C) [x] (done @ iter 3, commit c5d8619;authPlatformSourceProvider EP 反转注入;userId 恒 null 偏差候选)
- [ ] GET /api/auth/status(同步快照)
- [ ] problems 页头部账号徽标;auth:logout 新桥
- [ ] [unit] logout 桥单测+前端测试

## WP39: 实现模板设置与自定义 CSS(D+G) [x] (done @ iter 4, commit 29bf359;TemplateDraftsState 草稿核;/api/custom.css 下次加载生效)
- [ ] Settings Templates 区(每语言编辑器+恢复默认);创建链路接线
- [ ] 自定义 CSS 文本域+题面注入
- [ ] [unit] 设置读写/链路读取/CSS 透传测试

## WP40: 实现提交代码回看(F) [x] (done @ iter 5, commit 7134061;纯前端零 Kotlin)
- [ ] submissions 页 View code(本地快照+hljs);与详情面板同区切换
- [ ] [unit] 前端测试

## WP41: 实现题解排序与 webview 缩放(H+I) [x] (done @ iter 6, commit 8a12ca0;SPI orderBy 默认参数最小增量;zoom 对数刻度 ±1 级 ×1.2 钳制 [−7,+8];中断恢复一次)
- [ ] /solutions orderBy 参数+前端排序下拉
- [ ] webview:zoom 桥+工具栏三键
- [ ] [unit] 透传/缩放值测试

## WP42: 实现 LeetCode 公司题单(J) [x] (done @ iter 7, commit a175e06;SPI=CompanyQueryCapable 独立能力接口;公司题单 favoriteQuestionList/公司列表 questionCompanyTagsV2;paidOnly 改真值透传(WP37 偏差候选清偿);不兼容如实记录:公司模式 keyword 忽略(UI 提示)/UI 单选(端点支持多公司)/position·tag 筛选未接入)
- [ ] LC/CN 公司列表与公司题单查询(旧实现事实)
- [ ] problems 页 LC 系公司下拉;不兼容处如实记录
- [ ] [unit] mock 夹具

## WP43: M7 集成验收 [x] (done @ loop-20260819 iter 8, commit 2017c23;定版 1.1.0 @ 38dbe76;PROGRESS 有据,此处补勾)
- [x] 全量门禁+产物;沙箱实测(B/C/A/D/G/I)
- [x] docs/m7-manual-checklist.md;九项偏差记录+E 理由复核

---

# UI 重构：面板精简·平台登录门控·模版风格化·中文化 — WP 清单(loop-20260821-ui-revamp)

- 来源计划: `docs/plan/ui-revamp.md`(规格: docs/superpowers/specs/2026-08-21-ui-revamp-design.md)
- 调度序(依赖+优先级): WP44→{WP45,WP48}→{WP46,WP47,WP49,WP50}+WP51(独立)→WP52

## WP44: SPI 与平台层：模版风格定义与登录会话快照 [x] (done @ iter 1, commit eb7002f;TemplateStyle+storageKey;PlatformInfo.templateStyle 必填(16 处测试 fake 同步补);AuthCapable 两方法默认 null 零破坏;五 auth restoreSession=currentSession 复用+凭据注入校验,ensureLoggedIn 头部改调 restoreSession 语义不变;entryTemplate 双风格(LEETCODE 字节不变,ACM 五语言 stdin 骨架),旧单参委托 LEETCODE;+25 测,共 669;check 全绿)
- [x] core.workspace 新增 TemplateStyle 枚举(ACM=stdin/stdout 竞赛版 / LEETCODE=Solution 函数版)
- [x] spi.PlatformInfo 增加 templateStyle 字段;六平台声明:力扣中国/国际→LEETCODE,CodeForces/AtCoder/洛谷/HackerRank→ACM
- [x] spi.AuthCapable 新增 currentSession(): LoginSession?(同步只读快照)与 suspend restoreSession(): LoginSession?(静默启动恢复:只尝试已存凭据注入+校验,绝不弹窗,失败返回 null)
- [x] 六平台 auth 实现(LeetCodeAuth[CN/COM 共用]、CodeForcesAuth、AtCoderAuth、LuoguAuth、HackerRankAuth)补两方法:restoreSession 复用各实现的启动恢复段,currentSession 返回 session?.takeIf { verified }
- [x] ChallengeTemplates.entryTemplate(style, languageId):ACM Java=BufferedReader stdin 版、ACM Kotlin=readLine 版、LEETCODE=现状 Solution 版;Python/C++/Go 按风格给对应默认;regionMarkers/defaultCode/fileNameRule 与提交区域标记两行文案不动;旧单参 entryTemplate(languageId) 保留并委托 LEETCODE 变体(既有调用方零破坏)
- [x] [unit] TemplateStyle 六平台映射测试
- [x] [unit] entryTemplate(style, lang) 各风格快照测试(含两风格默认模板必须含 beginRegion/endRegion)
- [x] [unit] 各平台 restoreSession 静默性测试(无凭据返回 null 不触发 prompt;有效凭据校验成功后 session/verified 就位)

## WP45: 设置层：风格×语言模版存储与迁移 [x] (done @ iter 2, commit 8ec9636;normalizeTemplateKeys 旧键双写/移除/幂等/空白丢弃,新键优先不覆盖;entryTemplateOverride 双签名+@Deprecated 单参委托 LEETCODE(Wiring/Panel 压制注释 WP47 迁移);TemplateDraftsState 双键三语义(修 stored 基线空白归一 bug);TemplateAvailability;Configurable 编译级适配挂 LEETCODE(UI 改造留 WP46);+15 测共 684,check 全绿;429 中断一次,续做 Teamee 凭落盘零损失恢复)
- [x] CodeQuestSettings.State.templates 键升级为 "style:languageId";loadState 检测旧格式键(无冒号=纯语言键)一次性双写到 acm:L 与 leetcode:L 并归一化为新格式
- [x] entryTemplateOverride(style, languageId):非空白键=启用返回原文;空白/缺失返回 null(运行时无内置兜底)
- [x] TemplateDraftsState 扩展为 (风格,语言) 双键:editorText 预填对应风格内置默认文本;空白=未配置;「恢复默认」=文本回填内置默认(不改启用状态)
- [x] 新增纯函数 TemplateAvailability:usableLanguages(style, persisted) 与 defaultLanguage(可用列表)=Java 优先否则第一个
- [x] [unit] 旧键迁移双写测试(loadState 归一化、幂等)
- [x] [unit] TemplateDraftsState 双键全语义测试(空白/恢复默认/与内置一致=已配置)
- [x] [unit] TemplateAvailability 测试(空列表、Java 优先、风格隔离)

## WP46: 设置页：风格+语言编辑器与模版帮助 [x] (done @ iter 4, commit c07dbc3;风格下拉(ACM 竞赛版/LeetCode 版,面板态 styleComboValue 替 WP45 过渡常量)+switchSelection 切换 flush 纯函数+isModified/apply/reset 双下拉全链路;TemplateHelpDialog 四节(变量/提交区域标记/文件名规则/风格说明)只读 JBTextArea 纯文本避 html 转义;bundle 英中同步(旧 description/restoreDefault 改新语义值);+2 测共 690,check 全绿)
- [x] CodeQuestSettingsConfigurable Templates 区改造:风格下拉(ACM 竞赛版/LeetCode 版)+语言下拉+编辑器+「恢复默认文本」按钮,接线双键 TemplateDraftsState;提示文案(空白=该语言不可用,修改后点「应用」生效)
- [x] 「帮助」按钮 → 帮助对话框(DialogWrapper):①变量表(challenge.* 六变量、velocity.* 工具、beginRegion/endRegion)②提交区域标记说明(两行标记必须保留,标记之间代码才会被提交)③文件名规则 ④风格说明(哪些平台用哪个风格)
- [x] PluginBundle 英中同步新增 settings.templates.style.* 与 help.* keys
- [x] [unit] 设置面板草稿状态接线测试(isModified/apply 往返、风格切换 flush)

## WP47: 创建链路：模版门控与默认 Java [x] (done @ iter 5, commit fd3abb8;templateStyleOf(未知平台兜底 LEETCODE)、Wiring 双参接线+压制清偿、Dialog 语言下拉可用性过滤+默认 Java+空配置拦截(提示+打开设置+OK 禁用+doValidate 双保险)、Bridge 三态自动选语言(Java 优先,替代旧多语言弹菜单——规格行为)、createNewSolution 风格化;+6 测共 696,check 全绿;偏差:core null 最后防线仍 LEETCODE 单参形态(正常链路已被 UI 门控拦截,候选后续风格化))
- [x] ChallengeTemplateWiring.createChallengeWithTemplate 改为按平台风格读 entryTemplateOverride(styleOf(problem.platformId), languageId),非空白必传 template(core 服务签名不动)
- [x] NewChallengeDialog:平台选定后语言下拉=usableLanguages(平台风格);默认选中 Java(Java 无模版时选第一个可用);列表为空显示「请先在设置中配置代码模版」+「打开设置」按钮并禁止创建
- [x] ChallengeCreateBridge(题面一键开刷):Java 优先→第一个可用语言→全无可用回错误并引导去设置
- [x] ChallengeWorkspacePanel.createNewSolution(修复#45 语义):读当前 (styleOf(题目平台), 语言) 覆盖,无则用落库模板
- [x] [unit] ChallengeTemplateWiringTest 适配(风格维度覆盖/未配置路径)
- [x] [unit] 两条创建路径的可用语言与默认语言接线测试

## WP48: 认证端点：用户名透传与静默恢复 [x] (done @ iter 3, commit 5a64b41;AuthApi userId 透传 currentSession、POST /api/auth/refresh 并发 restoreSession(异常吞按未登录)与 status 同构、ResourceHttpServer 挂载、前端 refreshAuthStatus()、AuthLoginBridge 三错误串入 bundle 英中同步(测试 locale 无关断言);+4 测共 688,check 全绿,前端 145 无回归)
- [x] AuthApi:userId 从恒 null 改为透传 currentSession()?.userId(未登录仍 null)
- [x] 新增 POST /api/auth/refresh:经 AuthPlatformSources 并发跑 restoreSession()(网络校验),返回与 /api/auth/status 同构响应
- [x] ResourceHttpServer 挂载新端点
- [x] frontend/src/lib/api.ts 新增 refreshAuthStatus()
- [x] AuthLoginBridge 面向用户展示的错误串中文化(unknown platform/has no login 等入 bundle)
- [x] [unit] AuthApi userId 透传测试
- [x] [unit] refresh 端点逻辑测试(restoreSession 并发调用、响应同构、失败平台如实 false)

## WP49: 前端题库页：平台门控+账号区+本地搜索子视图 [x] (done @ iter 6, commit fc9c80e;账号区显式登录/退出按钮+用户名、filterPlatformsByAuth 门控+全未登录回落 leetcodecn(真实 id,计划书 leetcode-cn 有误已核源码修正)+提示条、status→refresh 静默恢复时序+失败不门控防线、SearchPanel.vue 抽组件+「平台题库｜本地搜索」子视图(/search/ 路由保留)、两页中文化;前端 +11 测共 156,build 通过)
- [x] 账号区重设计:全部六平台常驻;已登录=平台名+用户名+显式「退出」按钮;未登录=「登录」按钮(触发既有 auth:login 流程,替代点击整行切换)
- [x] 平台下拉过滤:fetchPlatforms ⋈ fetchAuthStatus 仅留 loggedIn=true;全未登录回落 leetcode.cn+提示条(「当前无已登录平台,默认展示力扣中国;在上方账号区登录后即可切换其他平台」)
- [x] 加载时序:GET /api/auth/status 瞬时渲染→POST /api/auth/refresh→刷新下拉与账号区;收到 auth:login-result/auth:logout-result 后重拉两接口
- [x] search/App.vue 列表逻辑抽为 SearchPanel.vue 组件;problems/App.vue 顶部加子视图切换「平台题库｜本地搜索」(/search/ 路由保留)
- [x] 题库页+本地搜索子视图中文化(筛选/按钮/空态/提示条)
- [x] [unit] vitest:平台过滤与全未登录回落 leetcode.cn 的纯逻辑测试(抽纯函数或 composable)

## WP50: 前端其余页面：中文化与竞赛门控 [x] (done @ iter 7, commit c838518;contest 门控(复用 platformGate+authKnown 防线,全未登录引导不回落力扣)+阶段中文(未开始/进行中/等待系统测试/系统测试中/已结束)、stats 卡片/热力图/趋势中文、problem-view 全文案、submissions 三件套/双 tab+verdict.ts 中文(与 PluginBundle_zh 对齐,grep 证仅 submissions 消费)、landing+AppShell NAV 中文;前端 +3 测共 159,build 通过)
- [x] contest 页:平台列表过滤已登录+全未登录引导文案;PHASE_LABELS 中文化(未开始/进行中/等待系统测试/系统测试中/已结束)+提醒订阅文案
- [x] stats 页中文化:汇总卡片(总提交/通过数/AC 率/当前连续/最长连续)、热力图/趋势标题、空态
- [x] problem-view 页中文化:一键开刷/登录/题解排序/缩放工具栏标签
- [x] submissions 页中文化:输入/期望输出/实际输出、详情/代码 tab、verdict.ts 判定文案与 Kotlin bundle 中文对齐
- [x] landing 页中文化:入口标签对齐新结构(题库/竞赛/统计/题面/提交记录/本地搜索)

## WP51: Kotlin UI 中文化与面板结构重组 [x] (done @ iter 8, commit 146bad6;PANELS 4→3 bundle 化、挑战面板约 45 条+对话框 bundle 化、stripeTitle 编程式设显示名(2026.2 无 XML displayName 属性,id 不动)、挑战窗双 tab(工作区+提交记录 JCEF 惰性创建+challengeId 联动+桥推送随迁)、ChallengeToolWindowNavigator 净新增导航(原无激活调用,prompt 假设与实际不符)、删 SubmissionsToolWindowFactory 零残留;696 测+check+buildPlugin 三绿,产物 xml 恰 3 toolWindow;首次派发 429 中断零残留,重派一次过;runIde 沙箱留 WP52)
- [x] CodeQuestToolWindowFactory PANELS 4→3(题库/竞赛/统计),标题/描述/JCEF 降级文案走 PluginBundle
- [x] ChallengeWorkspacePanel 全文案 bundle 化(约 25 条:Challenge/刷新/新建挑战/解法/测试用例分组与按钮、对话框文案、运行输出「用例 N：通过/失败」「汇总：x/y 通过」)
- [x] NewChallengeDialog/ImportTestCasesDialog 文案 bundle 化
- [x] plugin.xml 工具窗 displayName 更新:「CodeQuest」「CodeQuest 题面」「CodeQuest 挑战」
- [x] ChallengeWorkspacePanel 外层包 JBTabbedPane:「工作区」tab(现有 Swing 面板)+「提交记录」tab(JCEF 加载 /submissions/?challengeId=当前选中挑战,随选中联动、惰性创建,JCEF 不可用降级中文提示)
- [x] SubmissionCoordinator 提交完成导航:从打开提交记录工具窗改为激活挑战工具窗的提交记录 tab(保留 challengeId 定向过滤)
- [x] 删除 SubmissionsToolWindowFactory 及 plugin.xml 注册
- [x] [acceptance] IDE 工具窗 3 个、主侧栏 tab 3 个;提交后焦点跳挑战窗提交记录 tab 且按 challengeId 过滤

## WP52: 集成验收与收尾 [x] (done @ iter 9, commit 7e66e92;clean check test buildPlugin 全绿 696/27MB zip,前端 159+build 过;八条验收三态:②③⑤⑥⑧自动化/①静态核验/④端点自动化+重启人工/⑦纯人工;docs/ui-revamp-manual-checklist.md B 节 7 组人工走查;CHANGELOG 1.1.1 并入 1.2.0(定版留用户);零产品代码改动)
- [x] ./gradlew test 全绿;frontend npm run test + npm run build(含 vue-tsc)通过
- [x] [acceptance] 规格第 8 节验收清单 8 条逐条手动走查:中文无残留/平台门控与回落/用户名显示与退出/重启静默恢复/模版门控默认 Java/老配置双风格迁移可见/提交导航/本地搜索入口
- [x] CHANGELOG.md 按 SemVer 更新(MINOR,1.2.0 候选)

## WP53: SPI：TestEvaluationCapable 与力扣 interpret 云端测试 [x] (done @ iter 1;接口+三模型/TestRunStatus(Pending|Finished);LeetCodeTestEvaluationService POST interpret_solution(snake_case+CSRF)→轮询 check(state==SUCCESS 终态,复用 BaseSubmissionFlow.PollConfig 退避参数但自持循环——isTerminal/发射类型不匹配故不套用状态机本体);full_*/短文归一;PENDING 宽容/缺 state 快速失败;语言守卫同 submit;LeetCodePlatform 双变体实现;+9 测共 708 全绿,check 过;loop 层独立核验 BUILD SUCCESSFUL)
- [x] spi 新增 `TestEvaluationCapable` 接口：`watchTestRun(request: TestRunRequest): Flow<TestRunStatus>`；模型 `TestRunRequest(problemRemoteId, questionId, languageId, code, dataInput)`、`TestRunStatus`（Pending / Finished(TestRunResult)）、`TestRunResult(correctCount, totalCount, codeAnswers, expectedCodeAnswers, compareResult, statusRuntime, statusMemory, compileError, runtimeError, state)`
- [x] 力扣实现 `LeetCodeTestEvaluationService`：POST `/problems/{slug}/interpret_solution/`（snake_case body + 复用现有 LeetCodeApi csrf 通道）→ interpretId → 轮询 `/submissions/detail/{interpretId}/check/`（轮询节奏对齐 BaseSubmissionFlow 语义：1s 起步指数退避封顶，state != SUCCESS 继续，非终态字段宽容解析）
- [x] check 响应映射：code_answer[]/expected_code_answer[]/compare_result/total_correct/total_testcases/full_compile_error（优先全文）/full_runtime_error/status_runtime/status_memory/state；CE 时 message 取 full_compile_error 全文
- [x] LeetCodePlatform 实现该能力接口（leetcode + leetcodecn 两变体共用，域名走 variant）
- [x] [unit] FakeOJTransport 单测：interpret_solution 请求体（snake_case/字段值透传）与 interpret_id 解析；check 轮询→终态字段全集映射；CE/RE 全文优先级；非终态（PENDING）继续轮询
- [x] [unit] 平台能力归属测试（service is TestEvaluationCapable）

## WP54: 对话框：测试用例编辑与云端测试结果 [x] (done @ iter 3;TestCasesEditState(按 id diff/纯调序零写入/全空白新行噪音丢弃)+TestCasesDialog(装载→diff→ChallengeWorkspaceService 写库同源,onSaved 回调);TestRunResultState(compare_result 位图逐位优先,缺失回退 answers 逐项相等,任一侧缺失 null 显 -;correctCount/totalCount 优先逐行兜底;CE 优先 RE 全文替代)+TestRunResultDialog(首行截断 60+tooltip);bundle +17 键英中;+17 测共 737 全绿,check 过,loop 层独立核验;对话框壳薄接线未接面板(接线属 WP55))
依赖 WP53
- [x] `TestCasesDialog`（DialogWrapper + JBTable 两列「输入/期望输出」）：工具条增行/删行/清空；打开时装载当前挑战用例（testCaseRepository.findByChallenge），「确定」逐行 upsert + 缺席行删除（写库与挑战工具窗测试用例区同源互通）
- [x] 行编辑纯函数抽离（列表↔表格模型转换、diff 出增/删/改），可脱离 Swing 单测
- [x] `TestRunResultDialog`（DialogWrapper）：汇总行「X/Y 用例通过」+ JBTable 每用例（输入摘要/期望/实际/通过标记，通过判定取 compare_result 逐位或 codeAnswers 与 expectedCodeAnswers 逐项比较）+ compileError/runtimeError 非空时全文区替代表格
- [x] [unit] 表格模型转换与增删改 diff 纯函数测试
- [x] [unit] 结果对话框数据组装纯函数测试（compare_result 逐位对错、CE 替代表格分支）

## WP55: 编辑器浮动工具栏三键接线 [x] (done @ iter 5;AbstractFloatingToolbarProvider isApplicableAsync+EP com.intellij.editorFloatingToolbarProvider 注册,canShow=ChallengeFileMatcher(Path.startsWith 分量匹配+归一化)+ChallengeEditorGate(TTL 3s volatile 快照+Mutex 防击穿,update 走无 IO cachedIsChallengeFile);三 action=EditTestCases(pooled 装载→TestCasesDialog)/RunCloudTest(saveDocument→区域提取→dataInput→fetchProblemDetail 取 questionId→watchTestRun→TestRunResultDialog;无能力隐藏/未登录置灰/防重入)/SubmitToOj(加 saveDocument+浮动组,SubmissionCoordinator 复用);SubmissionCoordinator.resolveTarget 收敛为委托 ChallengeEditorGate 单一定位;ProblemStatement.questionId 默认 null 不破坏 21 平台;bundle 4+6 键英中;+13 测共 749 全绿+buildPlugin 产物核验含全部新类,loop 层独立核验;429 中断一次,续做 Teamee 零补齐纯核验恢复;版本号 1.3.0 留 WP58)
依赖 WP54
- [x] 核对平台 2026.2 `FloatingToolbarProvider` 接口签名（本地 IDE 依赖源/反编译），实现 `ChallengeFloatingToolbarProvider`（EP `com.intellij.editorFloatingToolbarProvider`；actionGroupId=`CodeQuest.ChallengeEditor.FloatingToolbar`；canShow=文件属于挑战工作区）
- [x] plugin.xml：extensions 挂 `<editorFloatingToolbar>`；actions 注册 group `CodeQuest.ChallengeEditor.FloatingToolbar` 与三 action（修改测试用例 / 提交测试 / 提交，带图标）
- [x] 「修改测试用例」action：按编辑器文件定位 challenge（复用 WorkspaceTargetResolver/挑战集合枚举语义）→ 打开 TestCasesDialog
- [x] 「提交测试」action：saveDocument → 区域提取（SubmitRegionExtractor，缺标记报错同提交流程文案）→ 拉用例拼 dataInput（\n 连接，原版语义）→ questionId 经 fetchProblemDetail 取 → TestEvaluationCapable.watchTestRun 收集至终态 → TestRunResultDialog；平台无能力该键隐藏；未登录置灰；进行中防重入（同文件门禁）
- [x] 「提交」action：saveDocument → 复用 `SubmissionCoordinator.submitFile`（零新逻辑）
- [x] canShow 门控性能：路径前缀匹配工作区基目录 + 挑战集合缓存（DB 变更失效），不逐次查库
- [x] PluginBundle 英中同步：三 action 文案、两对话框全部文案、提交测试错误态（非挑战文件/平台不支持/未登录/无用例/区域标记缺失）
- [x] [unit] canShow 判定纯函数测试（工作区内/外、多基目录）
- [x] [unit] 提交测试链路纯函数部分（dataInput 拼装、错误分支文案选择）

## WP56: 后端题单：favorite 泛化+学习计划+我的题单+端点 [x] (done @ iter 2;FavoriteQueryCapable(studyPlans/fetchMyFavorites/queryFavoriteProblems)+LeetCodeStudyPlans 18 计划(官方索引页 firecrawl 实证 slug);我的题单 GraphQL 实测修正 favoritesLists.allFavorites(spec 预估 favoriteFolders 不存在,CN 变体 slug 回退 id)+REST list_favorites 备选+双失败降级空表;fetchFavoriteQuestionPage 泛化(fetchCompanyQuestionPage 委托,线协议逐字不变);/api/favorites 端点(studyPlans 独立 DTO/404 探测同 companies)+/api/problems favorite 分支(优先于 company);+12 测共 720 全绿,check 过,loop 层独立核验;偏差:interview-75/data-structure 等 spec 例举 slug 不在 /studyplan/ 域未收录)
- [x] SPI 泛化：`queryFavoriteProblems(favoriteSlug, filters, offset, limit)`（favoriteQuestionList 查询抽出；`queryCompanyProblems` 委托之，参数协议不变防回归）
- [x] 学习计划内置清单常量（slug + 中文名，约 20 个：热题 100 top-100-liked、面试经典 75 题、LeetCode 75、数据结构入门/计划、初级/中级/高级算法、二分查找/哈希/动规/图论系列、SQL 50 等）——对照 leetcode.cn/studyplan/ 官方核对 slug
- [x] `fetchMyFavorites()` 能力方法：接口探测（优先 GraphQL `favoriteFolders`，备选 REST `GET /api/list_favorites/`）；两路都失败返回空表（降级不抛错）
- [x] `GET /api/favorites?platform=` 端点：`{studyPlans:[{slug,name}], myFavorites:[{slug,name,questionCount}]}`；无能力平台 404（同 /api/companies 探测语义）
- [x] `/api/problems` 挂 `favorite=<slug>` 分支：走 queryFavoriteProblems；favorite 与 company 同给时 favorite 优先、company 忽略
- [x] [unit] queryCompanyProblems 委托泛化后回归测试（请求参数快照不变）
- [x] [unit] fetchMyFavorites 两种接口形态解析测试（favoriteFolders GraphQL / list_favorites REST）与双失败降级
- [x] [unit] /api/favorites 端点 HTTP 测试（结构/404 语义）；favorite 分支与互斥兜底测试

## WP57: 前端题库页题单下拉 [x] (done @ iter 4;api.ts FavoriteSummary/fetchFavorites+favorite 参数透传;App.vue 题单下拉两组 optgroup(我的题单非空才渲染)/与公司互斥双向(handleFavoriteChange/handleCompanyChange)/平台切换与门控归位重置/题单模式 keyword 忽略提示;渲染条件采用能力探测式(/api/favorites 404→隐藏,同公司下拉模式,与力扣系限定行为等价——偏差已记);myFavorites 降级分层(后端空数组→optgroup 不渲染,整体失败→下拉隐藏);+6 vitest 共 165 过+build 绿,gradlew test check 无回归,loop 层独立核验)
依赖 WP56
- [x] 过滤行新增「题单」下拉（渲染条件：力扣系平台）：`<optgroup label="学习计划">`（/api/favorites studyPlans）+ `<optgroup label="我的题单">`（myFavorites 非空才渲染）；默认「全部题库」
- [x] 与「公司」下拉互斥：选题单→清公司+期间；选公司→清题单（双向）
- [x] 选中后列表查询带 `favorite=<slug>`（复用现有 reload 参数链路）；题单模式下 keyword 忽略提示（同公司模式文案）
- [x] 我的题单拉取失败/未登录 → 只显示学习计划组，静默降级不打扰
- [x] 前端类型定义（FavoriteSummary）与 api.ts 请求函数
- [x] [unit] vitest：下拉渲染条件（力扣系/其他平台）、optgroup 两组结构、互斥双向清空、favorite 参数传递、myFavorites 失败降级
- [x] [acceptance] `npm run test && npm run build` 全绿

## WP58: 集成验收与定版 1.3.0 [x] (done @ iter 6;clean check test buildPlugin 全绿 749 测+前端 165+build;runIde 沙箱自动化走查:/api/favorites leetcodecn 200(18 学习计划+我的题单 1 项——力扣登录会话 favoritesLists 真实拉通实证)+codeforces 404+Playwright 题单下拉两组渲染+截图,沙箱进程 0 残留;Swing 三键/两对话框如实标留人工;CHANGELOG 1.3.0(修正描述 favoritesLists)+版本 bump+codequest-1.3.0.zip 27.8MB 产物核验含全部新类与 EP 注册;spec §4 六条三态表落盘 docs/editor-actions-manual-checklist.md)
依赖 WP53-WP57
- [x] `./gradlew test check buildPlugin` 全绿；前端 `npm run test && npm run build` 全绿
- [x] runIde 沙箱走查：开刷力扣题（Java）→ 编辑器右上浮现三键；改用例保存后挑战工具窗同步；「提交测试」结果对话框展示每用例对比；「提交」走现有链路（通知+提交记录 tab）
- [x] runIde 沙箱走查：题库页「题单」下拉出现学习计划组（选热题 100 列表切换且开刷正常）；登录后「我的题单」组出现；与公司下拉互斥
- [x] runIde 沙箱走查：CF 等平台编辑器无「提交测试」键；非挑战文件无浮动栏
- [x] CHANGELOG 1.3.0 条目 + build.gradle.kts 版本 bump + 构建产物 zip
- [x] [acceptance] spec §4 六条验收全过（对话框互通/云端测试结果/提交流程/题单下拉/能力门控/定版）

---

# 力扣 P0 四项（评分/历史同步/复习/计时器）— WP 清单

- 来源计划: `docs/plan/lc-p0-features.md`（spec: docs/superpowers/specs/2026-10-02-lc-p0-four-features-design.md, c1ddd3a）
- loopId: `loop-20261002-lc-p0`
- 调度序: WP59→WP60→WP61→WP62→WP63→WP64→WP65→WP66

## WP59: 数据库 v3 迁移与三个新仓库 [x] (done @ iter 1;三表 raw DDL 幂等迁移 v2→3、三 Exposed 仓库+wire、16 新测共 771 全绿;commit aa8f797)
- [ ] `infra/db/Tables.kt` 加三表：`problem_rating(frontend_id PK, rating REAL, updated_at)`、`remote_submission(id PK AUTOINCREMENT, platform_id, remote_id, title_slug, title, status_display, lang, runtime_ms?, memory_kb?, submitted_at, UNIQUE(platform_id, remote_id))`、`review_schedule(problem_id PK, plan_dates, history_dates, updated_at)`（两列均为 epoch 秒数组 JSON）
- [ ] schema v3 迁移（照 v2 先例）：建三表 + schema_version bump，重复执行幂等
- [ ] `ExposedRatingRepository`（upsert 全量、loadAll）、`ExposedRemoteSubmissionRepository`（upsert 单条、existsBy(platform,remoteId)、分页 listPage、countByPlatform）、`ExposedReviewRepository`（findByProblem、upsert、listAll）
- [ ] [unit] 三仓库集成测试（照 `SubmissionRepositoryIntegrationTest` 模式：save/load/unique 冲突/分页排序）
- [ ] [unit] 迁移幂等测试（v2 库升级 v3 不丢数据）

## WP60: 实现后端评分体系 [x] (done @ iter 2;zerotrac 真实拉取 2596 条内置+RatingService 三态加载/refresh 原子+UserContestCapable SPI(userContestRanking 字段名对齐插件实证)+/api/problems persistAndRespond 单点装饰三模式;+19 测共 790 全绿;commit 73c8c25)
依赖 WP59
- [ ] 打包资源 `kotlin/src/main/resources/rating/leetcode-ratings.json`：拉取 zerotrac data.json 精简为 `{frontendId: rating}` 映射（一次性脚本或手动），附下载说明注释（URL + 日期）
- [ ] `infra/rating/RatingService`：启动加载（DB 非空优先，否则内置资源）→ 内存 Map；`refresh()` 拉 zerotrac → upsert DB → 重载；失败透传错误不破坏 DB
- [ ] `ProblemSummary` 加 `frontendQuestionId: String? = null`；LC 列表解析（`LeetCodeApi` 列表项→`toSummary`）填充，其他平台 null
- [ ] REST：`GET /api/rating/status`、`POST /api/rating/refresh`（ResourceHttpServer 挂路由）
- [ ] `LeetCodeApi` 加 GraphQL `userContestRankingInfo`（LeetCodeGraphQL.kt 常量）→ `GET /api/rating/user-contest`（失败/无数据返回 null 不抛错）
- [ ] `/api/problems` 三模式（普通/公司/题单）装配处：按 frontendQuestionId 查内存 Map，行 JSON 加 `rating` 字段
- [ ] [unit] RatingService 加载优先级（DB 优先/内置兜底/空）与 refresh upsert 单测（fake 传输）
- [ ] [unit] userContestRankingInfo 样例 JSON 解析（有分/无数据/未登录）
- [ ] [unit] /api/problems 装配 rating 装饰单测；三 REST 端点 HttpTest（照 SubmissionsApiHttpTest）

## WP61: 实现站内提交历史同步 [x] (done @ iter 3;SubmissionHistoryCapable SPI+submissionList GraphQL(协议按任务给定结构,插件实无此实现,测试锁 query 文本,真实站验证留 WP66)+SyncService 吃完整页再停/200 页上限+sync 端点(400/404/502)+统计并集本地优先+remoteSyncedCount;+18 测共 807 全绿;commit 673dc02)
依赖 WP59
- [ ] SPI 新增 `SubmissionHistoryCapable`：`suspend fun fetchSubmissionHistory(offset, limit): RemoteSubmissionPage`（模型 RemoteSubmissionPage/RemoteSubmission 按 spec §2.1）
- [ ] `LeetCodeApi` 加 GraphQL `submissionList`（offset/limit 分页，字段 id/title/titleSlug/statusDisplay/runtime/memory/lang/timestamp）；LeetCodePlatform（两 variant）实现能力
- [ ] `SubmissionHistorySyncService`（infra）：倒序分页拉（页 20）逐条 upsert，遇已存 (platform_id, remote_id) 停止；上限 200 页；返回 `{synced, total}`；pooled 线程执行
- [ ] REST：`POST /api/submissions/sync`（body platformId；无能力平台 400）、`GET /api/submissions/remote?platform=&limit=&offset=`
- [ ] `StatisticsService` 合并 `submission`+`remote_submission`：streak/热力图/AC 总数取并集，(platform_id, remote_id) 重复时本地优先；统计响应加 `remoteSyncedCount`
- [ ] [unit] 同步增量停止与页上限（fake api：首条已存即停/第 N 页已存截断/超 200 页保护）
- [ ] [unit] submissionList 样例 JSON 解析（多形态 statusDisplay）
- [ ] [unit] 统计合并口径（并集/去重本地优先/AC 判定 status_display=AC）
- [ ] [integration] sync 端点 HttpTest（不支持平台 400/同步计数返回）

## WP62: 实现间隔重复复习调度 [x] (done @ iter 4;ReviewService(端口注入 core 零 infra)+settings_kv 间隔配置+AC 触发点(runCatching 不阻断)+today/add/complete/config 四端点;+21 测共 828 全绿;commit 6c688d7;首 Teamee 429 中断留主代码,续做 Teamee 核验+修 4 处编译缺陷+补 HttpTest 零损失恢复)
依赖 WP59
- [ ] `core/review/ReviewService`：`onAccepted(problemId)`（plan 空→按间隔生成；非空→移除今天及过去、history 追加今天）、`addManual(problemId)`（明天零点起全量排）、间隔读 settings_kv `review_intervals`（默认 "1,4,7,14,28,60"，非法回退默认）；零点=系统默认时区
- [ ] `SubmissionCoordinator.runSubmission` AC 后（broadcastUpdated 旁）调 `onAccepted(target.problem.id)`——平台无关
- [ ] REST：`GET /api/review/today`（overdue/today/upcoming 分组+totalTracked）、`POST /api/review/problems/{id}/add`、`/complete`
- [ ] [unit] ReviewService：首 AC 排期/再 AC 完成移除/过期判定/手动加入/间隔配置解析与非法回退/跨零点边界
- [ ] [unit] 三端点 HttpTest

## WP63: 实现前端评分呈现 [x] (done @ iter 5;评分列+五档着色(lcRatingColor.ts 独立模块,CSS 变量亮暗双套)+排序循环钮+区间本地过滤+竞赛分徽标/刷新按钮/会话缓存;+27 vitest 共 200 过+build 绿;commit 14c713f;App.vue 行布局适配,新控件避开 select 断言既有测试零改)
依赖 WP60
- [ ] 题库表格加「评分」列（数值，列排序；rating null 显示 "-"）
- [ ] 分差五档着色（评分列文字色；阈值常量绿<-200/蓝<-50/紫<50/黄<200/红≥200）；无用户竞赛分退化绝对分档（<1400/1400-1799/1800-2199/2200-2599/≥2600）；着色模式与列显隐存 localStorage
- [ ] 筛选行评分区间（min/max 数字输入，本地过滤已加载行，同状态列模式）
- [ ] 账号区显示竞赛分（/api/rating/user-contest 会话缓存）+「刷新评分」按钮（POST refresh 后重拉列表）
- [ ] 前端类型与 api.ts：ratingStatus/refresh/userContest 请求函数
- [ ] [unit] vitest：五档着色映射（分差/绝对退化/无分）、区间过滤、列排序、localStorage 持久化
- [ ] [acceptance] `npm run test && npm run build` 全绿

## WP64: 实现前端复习子视图与加入复习 [x] (done @ iter 6;ReviewPanel 三分组+徽标/间隔配置/标记完成+统计页同步按钮(remoteSyncedCount)+题面页两态按钮(problemId=<platform>:<remoteId> 确定性映射)+授权最小 Kotlin diff(ReviewRowDto.remoteId+nav 桥需);+8 vitest 共 208 过+build 绿+ReviewApiHttpTest 绿;commit 81b4add)
依赖 WP60 与 WP62
- [ ] 题库页「复习」子视图入口（对齐本地搜索子视图切换模式）：已过期(红)/今日/未来分组列表，行点击 nav:openProblem；入口徽标=今日到期数
- [ ] 子视图内间隔配置输入（写 settings_kv 经 REST）与「标记完成」操作
- [ ] 统计页「站内历史：已同步 N 条」+「同步历史」按钮（POST /api/submissions/sync，进度/结果 toast）
- [ ] 题面页 header「加入复习」按钮（已排期显示「已排期至 x」）
- [ ] [unit] vitest：子视图分组渲染、徽标计数、按钮状态（未排期/已排期）
- [ ] [acceptance] `npm run test && npm run build` 全绿

## WP65: 实现题目计时器 [x] (done @ iter 7;SubmissionsBridgeRegistry 多播(签名不变单注册逐字节一致)+payload 增补匹配键、problemTimer.ts 可测模块+题面页角标/AC 停表 toast/开关;有据偏差:ProblemViewToolWindowFactory +4 行桥挂接(spec §4 明文要求);Kotlin +8 测共 836/前端 +19 测共 227 双绿;commit 5d7d86c)
- [ ] Kotlin：`SubmissionCoordinator` 桥单槽改多播列表（attach/detach 兼容旧签名，broadcast 遍历 postMessage）；payload 增补 `platformId`+`problemRemoteId`（runSubmission 持有 target.problem），challengeId 等既有字段不动
- [ ] 前端 problem-view 页：header 计时角标 mm:ss（≥1h 显示 h:mm:ss），收到题面数据重置起表；订阅 submission:updated，(platformId, problemRemoteId) 匹配且 verdict=Accepted → 停表定格+toast 耗时
- [ ] 开关 enableTimer（localStorage 默认开）
- [ ] [unit] 多播桥单测（单注册行为与现状一致/多注册遍历/已注册页仍收到）；桥 payload 字段单测
- [ ] [unit] vitest：计时格式化、起停逻辑（匹配/不匹配 challenge、enableTimer 关闭）
- [ ] [acceptance] `npm run test && npm run build` 全绿

## WP66: 集成验收与定版 1.7.0 [x] (done @ iter 8;lastKey 修复(真实站 A/B:修复版 200/带 null 字面量 403 实锤)+防回归断言;clean 全量门禁 836+227 双绿 zip 26.9MB;沙箱 11 项端点探测(zerotrac 真实刷新 2596/rating 题号关联实证/sync 401→502 透传如设计);手动清单三态 docs/lc-p0-manual-checklist.md;CHANGELOG+1.7.0 bump;commit 9236731)
依赖 WP63-WP65
- [ ] `./gradlew test check buildPlugin` 全绿；前端 `npm run test && npm run build` 全绿
- [ ] runIde 沙箱走查：题库页评分列+着色（登录后分差档/未登录绝对档）+区间筛选+刷新评分按钮
- [ ] runIde 沙箱走查：同步历史按钮拉回站内提交，统计页 streak/热力图/AC 计数随之修正
- [ ] runIde 沙箱走查：开刷提交 AC → 复习子视图出现该题明日到期；题面页「加入复习」可用；次日到期徽标（可改系统时间或调间隔验证）
- [ ] runIde 沙箱走查：切题计时重置、AC 停表定格+toast；提交记录页（submissions）刷新行为不变（多播回归）
- [ ] CHANGELOG 1.7.0 条目 + build.gradle.kts 版本 bump
- [ ] [acceptance] spec §7 风险项全过：zerotrac 失败不破坏 DB、无竞赛分退化、bridge 多播回归、同步上限保护

---

# 力扣 P1 三项（每日一题/竞赛回顾/排序+隐藏已解决）— WP 清单

- 来源计划: `docs/plan/lc-p1-features.md`（spec: docs/superpowers/specs/2026-10-03-lc-p1-features-design.md, 23cc0ac）
- loopId: `loop-20261003-lc-p1`
- 调度序: WP67→WP68→WP69→WP70→WP71

## WP67: 后端每日一题与 LC 竞赛能力 [x] (done @ iter 1;DailyQuestionCapable+ContestCapable 仅 CN(LeetCodeCnPlatform 子类,COM is not 天然区分)+三项协议真实站实测锁定(todayRecord 数组+Referer/operationName 可过已 loop 层 curl 复证/contest 字段 title_slug 与 spec 预估不符按实锁定/不存在 slug HTTP200+body error);+21 测共 857 全绿;commit 6abe770)
- [ ] SPI 新增 `DailyQuestionCapable`（`suspend fun fetchDailyQuestion(): DailyQuestion?`，模型 DailyQuestion(date/frontendQuestionId/title/titleSlug/difficulty/tags)）——仅 `LeetCodePlatform` CN 变体实现；协议 CN GraphQL `questionOfToday`（查询文本进 LeetCodeGraphQL.kt；translatedTitle 优先作 title；解析失败/字段缺失返回 null 降级，未登录 errors 信封不抛给上层）
- [ ] `LeetCodeApi` 加 `contestHistory(pageNum=1, pageSize=10)`（CN GraphQL；字段 title/titleSlug/startTime/duration——**startTime 单位与字段形态真实站实测锁定**）；`LeetCodePlatform` CN 变体实现 `ContestCapable.contests()`：phase 按 startTime+duration 与 now 推导（BEFORE/CODING/FINISHED 映射进现有五值枚举，注入 clock 测边界）；ContestSummary(remoteId=titleSlug, url=leetcode.cn/contest/{slug}/)；joinable 恒 false
- [ ] `contestDetail(remoteId)`：CN REST `GET /contest/api/info/{titleSlug}/` → questions → `ContestProblemRef(remoteId=question title_slug, index="Q{n}", title)`——字段名真实站实测锁定（插件 recentContestData 模块可 grep 参考，疑为 question__title/question__title_slug 下划线形态）；竞赛不存在抛 OJException；题目拉取失败降级空列表（照 CF 先例）
- [ ] REST：`GET /api/daily-question?platform=`（当日进程内缓存 volatile (date,payload)，跨日失效；无能力平台 404 照 /api/contests 探测惯例；挂 ResourceHttpServer 照 RatingApi 先例）
- [ ] [unit] questionOfToday 三态（正常含翻译优先/字段缺失 null/errors 信封 null）
- [ ] [unit] contestHistory 解析 + phase 推导边界（注入 clock：赛前/赛中/赛后跨界三例）+ startTime 单位锁定断言
- [ ] [unit] contest/api/info 解析（正常/字段缺失降级空表/不存在抛 OJException）
- [ ] [integration] /api/daily-question HttpTest（200/缓存同日命中/无能力 404）+ /api/contests?platform=leetcodecn 与 leetcode（COM）能力探测（CN 实现/COM 404）HttpTest
- [ ] 平台能力归属测试（CN is ContestCapable+DailyQuestionCapable；COM is not）

## WP68: 后端排序 sortBy 透传 [x] (done @ iter 2;ProblemFilter.sortBy+双站枚举实测锁定(CN V1 filters.orderBy/COM V2 sortBy.sortField 共用 FRONTEND_ID/DIFFICULTY,猜测值 FRONTEND_QUESTION_ID 被实测否定)+白名单回退+/api/problems 参数映射;+5 测共 862 全绿;commit ef668bd)
依赖 WP67
- [ ] `ProblemFilter` 加 `sortBy: String? = null`（协议枚举名透传；SPI 语义：实现忽略不支持值，照 tags 先例 KDoc 声明）
- [ ] `LeetCodeApi` 列表查询：sortBy 非空时替换写死的 CUSTOM ASCENDING——**协议枚举值真实站实测锁定**（预期 FRONTEND_QUESTION_ID/DIFFICULTY 类形态，curl leetcode.cn graphql 验证），未知/空值照旧 CUSTOM；请求参数快照测试（id→枚举A/difficulty→枚举B/空→CUSTOM）+ 防回归断言
- [ ] REST `/api/problems` 加 `sortBy` 参数（值域 `id`/`difficulty` 映射协议枚举，非法值忽略回默认序；题单/公司模式照旧忽略——协议不支持）
- [ ] [unit] LeetCodeApiTest 请求快照三态；ProblemApi sortBy 参数 HttpTest（透传/非法回退/题单模式忽略）
- [ ] [unit] 其他平台（CF/AtCoder 等 FakeOJTransport）sortBy 被忽略无回归

## WP69: 前端每日一题卡片与竞赛 tab 接入 [x] (done @ iter 3;今日题目常驻横幅(降级静默)+fetchDailyQuestion/fetchContestDetail+竞赛详情展开点题(核对发现原无点题交互,新增 platform.value 参数化 CF/LC 共用,LC payload 显式断言)+LC 提醒订阅通用可用;+8 vitest 共 235 过+build 绿;commit 0d9d73a)
依赖 WP67
- [ ] 题库页（frontend/src/pages/problems/App.vue）力扣 CN 平台时顶部常驻「今日题目」卡片：日期+题号+标题+难度徽标+标签 chips，点击 nav:openProblem(platformId=leetcodecn, remoteId=titleSlug)；非 CN/失败/404 静默不渲染（照 myFavorites 降级惯例）
- [ ] api.ts：fetchDailyQuestion(platform) + DailyQuestion 类型
- [ ] 竞赛 tab：核对 LC 平台接入（fetchContestPlatforms 能力探测自动出现）；竞赛行字段补齐共用模板（标题+开始时间本地化+时长+状态徽标，CF/LC 同受益）；详情题目行 Q 序号+标题，点题开题面——**实测核对 contest 页点题对 LC 平台的 platform 参数传递**（remoteId=titleSlug 与题库同源）
- [ ] 竞赛提醒订阅对 LC 平台可用性核对（contest_reminders 通用零改动，UI 上确认可订阅，不可用如实记录）
- [ ] [unit] vitest：卡片渲染条件三态+字段+点击导航 payload；竞赛行字段渲染；LC 平台出现于竞赛平台下拉
- [ ] [acceptance] `npm run test && npm run build` 全绿（基线 227 不破坏）

## WP70: 前端排序五档与隐藏已解决 [x] (done @ iter 4;五档循环不换控件(题号/难度服务端 reload 带 sortBy,评分本地标注仅已加载,题单/公司模式跳过服务端档)+隐藏已解决复用 statusOf 本地过滤+localStorage+三条件叠加;+4 vitest 共 239 过+build 绿;commit be8ae26)
依赖 WP68
- [ ] 扩展排序循环按钮档位：默认 → 题号 → 难度 → 评分↑ → 评分↓（题号/难度带 sortBy 参数 reload 服务端排序；评分两档保持本地排序标注「仅已加载」；切换平台/筛选重置默认）
- [ ] 「隐藏已解决」checkbox（筛选行评分列开关旁）：本地过滤已加载行（状态徽标非 AC 隐藏，复用现有状态判定函数；未登录仅按本地提交判定）；localStorage 键 `cq-problems-hide-solved` 默认关；与评分区间/状态筛选叠加（filter-note 计数合并）
- [ ] [unit] vitest：五档循环切换与 reload/本地分流、sortBy 参数传递、隐藏已解决过滤（AC 保留/非 AC 隐藏/未登录判定）、localStorage 持久化、叠加计数
- [ ] [acceptance] `npm run test && npm run build` 全绿

## WP71: 集成验收与定版 1.8.0 [x] (done @ iter 5;clean 门禁 862+239 双绿 zip 28,245,763B;沙箱 7 组端点真实数据(每日一题 1.65s 首拉/450x 缓存/官方站交叉一致,竞赛 10 条真实+Q1-Q4,sortBy 两档顺序实变)+Playwright UI 两项;手动清单 docs/lc-p1-manual-checklist.md;CHANGELOG+1.8.0 bump;commit 327cb63)
依赖 WP69-WP70
- [ ] `./gradlew clean test check buildPlugin` 全绿；前端 `npm run test && npm run build` 全绿（记录数字与 zip 产物）
- [ ] runIde 沙箱端点探测：`GET /api/daily-question?platform=leetcodecn`（真实拉取记录字段）、`?platform=leetcode`（404/COM 无能力）、`GET /api/contests?platform=leetcodecn`（真实竞赛列表记录条数与 phase）、`GET /api/contest/{真实slug}?platform=leetcodecn`（详情题目）、`GET /api/problems?platform=leetcodecn&sortBy=id` 与 `sortBy=difficulty`（列表真实顺序变化对比）；沙箱进程杀净 0 残留；起不来如实标留人工
- [ ] runIde 沙箱：题库页每日一题卡片渲染（Playwright/或留人工）、竞赛 tab LC 平台下拉与行字段
- [ ] CHANGELOG 1.8.0 条目 + build.gradle.kts 版本 bump
- [ ] 手动清单 `docs/lc-p1-manual-checklist.md`（三态+spec §6 风险结论表）
- [ ] [acceptance] spec §6 五条风险全过：每日一题降级不致败/竞赛字段实测锁定/sortBy 枚举实测+回退/竞赛行模板共用/phase 时区统一 Instant
