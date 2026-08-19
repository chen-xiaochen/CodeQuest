# CodeQuest Kotlin 重写 M3:LeetCode/CN 平台闭环(验证 SPI)

接入首个 OJ 平台:LeetCode + 力扣CN 的登录、查题、题面渲染、提交轮询全链路,同时落定 OJ 能力型 SPI 与 HTTP 会话层——后续平台(M4)只写适配器不碰核心。设计依据:docs/superpowers/specs/2026-08-14-codequest-kotlin-rewrite-design.md §2(SPI)/§5(迁移要点)/§7(M3)。旧 Scala leetcode 包只读参考。

## 定义 OJ 能力型 SPI 与注册机制
- [ ] spi 包:OJPlatform(id/domain/info) + AuthCapable/ProblemQueryCapable/SubmissionCapable/SolutionViewCapable(suspend+Flow,签名按 spec §2)
- [ ] OJPlatformRegistry:IntelliJ Extension Point 注册 + find(id)/allWith<T>() 查询;plugin.xml 声明 extensionPoint
- [ ] BaseSubmissionFlow 通用提交轮询状态机:submit→轮询(指数退避+超时)→终态;平台只实现 fetchRemoteStatus() 钩子
- [ ] 领域异常体系(core 或 spi):OJException/AuthExpiredException/AntiBotChallengeException/RateLimitedException
- [ ] [unit] 状态机单测(轮询节奏/终态判定/超时/异常传播),Registry 单测

## 实现 HTTP 会话层与凭据管理
依赖:先完成「定义 OJ 能力型 SPI 与注册机制」
- [ ] infra/http:ktor-client(OkHttp 引擎)封装,按平台隔离的 SessionStore(每平台独立 CookieJar)
- [ ] infra/secrets:登录凭据/cookie 持久化(沿用旧 SensitiveDataStore 语义,加密存储)
- [ ] HTTP 错误→领域异常映射(401/403→AuthExpired,429→RateLimited,反爬特征→AntiBot)
- [ ] [unit] MockEngine 单测:cookie 隔离/异常映射/请求头

## 实现 LeetCode/力扣CN 平台适配器
依赖:先完成「实现 HTTP 会话层与凭据管理」
- [ ] platform/leetcode:LeetCodeApi(GraphQL,leetcode.com 与 leetcode.cn 双域名)、LeetCodeAuth(cookie 粘贴登录+登录态校验)、LeetCodeProblemService(题目列表查询/详情)、LeetCodeSubmissionService(实现 SPI,消费 BaseSubmissionFlow)
- [ ] 语言映射:提交语言 id 表(参考旧实现);CN 端 Java 代码模板注意 spec 2026-08-03 的提交区域标记约定
- [ ] OJPlatformProvider 注册 plugin.xml(leetcode/leetcodecn 两个 id)
- [ ] [unit] MockEngine 回放夹具:GraphQL 查询构造/响应解析/轮询状态转换;不上真实网络

## 打通题面渲染与题目列表真实数据
依赖:先完成「实现 LeetCode/力扣CN 平台适配器」
- [ ] ResourceHttpServer 数据端点:/api/problems(平台筛选+关键字+分页)、/api/problem/:id(题面详情),数据来自 SPI 查询+本地库缓存
- [ ] frontend problems 页:题目列表真实渲染(筛选/搜索/分页),点击经桥 nav:openProblem 打开题面
- [ ] frontend problem-view 页:真实题面渲染(HTML/Markdown 管线 + KaTeX 公式 + hljs 代码高亮,LC 题面 HTML 的清洗与样式适配)
- [ ] 题面写入 problem_content 落库(离线回看)
- [ ] [unit] 前端渲染冒烟(题面样例)+ 端点单测

## 实现提交流态与编辑器提交动作
依赖:先完成「实现 LeetCode/力扣CN 平台适配器」
- [ ] 编辑器 action:提交当前 challenge 代码(提取提交区域标记内代码)→ SPI submit → 轮询 → Submission 落库(含代码快照)
- [ ] AuthExpiredException → 触发重登流程(cookie 粘贴对话框)后自动重试一次
- [ ] submissions 页/面板:提交结果与历史真实数据(HTTP 端点 + 提交完成事件推送刷新)
- [ ] 提交结果通知(IntelliJ 通知,AC/失败文案 i18n)
- [ ] [unit] 代码提取(区域标记解析)单测;提交流程集成测试(mock 平台)

## M3 集成验收
依赖:先完成「打通题面渲染与题目列表真实数据」和「实现提交流态与编辑器提交动作」
- [ ] ./gradlew check test buildPlugin 全绿 + frontend npm test/build 全绿 + CI 全绿
- [ ] [acceptance] 沙箱实测(自动化能做多少做多少,真实账号交互写手工步骤):登录→查题列表→打开题面渲染正确→创建 challenge→提交代码→轮询出结果→落库回看
- [ ] SPI 验证结论:以 LeetCode 适配器为例,记录「新增一个 OJ 需要写哪些文件、不碰哪些层」(spec 成功标准 1 的实证)
- [ ] 遗留修复:WP15 的 settings 面板 html 标签 UiDslException(顺手修,小改动)

## 成功标准
- 全部工作单元 checklist 通过
- LeetCode/CN 全链路可用:登录/查题/题面渲染/提交轮询/落库回看(mock 层自动化验证 + 真实账号手工 checklist 文档化)
- SPI 落定且被首个真实平台消费验证:BaseSubmissionFlow 被复用,适配器不含轮询代码
- check/test/buildPlugin + CI + Vitest 全绿;旧 Scala 工程零改动
