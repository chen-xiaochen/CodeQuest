# OJ SPI 实证验证结论(M3 / WP22;M4 量产追加见 §6)

- 日期:2026-08-16
- 实证对象:LeetCode / 力扣CN 适配器(WP19,commit `30b0e5d`)
- 对照标准:spec `docs/superpowers/specs/2026-08-14-codequest-kotlin-rewrite-design.md` §2 与成功标准 1

## 1. 新增一个 OJ 需要写的文件清单

以 WP19 diff 为准——新增 LeetCode 双站点适配器时,生产的全部改动如下。

### platform/leetcode/ 包内(9 个源文件,约 1081 行)

| 文件 | 行数 | 职责 | 实现的 SPI 能力 |
|---|---|---|---|
| `LeetCodeVariant.kt` | 47 | 平台身份与站点差异:双站点 id/domain/显示名/端点 URL | (OJPlatform 身份数据) |
| `LeetCodeGraphQL.kt` | 159 | GraphQL 查询语句常量 | (Api 支撑) |
| `LeetCodeApi.kt` | 454 | 纯 HTTP 映射层:请求构造/响应解析,不依赖 IntelliJ | (各服务支撑,平台最重的部分) |
| `LeetCodeAuth.kt` | 108 | cookie 粘贴登录 + globalData 校验 + 凭据持久化 | `AuthCapable` |
| `LeetCodeProblemService.kt` | 60 | 列表分页游走(skip/limit 内部消化)+ 题面拉取 | `ProblemQueryCapable` |
| `LeetCodeSubmissionService.kt` | 95 | 提交;轮询只实现 `fetchRemoteStatus()` 钩子 | `SubmissionCapable`(消费 `BaseSubmissionFlow`) |
| `LeetCodeSolutions.kt` | 25 | 官方题解列表引用 | `SolutionViewCapable` |
| `LeetCodeLanguages.kt` | 42 | 平台语言 id ↔ 工作区语言映射表 | (数据) |
| `LeetCodePlatform.kt` | 91 | 装配根:组合实现 4 个能力接口并显式委托各服务;含 `LeetCodeProvider` / `LeetCodeCnProvider` | `OJPlatformProvider` × 2 |

### plugin.xml:每个站点一行

```xml
<ojPlatformProvider implementation="com.codequest.platform.leetcode.LeetCodeProvider"/>
<ojPlatformProvider implementation="com.codequest.platform.leetcode.LeetCodeCnProvider"/>
```

### 测试(不进产物,platform/leetcode 测试包 6 个文件)

`FakeOJTransport.kt`(假传输回放夹具)+ `LeetCodeApiTest` / `LeetCodeAuthTest` / `LeetCodeLanguagesTest` / `LeetCodePlatformTest` / `LeetCodeProblemServiceTest` / `LeetCodeSubmissionServiceTest`(MockEngine 风格,不上真实网络)。

## 2. 不需要碰的层(git diff 佐证)

| 工作包 | commit | core | spi | infra | ui | platform |
|---|---|---|---|---|---|---|
| WP19 新增 LeetCode/CN | `30b0e5d` | **0 改动** | +`OJTransport.kt`(见 §4.1) | +`HttpOJTransport.kt` / `BackedOJCredentialStore.kt`(SPI 生产实现) | **0 改动** | +leetcode 包 9 文件 |
| WP20 题面/列表端点 | `dad53dd` | 0 改动 | 0 改动 | +`ProblemApi` 等端点 | +toolwindow 接线 | **0 改动** |
| WP21 提交流态 | `7677ba8` | 0 改动 | 0 改动 | +`SubmissionsApi` | +submission 包 | **0 改动** |

最有力的证据是后两行:**WP20/WP21 在 SPI 之上为统一前端加数据端点、提交流态、通知时,platform/leetcode 包零改动**——平台差异被 SPI 完全消化,这正是重写的第一驱动力(「加新 OJ 太痛苦」)要消灭的问题。

