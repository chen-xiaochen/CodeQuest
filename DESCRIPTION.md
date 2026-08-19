CodeQuest

> A fork of LeetCode Epiphany (Apache-2.0, © Wenjun Huang), fully rewritten in Kotlin + Gradle: a competitive programming assistant for IntelliJ-based IDEs (2026.2+), covering six OJ platforms with a capability-based plugin SPI.

### Supported Platforms

- **[LeetCode](https://www.leetcode.com)**
- **[LeetCode Chinese](https://www.leetcode.cn)** / **[力扣](https://www.leetcode.cn)**
- **[CodeForces](https://codeforces.com/)**
- **[AtCoder](https://atcoder.jp)**
- **[LuoGu](https://www.luogu.com.cn/)** / **[洛谷](https://www.luogu.com.cn/)**
- **[HackerRank](https://www.hackerrank.com)**

### What it does

- Query problem lists with keyword/difficulty filters, browse statements in-app (HTML/Markdown, KaTeX + syntax highlight, offline cache)
- One-click submit from the editor with a shared polling state machine and balloon notifications; submissions stored with code snapshots
- Challenge workspace: multiple solutions per problem, each with its own language, template and entry file; test-case management
- CodeForces contest list/detail with 24h subscribable reminders
- Statistics: summary cards, GitHub-style heatmap, 30-day trend
- Cross-problem full-text search (FTS5 over titles/statements/solution notes) with tag filtering
- In-app solution article reader for LeetCode CN and Luogu
- Cookie-paste login persisted via PasswordSafe; Cloudflare Turnstile handled through a JCEF dialog (CodeForces/AtCoder)

Capability boundaries are documented honestly (e.g. leetcode.com lists/solutions require login, CF rankings are not available without a public API) — see the README for the full matrix.

### 极客刷题助手

- 六大 OJ 平台一个插件搞定:LeetCode / 力扣 / CodeForces / AtCoder / 洛谷 / HackerRank
- IDE 内查题、题面渲染(KaTeX 公式/代码高亮/离线缓存)、编辑器右键一键提交、气球通知判题结果
- 多解法工作区:同一道题多种语言/多套解法,`begin/end` 区域标记界定提交代码,测试用例面板管理
- 刷题统计(汇总/热力图/趋势)、跨题全文检索(题面+题解笔记+标题)与标签筛选、题解文章应用内阅读
- 粘贴 Cookie 登录并持久化,重启免登;Turnstile 人机验证经 JCEF 弹窗引导
- 刷题数据存本地 SQLite(默认 `~/.codequest/codequest.db`,设置中可改目录),多 IDE 可共享
- 能力边界如实标注(如 leetcode.com 列表/题解需登录、CF 排名无公开 API 不承诺),完整矩阵见 README

### License / 许可

Apache License 2.0. Fork of [LeetCode Epiphany](https://github.com/WenjunHuang/CodeEpiphany) (Apache-2.0, © Wenjun Huang); upstream copyright notices retained.

### Learn More

- **[📖Documentation / 文档](https://github.com/chen-xiaochen/CodeEpiphany/blob/main/README.md)**
- **[🐛Report Issues / 提交问题](https://github.com/chen-xiaochen/CodeEpiphany/issues)**
