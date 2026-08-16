# M3 集成验收手册(沙箱实测 + 手工 checklist)

- 日期:2026-08-16(WP22)
- 环境:`./gradlew runIde` 沙箱(IntelliJ IDEA 2026.2,IU-262.8665.258),沙箱日志 `.intellijPlatform/sandbox/codequest/IU-2026.2/log/idea.log`
- 原则:自动化能验证的已验证并附证据;需要真实账号/人工交互的项如实标注「留手工」并给出步骤

## A. 自动化已执行项(证据见 WP22 报告)

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| A1 | 沙箱插件加载 0 ERROR | 通过 | 本轮(2026-08-16 18:31)idea.log 全文 0 ERROR、0 UiDslException;`CodeQuest resource server started on http://127.0.0.1:14751` |
| A2 | `GET /api/health` | 通过 | `{"status":"ok","version":"0.1.0"}` |
| A3 | `GET /api/platforms` | 通过 | `{"platforms":[{"id":"leetcode","displayName":"LeetCode"},{"id":"leetcodecn","displayName":"力扣"}]}` |
| A4 | `GET /api/problems?platform=leetcodecn&limit=3`(真实网络,未登录) | 通过 | 返回真实题目(two-sum/add-two-numbers/longest-substring-...),`hasMore:true`,同时落库 |
| A5 | `GET /api/problems?platform=leetcode&limit=5`(真实网络,未登录) | 平台侧受限 | leetcode.com 列表 GraphQL 已要求凭据:返回 `{"error":"auth-expired","message":"...HTTP 401..."}`(401 → AuthExpiredException 映射正确;COM 站查列表需先登录,CN 站无需) |
| A6 | `GET /api/problem/two-sum?platform=leetcodecn`(真实网络) | 通过 | 返回真实题面(HTML content)+ title/difficulty/url |
| A7 | `GET /api/submissions`(空库) | 通过 | `{"offset":0,"limit":5,"items":[],"hasMore":false}` |
| A8 | 静态资源页 | 通过 | `GET /problems/` → 200 `text/html` |
| A9 | WP15 修复:settings 面板 `<html><body>` UiDslException | 通过 | 修复前(2026-08-15 idea.log)42 处 SEVERE,栈指向 `RowImpl.comment → checkDeniedHtmlTags`;修复后 `--rerun-tasks` 全量构建(含 headless `buildSearchableOptions`,已确认实例化 `codequest.settings` 面板并索引 `settings.databaseFolder.label`)0 UiDslException / 0 SEVERE |

## B. 留手工项(未自动执行,按下列步骤逐项过)

以下链路需要真实 LeetCode/CN 账号 cookie 与人工 GUI 交互,自动化未执行。步骤与预期:

### B1. 登录(cookie 粘贴)

1. 浏览器打开 `https://leetcode.cn`(或 leetcode.com)并登录
2. DevTools → Network → 任意请求 → 复制完整 Cookie 请求头(必须含 `LEETCODE_SESSION`)
3. IDE 中打开任一 challenge 文件 → 右键 → Submit to OJ(未登录时自动触发 cookie 对话框)
   - 预期:弹出「登录 leetcode.cn」对话框,说明文案 + Cookie 粘贴多行框;空粘贴被校验拦截
4. 粘贴 cookie → OK
   - 预后:提交继续;cookie 经校验后持久化(PasswordSafe),重启 IDE 免再登

### B2. 查题列表

1. 左侧 CodeQuest 工具窗口 → Problems 标签
   - 预期:平台下拉含 LeetCode/力扣;CN 站未登录即可出真实列表(COM 站需先 B1 登录)
2. 搜索关键字/难度筛选、翻页(hasMore)
   - 预期:列表刷新,数据来自 `/api/problems` 并落库

### B3. 点题开题面

1. 列表点任一题 → 预期:右侧「CodeQuest Problem View」工具窗口打开题面(KaTeX/hljs 渲染,LC HTML 清洗)
2. 再次打开同一题 → 预期:命中本地 problem_content 缓存(断网或秒开)

### B4. 创建 challenge

1. 题面/列表页 → 创建 challenge → 选语言(如 Kotlin/Java/Python)
   - 预期:生成 challenge 工作区(入口文件含 begin/end 提交区域标记)
2. 多解法:新增第二个 solution;测试用例面板增删改用例

### B5. 写代码并提交

1. 在 begin/end 标记之间写解法
2. 编辑器右键 → Submit to OJ
   - 预期:进度通知「正在提交 → 评测中」;未登录/登录失效时自动弹 B1 对话框并重试一次
3. 等待终态
   - 预期:AC 绿色气球 / 失败红色气球(i18n 文案);AC 与 WA/CE 等判定正确

### B6. submissions 页显示

1. 底部「CodeQuest Submissions」工具窗口
   - 预期:提交完成事件(`submission:updated` 桥推送)后列表自动出现新记录(判定/语言/时间),无需手动刷新

### B7. 落库回看(SQLite 查验)

1. 打开数据库文件(默认 `~/.codequest/codequest.db`,或设置面板指定目录)
2. SQL 查验:
   ```sql
   SELECT remote_submission_id, language, verdict, submitted_at FROM submission ORDER BY submitted_at DESC LIMIT 5;
   SELECT length(code_snapshot) FROM submission ORDER BY submitted_at DESC LIMIT 1;  -- 代码快照非空
   ```
   - 预期:有刚提交的记录;verdict 与气球一致;code_snapshot 完整(回看不依赖工作区文件)
3. 重启 IDE → submissions 页回看历史仍在

### B8. 设置面板(WP15 修复目检)

1. Settings → Tools → CodeQuest
   - 预期:「数据库文件夹」注释为纯文本说明(默认目录 + codequest.db 说明),无 HTML 标签裸露
2. 打开面板期间观察 idea.log
   - 预期:无新增 `UiDslException` SEVERE(修复前同一操作产生 42 处,见 A9)
3. 修改数据库文件夹为一个新绝对路径 → OK → 重启
   - 预期:新目录生成新的 codequest.db(旧数据不迁移,符合文案)

## C. 已知限制(如实)

- leetcode.com 题目列表 GraphQL 未登录返回 401(平台侧收紧),COM 站查列表前需完成 B1;错误以 `auth-expired` JSON 正常返回,属预期行为而非插件缺陷
- 提交轮询的「反爬/限流」路径(429/AntiBot)在 LeetCode 上难以稳定复现,由 WP17/18 单测覆盖(MockEngine),真机不做强制验收