分层强制不靠自觉:`checkLayerDependencies` 门禁(WP10)静态扫描 import,禁止 `platform → infra/ui`、`platform/<ojA> → platform/<ojB>`、`core → 任何外层`;`./gradlew check` 每次构建执行(本轮实测输出「分层依赖检查通过」)。

## 3. SPI 运行机制(为什么 core/ui 不需要知道新平台)

- **注册**:`OJPlatformRegistry`(EP `com.codequest.ojPlatformProvider`)→ `find(id)` / `allWith<T>()`;插件加载即注册,无代码级枚举
- **能力查询**:题目端点经 `allWith(ProblemQueryCapable::class)` 发现数据源;提交链路按 `as? SubmissionCapable` 过滤——加平台即自动出现在 `/api/platforms` 与提交流态
- **轮询复用**:`BaseSubmissionFlow` 承担指数退避/间隔封顶/超时,平台只写单次 `fetchRemoteStatus()`
- **传输/凭据**:`OJTransport` / `OJCredentialStore` 接口在 spi,生产实现在 infra(EP `ojTransportProvider` / `ojCredentialStoreProvider` 注入),平台包全程不 import infra

## 4. 偏差与遗留(诚实记录)

### 4.1 WP19 曾新增 spi/OJTransport.kt(一次性 SPI 缺口补齐)

WP17 定义的 SPI 只有平台能力接口;首次真实接入(WP19)暴露「平台适配器要发 HTTP/存凭据,但 platform 禁止 import infra」的缺口,故补齐传输/凭据两个注入接口与 infra 生产实现。这是 SPI 自身的一次性演进,不属于 LeetCode 特有逻辑;**此后新增 OJ 不再需要触碰 spi/infra**(以 WP20/21 diff 佐证)。

### 4.2 ui/submission/SubmissionPlatforms.kt 存在一处按平台 id 的 when 分发

```kotlin
when (platformId.value) {
    LeetCodeVariant.LEETCODE.id.value -> leetCode(project, LeetCodeVariant.LEETCODE)
    LeetCodeVariant.LEETCODE_CN.id.value -> leetCode(project, LeetCodeVariant.LEETCODE_CN)
    else -> OJPlatformRegistry.find(platformId)
}
```

原因:注册表经 provider `create()` 产出实例,无法回填交互式登录 UI 回调(`LeetCodeAuth.CookiePrompt`);而重登流程需要真实「cookie 粘贴对话框」。故 LeetCode 系在 UI 层按 variant 组装并注入 `UiCookiePrompt`。

影响评估:
- 新增 OJ 若登录形态不是「cookie 粘贴对话框」(如 CodeForces 账密表单、AtCoder Turnstile JCEF):走 `else` 分支,零改动;
- 若是同形态:需在 `SubmissionPlatforms` 加一个组装分支(约 3 行)——不满足字面意义的「core/ui 零改动」。

**建议**(M4 铺开前):把「交互登录回调」抽象为独立 EP 或 SPI 上下文参数,消除这处 match,使 spec「不存在任何枚举 match 分发」完全成立。

### 4.3 spec §2 接口签名与实际实现的差异

| spec §2 签名 | 实际实现 | 差异原因 |
|---|---|---|
| `fetchProblemDetail(id: ProblemId): ProblemDetail` | `fetchProblemDetail(remoteId: String): ProblemStatement` | `ProblemId` 是本地库主档 id(平台不认识),`ProblemDetail` 是本地缓存记录(以本地 id 为主键);平台只认 remoteId、只应产出平台侧原文,映射归用例层落库(见 `ProblemQueryCapable.kt` KDoc) |
| `ContestCapable`(contests/contestDetail) | 未实现(M3 无竞赛平台) | 能力矩阵中 Contest 归 CodeForces(M4);spi 包暂无此接口,接口位待 M4 首接时定义 |
| `fetchSolutions(id): List<SolutionArticle>` | `fetchSolutions(remoteId): List<SolutionArticleRef>` | M3 只交付题解引用列表;正文拉取与文章页渲染留 M5(接口位已预留) |
| `AuthCapable` / `SubmissionCapable` | 与 spec 一致 | — |
| 「plugin.xml 一行」 | LeetCode 实际两行(每站点一行) | 双站点同包双 provider(leetcode + leetcodecn);单一站点仍是一行 |

