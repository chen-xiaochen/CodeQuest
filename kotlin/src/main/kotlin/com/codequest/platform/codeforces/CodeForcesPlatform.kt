package com.codequest.platform.codeforces

import com.codequest.core.model.PlatformId
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.AntiBotChallengeHandler
import com.codequest.spi.AuthCapable
import com.codequest.spi.ContestCapable
import com.codequest.spi.ContestDetail
import com.codequest.spi.ContestSummary
import com.codequest.spi.LoginSession
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformProvider
import com.codequest.spi.OJRuntime
import com.codequest.spi.OJTransport
import com.codequest.spi.PlatformInfo
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * CodeForces 平台适配器(WP24,M4 四平台之二):组合实现五个能力接口
 * (auth / problem / submission / contest),按服务拆分委托,结构照 LeetCode 样板。
 *
 * 竞赛能力使通用 `/api/contests` 端点与 contest 前端页零额外接线即覆盖 CF
 * (WP23 ContestApi 经 `OJPlatformRegistry.allWith<ContestCapable>` 发现本平台);
 * 提醒订阅经 WP23 的 ContestReminderService 落库,平台侧无新代码。
 */
class CodeForcesPlatform(
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    authPrompt: OJAuthPrompt = OJAuthPrompt { null },
    antiBotSource: () -> AntiBotChallengeHandler = OJRuntime::antiBotHandler,
    clock: () -> Instant = Instant::now,
) : OJPlatform,
    AuthCapable,
    ProblemQueryCapable,
    SubmissionCapable,
    ContestCapable,
    SubmissionDetailCapable {
    private val api = CodeForcesApi(transport)
    private val auth = CodeForcesAuth(api, transport, credentialStore, authPrompt)
    private val problemService = CodeForcesProblemService(api)
    private val submissionService = CodeForcesSubmissionService(api, antiBotSource)
    private val contestService = CodeForcesContestService(api, auth::isLoggedIn, clock)

    override val id: PlatformId = CODEFORCES_PLATFORM_ID
    override val domain: String = CODEFORCES_DOMAIN
    override val info: PlatformInfo =
        PlatformInfo(
            displayName = "CodeForces",
            iconPath = null,
            supportedLanguages = CodeForcesLanguages.ALL,
        )

    // 显式委托(而非 `by` 委托):避免超类列表委托表达式在属性初始化前求值的顺序坑

    override suspend fun ensureLoggedIn(): LoginSession = auth.ensureLoggedIn()

    override fun isLoggedIn(): Boolean = auth.isLoggedIn()

    override suspend fun logout() = auth.logout()

    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> = problemService.queryProblems(filter)

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement = problemService.fetchProblemDetail(remoteId)

    override suspend fun submit(request: SubmitRequest): SubmissionHandle = submissionService.submit(request)

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = submissionService.watchSubmission(handle)

    override suspend fun fetchSubmissionDetail(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): SubmissionDetail? = submissionService.fetchSubmissionDetail(remoteSubmissionId, problemRemoteId)

    override fun submissionViewUrl(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): String? = submissionService.submissionViewUrl(remoteSubmissionId, problemRemoteId)

    override fun contests(): Flow<ContestSummary> = contestService.contests()

    override suspend fun contestDetail(remoteId: String): ContestDetail = contestService.contestDetail(remoteId)
}

/** codeforces 注册(plugin.xml `<ojPlatformProvider implementation=.../>`)。 */
class CodeForcesProvider : OJPlatformProvider {
    override fun create(): CodeForcesPlatform =
        CodeForcesPlatform(
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}
