# CodeQuest UI 与功能优化

CodeQuest（基于 LeetCode Epiphany 二开的 IntelliJ 刷题插件）UI 与功能优化路线图。前序代码审查识别的优化项，按优先级与依赖组织为 8 个工作单元，供 skill-agentic-loop 自闭环执行。

## 前端性能与依赖瘦身
- [ ] webview/leetCodeSolutionArticle/app.ts：将 `import hljs from 'highlight.js'` 改为 `import hljs from 'highlight.js/lib/core'`
- [ ] 用 `hljs.registerLanguage(...)` 注册刷题常用语言（python/java/cpp/c/js/ts/go/rust/sql/kotlin/swift/php 等约 12 种）
- [ ] webview 目录执行 `npm uninstall mathjax`（前端仅用 katex，mathjax 零引用）
- [ ] 重新 `npm run build`，确认无报错
- [ ] [acceptance] leetCodeSolutionArticle-*.js bundle < 150KB（当前 1004KB）

## challengeDescription 去 jQuery
- [ ] 排查 webview/challengeDescription/index.html 及其后端渲染入口，定位 jquery-1.9.1.min.js 的所有用法
- [ ] 用原生 DOM API（fetch/classList/querySelector/addEventListener）替换 jQuery 调用
- [ ] 删除 webview/challengeDescription/jquery-1.9.1.min.js 及对其的引用
- [ ] [acceptance] challengeDescription 页面渲染与交互行为与去 jQuery 前一致

## Markdown 渲染管线统一
建议先完成「前端性能与依赖瘦身」
- [ ] 对比 challengeDescription（后端 flexmark）与 leetCodeSolutionArticle（前端 marked）的渲染差异（表格/代码块/数学/扩展语法）
- [ ] 选定统一引擎（推荐前端 marked 集中渲染）并记录决策
- [ ] 改造非选定方的渲染入口，统一到选定引擎
- [ ] [acceptance] 各 OJ 题目描述渲染效果一致

## 刷题统计可视化
- [ ] 设计统计 toolwindow 布局（提交数/通过率/难度分布/刷题热力图）
- [ ] 数据层：基于 submission 相关 jooq 表实现聚合查询服务
- [ ] 前端：webview 新增统计页面（图表 + 热力图，复用现有 vite MPA 多入口模式）
- [ ] Scala：新增 ToolWindowFactory 并在 plugin.xml 注册
- [ ] [acceptance] 统计数据与 submission 表一致，图表/热力图正确渲染

## OJ 公共基类抽象
- [ ] 分析 leetcode/codeforces/atcoder/luogu/hackerrank 五模块 services 的重复模式（各 SubmissionService 共用 CefRemoteRequestHandler 等）
- [ ] 抽象 BaseOjService / BaseSubmissionService 及公共特质
- [ ] 迁移各 OJ 到公共基类，保留各平台差异
- [ ] [acceptance] 各 OJ 查询/提交/登录行为不变，重复代码显著减少

## 竞赛功能
- [ ] 实现 LeetCode 或 CodeForces 竞赛列表查看
- [ ] 竞赛详情与参加入口
- [ ] 竞赛时间提醒（系统通知）
- [ ] 竞赛排名/结果查看
- [ ] [acceptance] 至少一个 OJ 的竞赛列表与详情可用

## 跨题检索与标签
- [ ] 实现题库全文检索（基于 sqlite FTS5 或现有查询）
- [ ] 标签管理 + 按标签筛选题目
- [ ] [acceptance] 可按关键词或标签检索题目并定位打开

## 更多 OJ 支持
依赖 先完成「OJ 公共基类抽象」
- [ ] 调研目标 OJ（牛客 NowCoder 等）的 API 与提交方式
- [ ] 基于公共基类实现新 OJ 模块（services/actions/settings）
- [ ] [acceptance] 新 OJ 可查询题目并提交代码

## 成功标准
- 全部纳入工作单元的 checklist 通过
- [acceptance] 前端 leetCodeSolutionArticle bundle < 150KB
- [acceptance] 刷题统计 toolwindow 可用且数据正确
- [acceptance] 插件在 IntelliJ 2025.2 与 2026.2 上正常加载运行，现有功能不被破坏
- [acceptance] npm run build 与 sbt packageArtifact 均无报错