## 5. 对照 spec 成功标准 1 的结论

> 成功标准 1:「新增一个 OJ 平台 = 一个包 + plugin.xml 一行声明,不需要改动 core/ui 任何代码(以真实实现衡量)」

**结论:成立(以 LeetCode/CN 真实实现衡量)。**

- 新增 LeetCode/CN:core 零改动、ui 零改动(WP19 diff §2 表);platform/leetcode 包 9 文件 + plugin.xml 2 行(双站点)
- SPI 之上扩展统一功能(WP20 端点/题面、WP21 提交流态):平台包零改动
- 两点限定如实记录:一次性 SPI 缺口补齐(§4.1,已发生且不重复);交互式登录 UI 回调注入(§4.2,M4 前建议收敛,届时该例外消除)

---

# 6. M4 量产结论(四平台批量接入实证,WP28,2026-08-18)

M4 以同一 SPI 连续接入 CodeForces / AtCoder / 洛谷 / HackerRank 四平台,是比 M3 双站点更严苛的量产检验。以下数据以 WP24-27 四个提交(`8e4da9d` / `83cb1bb`+`079bbea` / `d55eefc` / `056b402`)的 `git show --stat` 与 diff-tree 为准。

## 6.1 各平台生产改动清单

| 平台 | commit | platform/<oj> 主源 | ui 新增 | plugin.xml | 新测试 |
|---|---|---|---|---|---|
| CodeForces(WP24) | `8e4da9d` | 9 文件 1171 行(Api 354/Auth 105/Contest 127/Endpoints 93/Html 123/Languages 54/Platform 90/Problem 118/Submission 116;提交时另有 FormPostCapable 33 行,WP25 删除) | CodeForcesAuthPromptProvider 47 | +4 行 | 59 |
| AtCoder(WP25) | `83cb1bb`+`079bbea` | 8 文件 978 行(Api 171/Auth 106/Endpoints 77/Html 200/Languages 84/Platform 76/Problem 136/Submission 128) | AtCoderAuthPromptProvider 47 | +4 行 | 48 |
| 洛谷(WP26) | `d55eefc` | 9 文件 1076 行(Api 515/Auth 105/Endpoints 63/Html 79/Languages 58/Platform 82/Problem 88/Solutions 25/Submission 61) | LuoguAuthPromptProvider 47 + ExternalLinkOpener 50 | +4 行 | 51 Kotlin + 5 前端 |
| HackerRank(WP27) | `056b402` | 8 文件 1075 行(Api 422/Auth 105/Endpoints 134/Html 65/Languages 68/Platform 77/Problem 126/Submission 78) | HackerRankAuthPromptProvider 47 | +4 行 | 46 |

四平台共 34 个主源文件、4298 行,平均每平台约 8.5 文件 1075 行——与 LeetCode 单包(9 文件 1081 行)同量级,SPI 接入成本未随平台数劣化。

## 6.2 分层触碰(git diff-tree 佐证)

**core:零触碰。** WP24-27 四提交(含 WP25 首提交)的 diff-tree 中无任何 `core/` 文件——core 用例层对新平台完全无感知。

**ui:既有文件仅一处修改。**

