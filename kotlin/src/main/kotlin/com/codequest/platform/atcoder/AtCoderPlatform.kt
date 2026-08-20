package com.codequest.platform.atcoder

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
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.Flow

/**
 * AtCoder 平台适配器(WP25,M4 四平台之三):组合实现三个能力接口
 * (auth / problem / submission),按服务拆分委托,结构照 LeetCode/CodeForces 样板。
 *
 * 不实现 ContestCapable(spec 能力矩阵 AtCoder 无竞赛消费,题目/提交才是主链路;
 * kenkoooo contests.json 未接入,需要时再增量)。
 */
class AtCoderPlatform(
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    authPrompt: OJAuthPrompt = OJAuthPrompt { null },
) : OJPlatform,
    AuthCapable,
    ProblemQueryCapable,
    SubmissionCapable,
    SubmissionDetailCapable {
    private val api = AtCoderApi(transport)
    private val auth = AtCoderAuth(api, transport, credentialStore, authPrompt)
    private val problemService = AtCoderProblemService(api)
    private val submissionService = AtCoderSubmissionService(api)

    override val id: PlatformId = ATCODER_PLATFORM_ID
    override val domain: String = ATCODER_DOMAIN
    override val info: PlatformInfo =
        PlatformInfo(
            displayName = "AtCoder",
            iconPath = null,
            supportedLanguages = AtCoderLanguages.ALL,
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
}

/** atcoder 注册(plugin.xml `<ojPlatformProvider implementation=.../>`)。 */
class AtCoderProvider : OJPlatformProvider {
    override fun create(): AtCoderPlatform =
        AtCoderPlatform(
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}
