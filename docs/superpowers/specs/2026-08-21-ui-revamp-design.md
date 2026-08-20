# UI 重构：面板精简 · 平台登录门控 · 模版风格化 · 界面中文化

- 日期：2026-08-21
- 状态：plan
- 来源：用户五项优化需求（面板冗余评估 / 界面中文 / 平台未登录隐藏 / 代码模版门控与帮助 / 分平台模版）+ 澄清问答
- 前置：基于 1.1.0（M7 收官）代码现状

## 1. 背景与目标

CodeQuest 插件现状：IDE 内 4 个工具窗口（主侧栏含 Problems/Contest/Statistics/Search 四 tab、题面、提交记录、挑战工作区）；6 个平台（力扣中国/国际、CodeForces、AtCoder、洛谷、HackerRank）的平台下拉无条件全部显示；代码模版仅按语言覆盖、内置默认兜底；Kotlin 侧 bundle 双语但大量硬编码英文，前端 6 个 Vue 页面几乎全英文。

本次重构四项目标：

1. **面板精简**（方案 A「浏览/工作分离」）：工具窗 4→3、主侧栏 tab 4→3
2. **平台登录门控**：未登录平台不出现在平台下拉；全未登录回落力扣中国 + 提示；账号区常驻全部平台、显示用户名、显式登录/退出
3. **模版风格化**：ACM 版 / LeetCode 版两种风格×语言，显式设置才可用，默认 Java，设置页带帮助
4. **界面中文化**：Kotlin 硬编码文案入 bundle，前端 Vue 页面直接中文化

## 2. 非目标

- 不做平台匿名浏览模式开关（门控为固定行为，无设置项）
- 不做多平台独立模版（仅 ACM/LeetCode 两风格，平台映射到风格）
- 前端不做双语/语言切换（直接中文化）
- 不改提交链路判题、题面渲染、统计口径等既有业务逻辑
- 内置默认模板仍保留，但**只作设置页草稿起点与帮助示例**，运行时不兜底

## 3. 设计 · 面板结构重组（方案 A）

### 3.1 目标形态

```
主侧栏 CodeQuest（左）                编辑器侧
├─ Tab1 题库（route: problems）      ├─ 工具窗 CodeQuest 题面（不变）
│   页内子视图切换「平台题库｜本地搜索」├─ 工具窗 CodeQuest 挑战
├─ Tab2 竞赛（route: contest）       │   JBTabbedPane:
└─ Tab3 统计（route: stats）         │   ├─ Tab 工作区（现有 Swing 面板）
                                     │   └─ Tab 提交记录（JCEF /submissions/）
```

- 工具窗口 4→3：主侧栏、题面、挑战（提交记录并入挑战窗）
- 主侧栏 tab 4→3：题库、竞赛、统计（Search 不再单列）

### 3.2 组件与数据流

- `CodeQuestToolWindowFactory.PANELS` 4→3 项，标题走 PluginBundle（见 §6）
- **Search 合并**：`search/App.vue` 列表逻辑抽为 `SearchPanel.vue` 组件；`problems/App.vue` 顶部加子视图切换（`平台题库 | 本地搜索`）。`/search/` 路由与页面保留（落地页入口仍在），仅主侧栏不单列 tab
- **提交记录并入挑战窗**：`ChallengeWorkspacePanel` 外层包 `JBTabbedPane`——「工作区」tab = 现有 Swing 面板；「提交记录」tab = JCEF 加载 `/submissions/?challengeId=<当前选中挑战>`，challengeId 随选中挑战联动刷新；删除 `SubmissionsToolWindowFactory` 及 plugin.xml 注册
- `SubmissionCoordinator` 提交完成后的导航：从「打开提交记录工具窗」改为「激活挑战工具窗 → 提交记录 tab」（复用现有 challengeId 定向过滤）
- JCEF 不可用时提交记录 tab 降级为中文提示面板（复用 `JcefToolWindowSupport` 现有机制）

## 4. 设计 · 平台登录门控与账号区

### 4.1 门控规则（固定行为，无设置开关）

作用于主侧栏题库 tab 与竞赛 tab 的**平台选择器**；题面打开、一键开刷、提交链路不受影响（登录校验仍由 `ensureLoggedIn()` 兜底，SPI 契约不变）。

1. 平台下拉选项 = `fetchPlatforms()` ⋈ `fetchAuthStatus()`，仅保留 `loggedIn=true`
2. 过滤后为空 → 默认显示**力扣中国（leetcode.cn）**，下拉旁提示条：「当前无已登录平台，默认展示力扣中国；在上方账号区登录后即可切换其他平台」
3. 竞赛 tab 平台列表（`/api/contests` 无参返回的 ContestCapable 平台）同规则过滤；全未登录时列表区显示引导文案（竞赛必须选平台才拉数据）
4. 后端 `/api/platforms`、`/api/auth/status` 响应结构不变（除 §4.3 userId），过滤在前端做

