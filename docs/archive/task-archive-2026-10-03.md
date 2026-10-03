# Task Archive — 2026-10-03

> 归档范围：task.md 首节「CodeQuest UI 与功能优化 — WP 清单」WP1-WP8（旧 Scala 时代遗留 8 个 pending 工作包）
> 归档原因：**过时废弃**（非完成归档）——该清单面向旧 Scala 工程（loop-20260803-codequest-ui），旧工程已在 Kotlin 重写（loop-20260814-codequest-rewrite-m1m2 起）中删除；8 项能力均已被 Kotlin 版等价或超越实现（渲染管线/统计/竞赛/跨题检索 FTS/多 OJ 平台 SPI 等，见 WP9-71 历史记录）。经用户指示（2026-10-03）废弃归档，task.md 移除该节。
> 以下为归档原貌：

---

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