- 四平台各新增一个 `XxxAuthPromptProvider`(47 行,同模板形态,经 `ojAuthPromptProvider` EP 声明)——新增文件,不触碰既有;
- M3 §4.2 的 `SubmissionPlatforms` when 分发已在 WP23 由 ojAuthPromptProvider EP **消灭**,四平台接入对该文件零改动(对照 M3 的预测「LeetCode 系需加组装分支」,EP 化后该例外消除);
- 唯一既有文件修改:WP26 `ui/toolwindow/ProblemViewToolWindowFactory.kt`(+21-2)——为题解列表区块接线 `nav:openUrl` 外链出口(配合新增 `ExternalLinkOpener`)。这是 SolutionViewCapable 的统一 UI 出口(SPI 之上功能),不含平台 id match;但按提交时序发生在洛谷接入 WP 内,如实记录。

**spi:一次一次性增量。** WP25 首提交(`83cb1bb`)将 `postForm` 提升进 `OJTransport`(+18 行)——CF/AtCoder 提交端点为 x-www-form-urlencoded,SPI 传输最小集缺此一项。与 WP19 补传输/凭据接口同类:**SPI 自身一次性演进,非平台特有逻辑;此后平台不再触碰 spi**。

**infra:统一功能扩展,非平台接线。**

- WP25:OJHttpClient/HttpOJTransport 同步实现 postForm(SPI 生产实现,口径同 M3 §2「infra 生产实现」);
- WP26:新增 `SolutionsApi`(183 行,`/api/problem/{id}/solutions` 透传端点)+ ResourceHttpServer 挂载路由(+38)——SolutionViewCapable 平台的统一题解列表端点,类比 WP20 为题面加端点;任何后续接入的题解平台(如需)零改动复用。

## 6.3 「plugin.xml 一行」的如实记录

实际为**每站点两行**:provider 一行 + authPrompt 一行(外加注释两行):

```xml
<ojPlatformProvider implementation="com.codequest.platform.codeforces.CodeForcesProvider"/>
<ojAuthPromptProvider implementation="com.codequest.ui.submission.CodeForcesAuthPromptProvider"/>
```

原因:交互式登录回调(粘贴 Cookie 对话框)经独立 EP 声明——这是 WP23 为消灭 M3 §4.2 when 分发付出的架构代价(每站多一行声明,换掉 ui 层代码级 match)。M3 §4.3 表中「plugin.xml 一行」条目按此修订。

## 6.4 M4 对照 spec §2 能力矩阵的偏差表(如实)

| spec §2 矩阵项 | M4 实际 | 偏差说明 |
|---|---|---|
| CodeForces Contest「列表/详情/参加/提醒」 | 列表/详情/订阅提醒(24h 气球)达成;**「参加」为 url 外链兜底** | 参加需登录 POST,平台内交互留后续迭代(contest 页点参加跳浏览器) |
| AtCoder Auth「✅(Turnstile)」 | Auth = cookie 粘贴(REVEL_SESSION);**Turnstile 位于提交通道**(anti-bot JCEF 弹窗,表单 POST 二元组) | spec 把 Turnstile 标在 Auth 列;实际 Turnstile 保护的是提交表单而非登录(AtCoder 登录本身无验证码)。两项能力均达成,标注位置与实际链路有偏差 |
| 洛谷 Solutions「✅」 | 题解列表(引用)达成;点击经系统浏览器外链 | 正文拉取/站内渲染留 M5(接口位 M3 §4.3 已预留);外链是本期如实选型 |
| LeetCode Solutions「✅」 | 引用列表(M3 交付) | 同 M3 记录,正文 M5 |
| 其余矩阵格(各平台 Auth/Problem/Submit;HR/AtCoder 无 Contest/Solutions) | 一致 | — |
| ContestCapable 接口位(M3 §4.3 记「未实现,接口位待 M4 首接时定义」) | WP23 已定义并随 CF 实现 | M3 遗留清偿;签名 `contests(): Flow<ContestSummary>` + `contestDetail(remoteId)` 与 spec §2 一致 |
| fetchProblemDetail / fetchSolutions 签名 | 沿用 M3 §4.3 的差异(remoteId + ProblemStatement / SolutionArticleRef) | 不重复展开,见 M3 §4.3 |

