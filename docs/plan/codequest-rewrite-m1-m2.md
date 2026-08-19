# CodeQuest Kotlin 重写 M1+M2:工程骨架与核心数据层

把 CodeQuest 插件从 Scala/sbt 迁到 Kotlin/Gradle 全新骨架,并完成核心领域模型、数据层与 challenge 工作区。设计依据:docs/superpowers/specs/2026-08-14-codequest-kotlin-rewrite-design.md。旧 Scala 源码保留不动,M6 才删除。

## 搭建 Gradle/Kotlin 插件工程骨架
- [ ] 新建 settings.gradle.kts + build.gradle.kts,使用 intellij-platform-gradle-plugin 2.x、Kotlin 2.x、JVM 21
- [ ] 配置 IntelliJ Platform 2025.2 (252) 依赖,since-build=252,无多版本兼容分支
- [ ] 新建 src/main/kotlin 下包结构:com.codequest.{core,spi,platform,infra,ui}(各包放占位 .kt 文件说明职责)
- [ ] 新建最小 plugin.xml:plugin id com.codequest、名称 CodeQuest、vendor、description
- [ ] Gradle wrapper 就位,仓库根添加 .gitignore 补充 build/ 与 .gradle/
- [ ] [acceptance] ./gradlew buildPlugin 产出可安装 zip,zip 内含正确 plugin.xml 与 since-build=252

## 配置代码质量门禁与 CI
依赖:先完成「搭建 Gradle/Kotlin 插件工程骨架」
- [ ] 配置 detekt + ktlint,纳入 check 任务
- [ ] 实现分层依赖检查(ui→platform→spi→core 方向禁令、platform 各 OJ 包互相 import 禁令;可用 detekt 自定义规则或 Gradle 编译期检查脚本)
- [ ] 改造 .github/workflows CI 为 Gradle:test + detekt + buildPlugin 全绿才算通过
- [ ] [acceptance] 故意写入一条 ui 包 import platform 之外的反向/越层依赖时,CI 或本地 check 失败;移除后恢复绿色

## 实现 JCEF 工具窗口壳与 CQBridge 协议骨架
依赖:先完成「搭建 Gradle/Kotlin 插件工程骨架」
- [ ] 用 Kotlin UI DSL 实现左侧 CodeQuest 主工具窗口壳(可切换面板容器,无 GUI Designer .form)
- [ ] 实现 ResourceHttpServer:本地静态资源服务 + REST 风格数据端点骨架(/api/health 可访问)
- [ ] 实现 CQBridge:Kotlin 侧 postMessage(topic, jsonPayload)/on(topic, handler),JCEF 消息仅承载命令与事件
- [ ] 实现编辑器侧题面/提交结果两个工具窗口占位壳
- [ ] [unit] 桥协议单测:topic 注册/分发/未知 topic 不抛异常
- [ ] [acceptance] IDE 沙箱中打开工具窗口,JCEF 页面经 /api/health 拉到数据且桥 echo 往返可用

## 搭建 webview 统一前端脚手架
依赖:先完成「搭建 Gradle/Kotlin 插件工程骨架」
- [ ] webview/ 新建单一 Vite + Vue3 + TS 应用,页面 /problems /contest /stats /search /problem-view /submissions 各为独立 entry(非 SPA 大包)
- [ ] 实现主题跟随:从 IDE 注入 JB 颜色 CSS 变量,亮/暗模式均可用
- [ ] 集成 highlight.js(按需语言)与 KaTeX(本地资源,沿用现有本地化结论)
- [ ] 实现 JS 侧 CQBridge 客户端(postMessage/on 对称 API)
- [ ] 各页面空壳 + 导航占位,构建产物输出到插件资源目录
- [ ] [unit] Vitest:桥客户端 mock 测试 + 一条页面渲染冒烟

## 实现 core 领域模型与仓库接口
依赖:先完成「搭建 Gradle/Kotlin 插件工程骨架」
- [ ] 定义领域模型:Problem/ProblemDetail/Challenge/ChallengeLanguage/Solution/TestCase/Submission/Contest/SubmissionResult 等(value class id、kotlinx.serialization)
- [ ] 定义平台无关仓库接口:ProblemRepository/SubmissionRepository/ChallengeRepository/SolutionRepository/TestCaseRepository/TagRepository/ContestReminderRepository(接口放 core,实现归 infra)
- [ ] [unit] 领域模型单测(序列化往返、SubmissionResult 终态判定)

## 实现 Exposed 数据层(schema + DAO + FTS5)
依赖:先完成「实现 core 领域模型与仓库接口」
- [ ] Exposed + JDBC SQLite 接入,建全量表:problem/problem_content/challenge/challenge_language/solution/test_case/submission/tag/problem_tag/contest_reminder/settings_kv,含 schema_version 版本表
- [ ] problem_content 与 solution.note_md 建 FTS5 虚拟表,LIKE 作降级路径
- [ ] 实现 core 仓库接口的 Exposed DAO,数据库写操作经单写协程(Mutex 串行化),WAL 模式
- [ ] 数据库文件位置遵循"数据库文件夹"设置
- [ ] [integration] 临时 SQLite 库集成测试:建表/CRUD/FTS5 中文检索/并发读写不损坏
- [ ] [unit] DAO 单测(内存假实现对照)

## 实现 settings 与数据库文件夹管理
依赖:先完成「实现 Exposed 数据层(schema + DAO + FTS5)」
- [ ] CodeEpiphanySettings 等价物:数据库文件夹路径设置(Kotlin UI DSL 面板,含空路径校验)
- [ ] 设置变更经消息总线通知数据层重建连接
- [ ] i18n:PluginBundle properties 保留 + Kotlin 类型化包装(中英双语资源齐全)
- [ ] [acceptance] 修改数据库文件夹后重启,数据在新位置生效

## 实现 challenge 工作区(编辑器开题/多解法/测试用例)
依赖:先完成「实现 settings 与数据库文件夹管理」
- [ ] 从题目创建 challenge 工作区:按语言模板生成入口文件(模板渲染上下文沿用 spec 2026-08-03 的变量约定)
- [ ] 多解法管理:同一 challenge 下多 solution,编辑/切换/比较入口
- [ ] 测试用例面板:用例增删改、本地运行(运行器逻辑参考旧 TestCases 实现,重写)
- [ ] [unit] 模板渲染与用例解析单测
- [ ] [acceptance] 手工 checklist:创建 challenge → 写多解法 → 添加测试用例 → 本地运行通过 → 数据落库可回看

## 成功标准
- 全部工作单元 checklist 通过
- ./gradlew test detekt buildPlugin 全绿,CI 全绿
- M1:IDE 沙箱可安装运行,6 个前端页面经统一桥与 HTTP 端点可打开
- M2:创建 challenge/多解法/测试用例全链路可用,数据持久化到自选文件夹的 SQLite(含 FTS5 检索)
- 旧 Scala 源码与 sbt 构建完整保留未被破坏(新旧并存,互不影响构建)
