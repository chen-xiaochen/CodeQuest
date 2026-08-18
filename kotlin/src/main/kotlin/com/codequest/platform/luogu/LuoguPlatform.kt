package com.codequest.platform.luogu

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
import com.codequest.spi.SolutionArticleContent
import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.Flow

/**
 * 洛谷平台适配器(WP26,M4 四平台之四:题解列表):组合实现四个能力接口
 * (auth / problem / submission / solutions),按服务拆分委托,结构照
 * LeetCode/CodeForces/AtCoder 样板。
 *
 * 不实现 ContestCapable(spec 能力矩阵洛谷无竞赛消费;YAGNI)。
 */
class LuoguPlatform(
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    authPrompt: OJAuthPrompt = OJAuthPrompt { null },
) : OJPlatform,
    AuthCapable,
    ProblemQueryCapable,
    SubmissionCapable,
    SolutionViewCapable {
    private val api = LuoguApi(transport)
    private val auth = LuoguAuth(api, transport, credentialStore, authPrompt)
    private val problemService = LuoguProblemService(api)
    private val submissionService = LuoguSubmissionService(api)
    private val solutions = LuoguSolutions(api)

    override val id: PlatformId = LUOGU_PLATFORM_ID
    override val domain: String = LUOGU_DOMAIN
    override val info: PlatformInfo =
        PlatformInfo(
            displayName = "洛谷 Luogu",
            iconPath = null,
            supportedLanguages = LuoguLanguages.ALL,
        )

    // 显式委托(而非 `by` 委托):避免超类列表委托表达式在属性初始化前求值的顺序坑

    override suspend fun ensureLoggedIn(): LoginSession = auth.ensureLoggedIn()

    override fun isLoggedIn(): Boolean = auth.isLoggedIn()

    override suspend fun logout() = auth.logout()

    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> = problemService.queryProblems(filter)

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement = problemService.fetchProblemDetail(remoteId)

    override suspend fun submit(request: SubmitRequest): SubmissionHandle = submissionService.submit(request)

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = submissionService.watchSubmission(handle)

    override suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef> = solutions.fetchSolutions(remoteId)

    override suspend fun fetchSolutionContent(
        remoteId: String,
        solutionId: String,
    ): SolutionArticleContent = solutions.fetchSolutionContent(remoteId, solutionId)
}

/** 洛谷注册(plugin.xml `<ojPlatformProvider implementation=.../>`)。 */
class LuoguProvider : OJPlatformProvider {
    override fun create(): LuoguPlatform =
        LuoguPlatform(
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}
