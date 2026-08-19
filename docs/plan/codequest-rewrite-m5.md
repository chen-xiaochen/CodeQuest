# CodeQuest Kotlin 重写 M5:统计/跨题检索/题解正文(增强)

在六平台全量接入的基础上完成三项增强:刷题统计可视化、跨题全文检索、题解文章正文渲染。设计依据:spec §4(stats/search 页)/§3(FTS)/§7(M5)。竞赛提醒已在 M4 交付不重复。

## 实现刷题统计可视化
- [ ] core 用例:StatisticsService——按平台/难度/日期聚合 submission(总数/AC 数/AC 率/连续天数/streak),数据源 submissionRepository(与 submission 表一致,旧版验收口径延续)
- [ ] /api/stats 端点(平台过滤+时间范围):汇总卡片 + 日历热力图数据(日期→提交数)+ 趋势序列
- [ ] stats 前端页真实化:汇总卡片(总提交/AC/AC 率/当前 streak)+ 热力图(GitHub 风格,纯 CSS/SVG,旧 WP4 经验)+ 近 N 天趋势条形
- [ ] [unit] 统计聚合单测(边界:空库/单日多条/跨平台过滤/streak 断续)+ 端点单测;前端渲染冒烟

## 实现跨题全文检索与标签筛选
依赖:先完成「实现刷题统计可视化」
- [ ] /api/search 端点:keyword(FTS5:problem_content+solution.note_md+problem.title,WP14 的 FtsSupport/SolutionNoteSearch 复用,<3 字符 LIKE 降级)+ 标签过滤(tag 表 join)+ 平台过滤;返回题目列表(含命中来源标记:题面/题解/标题)
- [ ] search 前端页真实化:搜索框+标签多选(标签列表经 /api/tags 或复用既有数据)+结果列表;点击结果经 nav:openProblem 打开题面(定位到题目)
- [ ] [integration] 检索集成测试:中文关键词命中题面/题解笔记、标签过滤组合、空结果、降级路径
- [ ] [unit] 前端搜索页渲染测试

## 实现题解文章正文渲染
依赖:先完成「实现跨题全文检索与标签筛选」
- [ ] SPI 增量(最小):SolutionViewCapable 扩展 `fetchSolutionContent(ref: SolutionArticleRef): SolutionArticleContent`(MD/HTML + 标题);洛谷与 LeetCode/CN 实现——端点按旧 Scala 实现事实
- [ ] /api/problem/:remoteId/solutions/:sid 端点(题解正文较大,YAGNI 先不落库,报告说明取舍)
- [ ] 前端:题解正文渲染页(复用 problem-view 的 MD/HTML 管线+KaTeX+hljs)+ 浏览器风格导航工具栏(后退/前进/刷新)+ 题解列表→正文跳转
- [ ] [unit] 题解正文解析单测(洛谷 MD/LC HTML 夹具)+ 前端渲染冒烟

## M5 集成验收
依赖:先完成三个增强工作单元
- [ ] gradlew clean check test buildPlugin --rerun-tasks + npm test/build 全绿;产物完整
- [ ] 沙箱实测:stats 页真实渲染(测试数据注入);search 页检索已缓存题面命中;题解列表→正文打开渲染正确
- [ ] docs/m5-manual-checklist.md:统计核对(与 submission 表对照)/检索/题解链路手工步骤
- [ ] M5 与 spec 对照偏差记录(如有)

## 成功标准
- 全部工作单元 checklist 通过
- 统计数据与 submission 表一致(单测+手工对照);热力图/趋势正常渲染(亮/暗主题)
- 检索:中文关键词+标签可定位题目并打开题面;FTS 降级路径可用
- 题解:洛谷/LC 题解正文渲染(公式/代码高亮),导航工具栏可用
- check/test/buildPlugin + CI + Vitest 全绿;旧 Scala 工程零改动