## 6.5 对照 spec 成功标准 1 的结论(M4 版)

> 「新增一个 OJ 平台 = 一个包 + plugin.xml 一行声明,不需要改动 core/ui 任何代码(以真实实现衡量)」

**结论:成立(以 M4 四平台量产衡量),三点限定如实记录。**

- core 四平台零触碰;ui 既有文件仅 WP26 一处统一出口修改(+21-2,非平台 match);platform 四包共 34 文件 4298 行,形态与 LeetCode 包同构;
- 限定一(一次性 SPI 增量):WP25 postForm 进 OJTransport(+18 行),此后不再重复;
- 限定二(声明成本):每站 plugin.xml 两行(provider + authPrompt),见 §6.3;
- 限定三(统一功能随平台首发):题解端点/外链出口随首个消费平台(WP26 洛谷)落地在 infra/ui——属 SPI 之上的功能扩展,后续同类平台零改动复用(与 WP20/21 端点先例同性质)。

---

# 7. M5 增强层偏差记录(WP32 集成验收,2026-08-19)

M5(统计可视化 / 跨题检索 / 题解正文)是 SPI 之上的**功能增强里程碑**:不新增平台、不扩平台矩阵,主战场在 core 聚合 + infra 端点 + 前端页。以下按 WP29/30/31/32 四提交(`c8d419b` / `89bae42` / `851c041` / WP32)如实汇总与 spec(`2026-08-14-codequest-kotlin-rewrite-design.md`)的偏差与设计取舍。

## 7.1 SPI 增量:fetchSolutionContent 签名(WP31)

spec §2 只定义了 `fetchSolutions(id: ProblemId): List<SolutionArticle>`;实际 SPI 为两级:

| 项 | 实际实现 | 取舍原因 |
|---|---|---|
| 列表签名 | `fetchSolutions(remoteId): List<SolutionArticleRef>` | M3 §4.3 已记录(remoteId 与本地主档 id 的口径差异),不重复展开 |
| **正文签名** | `fetchSolutionContent(remoteId, solutionId): SolutionArticleContent`,**两个定位参数而非 SolutionArticleRef** | REST 端点路由 `/api/problem/{remoteId}/solutions/{sid}` 只持有二元组;SolutionArticleRef 的 title/url 在列表响应里,端点若拼 ref 需二次拉列表;洛谷正文寻址需要 pid(= remoteId)而 ref 不携带。签名与路由 1:1 映射(SolutionViewCapable.kt KDoc) |
| **默认实现而非抽象方法** | 默认抛 `OJException`(502 + 「该平台未实现题解正文拉取」文案) | 正文是列表之后的增强能力;强制抽象会迫使所有 SolutionViewCapable 平台同步实现,违反能力接口「按需组合」精神。未接入平台保持可编译,调用方经标准错误映射得到明确文案 |
| 正文 DTO | `SolutionArticleContent(id, title, content, contentFormat)` | contentFormat 复用 core 既有 `ContentFormat`(HTML/MARKDOWN),交前端统一管线,不新造枚举 |

## 7.2 数据层与端点口径(设计意图,非缺陷)