### 4.2 SPI 扩展（用户名出口）

- `AuthCapable` 新增 `fun currentSession(): LoginSession?`——同步只读会话快照（各实现返回 `session?.takeIf { verified }`），供 UI 展示用户名；未登录返回 null
- `AuthCapable` 新增 `suspend fun restoreSession(): LoginSession?`——**静默启动恢复**：只尝试已存凭据注入 + 校验，绝不弹窗，失败返回 null。解决 IDE 重启后 `verified=false`、已登录平台全部从下拉消失的问题

### 4.3 后端

- `GET /api/auth/status`：`userId` 从恒 null 改为透传 `currentSession()?.userId`
- 新增 `POST /api/auth/refresh`：对所有 AuthCapable 平台**并发**跑 `restoreSession()`（网络校验，可能数秒），返回与 status 同构结果

### 4.4 账号区重设计（题库 tab 顶部，全部 6 平台常驻）

```
┌──────────────────────────────────────────┐
│ 力扣中国 ✅ chenchen   [退出]              │
│ CodeForces ✅ cf_user  [退出]              │
│ AtCoder    未登录      [登录]              │
│ 洛谷 / HackerRank / 力扣国际 …             │
└──────────────────────────────────────────┘
```

- 已登录：平台名 + 用户名 + 显式**退出**按钮（替代现在「点击整行切换」）
- 未登录：平台名 + **登录**按钮（触发既有 `auth:login` → `ensureLoggedIn` 交互流程）
- 登录/登出结果事件（`auth:login-result` / `auth:logout-result`）后重拉 status + platforms，平台下拉即时增减

### 4.5 页面加载时序

先 `GET /api/auth/status`（瞬时渲染）→ 后台 `POST /api/auth/refresh`（恢复重启前登录态）→ 用返回结果刷新下拉与账号区。

## 5. 设计 · 代码模版体系（风格×语言，显式启用）

### 5.1 核心模型

- `core.workspace.TemplateStyle` 枚举：`ACM`（stdin/stdout 竞赛版）/ `LEETCODE`（Solution 函数版）
- `spi.PlatformInfo` 增加 `templateStyle: TemplateStyle` 字段，各平台声明：力扣中国/国际 → LEETCODE；CodeForces/AtCoder/洛谷/HackerRank → ACM
- 存储键：`"风格:语言"`（如 `acm:java`、`leetcode:python3`）；`CodeQuestSettings.State.templates` 值结构不变、键格式升级

### 5.2 显式启用语义（关键变更）

- **设置里保存过 = 启用**：持久化 map 有非空键 → 该 (风格，语言) 可用；**运行时不再有内置兜底**
- **空白 = 未配置** → 该语言在该风格的所有平台不出现
- 设置页编辑器仍预填该风格内置默认文本作为起点（新用户打开 → 点「应用」即启用，一步完成）
- 「恢复默认」= 编辑器文本重置为内置默认文本（不改变启用状态）；要禁用某语言 → 清空文本后应用

### 5.3 内置默认模板按风格分化

- `ChallengeTemplates.entryTemplate(style, languageId)`：
  - LEETCODE Java/Kotlin = 现状（Solution 类 + main 调试桩）
  - ACM Java = BufferedReader stdin 版；ACM Kotlin = readLine 版；Python/C++/Go 按风格给对应默认
- 内置默认只作设置页草稿起点与帮助示例
- `regionMarkers` / 提交区域标记两行文案**不变**（提交提取链路依赖，勿改）

### 5.4 语言可用性规则

- 纯函数 `TemplateAvailability.usableLanguages(style, persistedTemplates)`：`WorkspaceLanguage.ALL` 中已配置该风格模版者
- `NewChallengeDialog`：选定平台 → 语言下拉 = 该平台风格的可用语言；**默认选 Java**（Java 无模版时选第一个可用）；列表为空 → 显示「请先在设置中配置代码模版」+「打开设置」按钮，禁止创建
- 题面一键开刷（`ChallengeCreateBridge`）：Java 优先 → 第一个可用语言 → 全无则回错误并引导去设置
- `createChallenge` 落库 `template` 由 ui 层**必传**（core 服务签名保留默认参数，既有测试不破）
- 多解法「生成时读当前设置」（修复#45 语义）改为读当前 (风格，语言) 模版，无则用落库模板

### 5.5 设置页 UI

