package com.codequest.platform.leetcode

import com.codequest.core.model.PlatformId
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
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
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.Flow

/**
 * LeetCode 平台适配器(WP19):同一实现承载 leetcode.com / leetcode.cn 两变体
 * (身份差异见 [LeetCodeVariant]),组合实现五个能力接口并按服务拆分委托:
 * auth / problem / submission / solutions / submission detail(WP36)。
 */
class LeetCodePlatform(
    private val variant: LeetCodeVariant,
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    cookiePrompt: LeetCodeAuth.CookiePrompt = LeetCodeAuth.CookiePrompt { null },
) : OJPlatform,
    AuthCapable,
    ProblemQueryCapable,
    SubmissionCapable,
    SolutionViewCapable,
    SubmissionDetailCapable {
    private val api = LeetCodeApi(variant, transport)
    private val auth = LeetCodeAuth(variant, api, transport, credentialStore, cookiePrompt)
    private val problemService = LeetCodeProblemService(variant, api)
    private val submissionService = LeetCodeSubmissionService(variant, api)
    private val solutions = LeetCodeSolutions(variant, api)

    override val id: PlatformId = variant.id
    override val domain: String = variant.domain
    override val info: PlatformInfo =
        PlatformInfo(
            displayName = variant.displayName,
            iconPath = null,
            supportedLanguages = LeetCodeLanguages.ALL,
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

    override suspend fun fetchSubmissionDetail(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): SubmissionDetail = submissionService.fetchSubmissionDetail(remoteSubmissionId, problemRemoteId)

    override fun submissionViewUrl(
        remoteSubmissionId: String,
        problemRemoteId: String,
    ): String? = submissionService.submissionViewUrl(remoteSubmissionId, problemRemoteId)
}

/** leetcode.com 注册(plugin.xml `<ojPlatformProvider implementation=.../>`)。 */
class LeetCodeProvider : OJPlatformProvider {
    override fun create(): LeetCodePlatform =
        LeetCodePlatform(
            variant = LeetCodeVariant.LEETCODE,
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}

/** leetcode.cn 注册。 */
class LeetCodeCnProvider : OJPlatformProvider {
    override fun create(): LeetCodePlatform =
        LeetCodePlatform(
            variant = LeetCodeVariant.LEETCODE_CN,
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}