| 项 | spec 表述 | 实际口径 | 说明 |
|---|---|---|---|
| 全文检索 | `problem_content` + `solution.note_md` 建 FTS5,LIKE 降级 | 一致,另有第三源**标题/remoteId LIKE** 与命中来源标记 matchedSources(title/content/note) | 标题源是 spec 未明写的自然补全;来源标记支撑前端 chips |
| 检索语义 | 「跨题检索(FTS+标签)」 | keyword 与 tags **均为空 = 浏览模式**(返回近期题目,搜索页兼作本地题库浏览);有关键字但零命中 = 空结果,**不退化浏览全表** | 浏览模式为语义增强;零命中短路是防歧义的正确性约束(ExposedSearchStore.search 首行) |
| 中文 FTS | 未细化 | 关键字 < 3 字符走 LIKE 降级(FTS5 trigram 最小长度) | 功能等价,索引效率差异如实 |
| stats 汇总口径 | 「刷题统计(热力图/趋势)」 | summary 卡片为**全量历史口径**(只受平台过滤,不随 days 窗口漂移);heatmap=days(缺省 365,1..731),trend=min(days,30) | 卡片数字稳定可预期;窗口只影响图形(StatisticsService KDoc 明示) |
| stats 前端 | Statistics 面板 | 后端 `/api/stats?platform=` 支持平台过滤;**前端 stats 页暂无平台选择器**(展示全量) | 后端能力就绪,选择器留后续;如实记录 |
| 数据源端口 | 未涉及 | core 新增 `SubmissionQuery` fun interface(全量列表),**不改既有 SubmissionRepository** | 该接口只有按 challenge 查询;以最小端口补「列全量」能力,避免牵连既有实现与测试(StatisticsService.kt KDoc) |

## 7.3 平台侧已知限制(如实)

- **洛谷题解第二页楼层不可达**:洛谷无单篇题解端点,WP31 实现复用 solution 列表页 payload 按楼层 id 筛 content,列表第二页及以后的楼层取不到正文——已知限制,登录后翻页验证步骤见 m5-manual-checklist B3-6。
- **题解正文不落库**:纯透传(体积数十 KB/篇、平台侧随时编辑、按需低频),重复查看重复请求上游为预期;离线回看需求出现再引入 problem_content 同款缓存表(YAGNI,SolutionsApi.kt KDoc)。WP32 沙箱以计数复核(A13)。
- **leetcode.com 题解匿名 401**(站点要求登录);**力扣CN 匿名可用**(WP32 沙箱实测列表+公开正文 200);**LC 会员文章正文 502** 如实报错(平台侧付费墙,不绕过)。
- AtCoder/CodeForces/HackerRank 不实现 SolutionViewCapable(与 spec 能力矩阵一致),题解端点对它们 404 unknown-platform。

## 7.4 分层触碰(M5 三提交 git diff-tree 汇总)

| 层 | WP29 统计 | WP30 检索 | WP31 题解正文 |
|---|---|---|---|
| core | +StatisticsService(+SubmissionQuery 端口) | 0 | 0 |
| spi | 0 | 0 | +SolutionViewCapable 增量(默认方法)+SolutionArticleContent |
| platform | 0 | 0 | leetcode(+208)/luogu(+71)正文实现(能力增强,不改既有签名) |
| infra(db) | 0 | +SearchStore/ExposedSearchStore(+9 DatabaseManager) | 0 |
| infra(messaging) | +StatisticsApi(路由挂载) | +SearchApi(路由挂载) | +SolutionsApi 正文路由(ResourceHttpServer +80) |
| frontend | +stats 页(stats-api.ts+App.vue) | +search 页真实化 | +problem-view 应用内阅读器 |

要点:M5 主体落在 **core 用例 + infra 端点 + 前端页**,平台包仅洛谷/力扣系为既有能力补正文实现,未新增平台、未触碰六平台注册与提交通道——「SPI 之上统一功能,平台包按能力接口按需实现」的分层在增强类迭代中同样成立。题解正文对未接入平台零破坏(默认实现,7.1)。

## 7.5 对照 spec 成功标准的结论(M5 版)

spec §5 测试要求「统计聚合、检索逻辑单测全覆盖」:达成(StatisticsServiceTest 11 例 + StatisticsApiHttpTest 6 例 + SearchApiHttpTest 真实 server/临时库 + 洛谷/LC 正文解析 Mock 单测;全量 517 Kotlin + 95 Vitest,WP32 复跑全绿)。spec §3 前端六路由页(含 /stats /search)全部真实化入包(WP32 产物核验)。