```
代码模板
风格: [ACM 竞赛版 ▼]  语言: [Java ▼]  [? 帮助]
模板: <编辑器预填该风格默认文本>
[恢复默认文本]    (空白=该语言不可用，修改后点「应用」生效)
```

- `TemplateDraftsState` 扩展为 (风格，语言) 双键
- **帮助对话框**内容（走 PluginBundle 双语）：① 变量表（`challenge.*` 六个变量、`velocity.*` 工具、`beginRegion`/`endRegion`）② 提交区域标记说明（两行标记必须保留，标记之间代码才会被提交）③ 文件名规则 ④ 风格说明（哪些平台用 ACM、哪些用 LeetCode）

### 5.6 迁移

`loadState` 检测旧格式键（无冒号 = 纯语言键）→ **双写**到 `acm:L` 与 `leetcode:L`（老用户配置不丢，可再分别改）；一次性归一化，迁移后 state 即新格式。

## 6. 设计 · 界面中文化

### 6.1 Kotlin 侧（PluginBundle 双语：中文 IDE 全中文、英文 IDE 英文）

- 主侧栏 3 个 tab 标题/描述 + JCEF 降级文案 → bundle（题库/竞赛/统计）
- `ChallengeWorkspacePanel` 全部文案（约 25 条：Challenge/刷新/新建挑战/解法组/新建解法/打开文件/保存笔记/删除/测试用例/导入/全部运行/输入/期望输出/运行输出 + 对话框文案 + 运行结果 PASSED/FAILED/汇总）
- `NewChallengeDialog` / `ImportTestCasesDialog` → bundle
- plugin.xml 工具窗 displayName：「CodeQuest」「CodeQuest 题面」「CodeQuest 挑战」（提交记录窗移除）
- 面向用户展示的错误串（如 AuthLoginBridge 的 unknown platform）中文化
- 新 key 英中两文件同步补齐

### 6.2 前端 6 页（直接中文化，不做双语）

- 题库页：账号区（用户名/登录/退出）、筛选（平台/难度/关键字/标签/公司）、按钮与空态
- 本地搜索子视图：标签与空态
- 竞赛页：`PHASE_LABELS`（未开始/进行中/等待系统测试/系统测试中/已结束）、提醒订阅文案
- 统计页：汇总卡片（总提交/通过数/AC 率/当前连续/最长连续）、热力图/趋势标题
- 题面页：一键开刷/登录/题解排序/缩放工具栏
- 提交记录页：输入/期望输出/实际输出、详情/代码 tab、判定文案（`verdict.ts` 与 Kotlin bundle 对齐）
- 落地页：入口标签对齐新结构（题库/竞赛/统计/题面/提交记录；本地搜索 `/search/` 路由保留，入口照列）

## 7. 测试

- `TemplateStyle` 平台映射单测（6 平台）
- `TemplateDraftsState` 双键版单测（含旧 initial 双写迁移语义）
- `TemplateAvailability.usableLanguages` 单测（含空列表、Java 优先）
- `ChallengeTemplates.entryTemplate(style, lang)` 各风格快照单测
- `CodeQuestSettings` 旧 state 键迁移单测
- `ChallengeTemplateWiringTest` / `AuthLoginBridgeTest` 等既有测试适配更新
- 前端 `vue-tsc` + build 通过；Kotlin 全量单测通过

## 8. 验收清单（手动）

1. 中文 IDE 下：主侧栏 3 tab、题面窗、挑战窗（含提交记录 tab）、设置页、各对话框无英文残留
2. 平台下拉仅显示已登录平台；全部登出后回落力扣中国 + 提示条；账号区登录新平台后下拉即时出现
3. 账号区显示用户名；退出后平台从下拉消失
4. IDE 重启后已登录平台仍显示（静默恢复生效）
5. 未配置任何模版时新建挑战被拦截并引导去设置；配置 Java 后默认语言为 Java；某语言无模版不出现在语言下拉
6. 老用户升级：既有语言模版出现在两个风格下
7. 提交后焦点跳挑战窗提交记录 tab 并按 challengeId 过滤
8. Search 功能可从题库页「本地搜索」子视图访问

## 9. 风险与对策

- **风险**：`restoreSession` 并发网络校验在弱网下拖慢页面首次渲染 → 对策：`/api/auth/refresh` 与首屏渲染解耦（status 先行渲染），refresh 完成后增量刷新
- **风险**：旧模版键迁移双写导致用户误以为配置翻倍 → 对策：设置页按风格分开展示，用户可分别调整；迁移一次性
- **风险**：挑战面板变 Swing+JCEF 混合 tab 的焦点/生命周期问题 → 对策：JCEF 组件复用 `JcefToolWindowSupport`，随 tab 销毁释放；提交记录 tab 惰性创建（首次切换才加载）
