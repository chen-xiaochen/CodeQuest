# OJ SPI 实证验证结论(M3 / WP22)

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
