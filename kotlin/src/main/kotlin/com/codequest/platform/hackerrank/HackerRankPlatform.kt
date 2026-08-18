package com.codequest.platform.hackerrank

import com.codequest.core.model.PlatformId
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.AuthCapable
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
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.Flow

/**
 * HackerRank 平台适配器(WP27,M4 收官平台):组合实现三个能力接口
 * (auth / problem / submission),按服务拆分委托,结构照
 * LeetCode/CodeForces/AtCoder/洛谷样板。
 *
 * 不实现 ContestCapable 与 SolutionViewCapable(spec 能力矩阵 HackerRank 无竞赛/
 * 题解消费;YAGNI)。
 */
class HackerRankPlatform(
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    authPrompt: OJAuthPrompt = OJAuthPrompt { null },
) : OJPlatform,
    AuthCapable,
    ProblemQueryCapable,
    SubmissionCapable {
    private val api = HackerRankApi(transport)
    private val auth = HackerRankAuth(api, transport, credentialStore, authPrompt)
    private val problemService = HackerRankProblemService(api)
    private val submissionService = HackerRankSubmissionService(api)

    override val id: PlatformId = HACKERRANK_PLATFORM_ID
    override val domain: String = HACKERRANK_DOMAIN
    override val info: PlatformInfo =
        PlatformInfo(
            displayName = "HackerRank",
            iconPath = null,
            supportedLanguages = HackerRankLanguages.ALL,
        )

    // 显式委托(而非 `by` 委托):避免超类列表委托表达式在属性初始化前求值的顺序坑

    override suspend fun ensureLoggedIn(): LoginSession = auth.ensureLoggedIn()

    override fun isLoggedIn(): Boolean = auth.isLoggedIn()

    override suspend fun logout() = auth.logout()

    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> = problemService.queryProblems(filter)

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement = problemService.fetchProblemDetail(remoteId)

    override suspend fun submit(request: SubmitRequest): SubmissionHandle = submissionService.submit(request)

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = submissionService.watchSubmission(handle)
}

/** HackerRank 注册(plugin.xml `<ojPlatformProvider implementation=.../>`)。 */
class HackerRankProvider : OJPlatformProvider {
    override fun create(): HackerRankPlatform =
        HackerRankPlatform(
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}
