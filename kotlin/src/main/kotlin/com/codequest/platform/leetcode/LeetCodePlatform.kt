package com.codequest.platform.leetcode

import com.codequest.core.model.PlatformId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.workspace.TemplateStyle
import com.codequest.spi.AuthCapable
import com.codequest.spi.CompanyProblemPage
import com.codequest.spi.CompanyProblemQuery
import com.codequest.spi.CompanyQueryCapable
import com.codequest.spi.CompanyTag
import com.codequest.spi.ContestCapable
import com.codequest.spi.ContestDetail
import com.codequest.spi.ContestSummary
import com.codequest.spi.DailyQuestion
import com.codequest.spi.DailyQuestionCapable
import com.codequest.spi.FavoriteList
import com.codequest.spi.FavoriteProblemPage
import com.codequest.spi.FavoriteQueryCapable
import com.codequest.spi.FavoriteWriteCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformProvider
import com.codequest.spi.OJRuntime
import com.codequest.spi.OJTransport
import com.codequest.spi.PlatformInfo
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemHintsCapable
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import com.codequest.spi.RemoteSubmission
import com.codequest.spi.RemoteSubmissionPage
import com.codequest.spi.SolutionArticleContent
import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionDetail
import com.codequest.spi.SubmissionDetailCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmissionHistoryCapable
import com.codequest.spi.SubmitRequest
import com.codequest.spi.TestEvaluationCapable
import com.codequest.spi.TestRunRequest
import com.codequest.spi.TestRunStatus
import com.codequest.spi.UserContestCapable
import com.codequest.spi.UserContestRanking
import kotlinx.coroutines.flow.Flow

/**
 * LeetCode 平台适配器(WP19):同一实现承载 leetcode.com / leetcode.cn 两变体
 * (身份差异见 [LeetCodeVariant]),组合实现十二个能力接口并按服务拆分委托:
 * auth / problem / company(WP42)/ favorite(WP56)/ submission / solutions /
 * submission detail(WP36)/ test evaluation(WP53)/ user contest(1.7.0 F1)/
 * submission history(1.7.0 F2)/ favorite write + hints(1.9.0 F1/F2,主类内
 * 薄委托——两变体共用,收藏/提示非 CN 专属)。
 *
 * 1.8.0 起 CN 变体的竞赛回顾/每日一题由子类 [LeetCodeCnPlatform] 追加实现
 * (COM 站无对应 API,能力探测按实例类型区分,基础能力类保持不变)。
 */
open class LeetCodePlatform(
    private val variant: LeetCodeVariant,
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    cookiePrompt: LeetCodeAuth.CookiePrompt = LeetCodeAuth.CookiePrompt { null },
) : OJPlatform,
    AuthCapable,
    ProblemQueryCapable,
    CompanyQueryCapable,
    FavoriteQueryCapable,
    SubmissionCapable,
    SolutionViewCapable,
    SubmissionDetailCapable,
    TestEvaluationCapable,
    UserContestCapable,
    SubmissionHistoryCapable,
    FavoriteWriteCapable,
    ProblemHintsCapable {
    private val api = LeetCodeApi(variant, transport)
    private val auth = LeetCodeAuth(variant, api, transport, credentialStore, cookiePrompt)
    private val problemService = LeetCodeProblemService(variant, api)
    private val submissionService = LeetCodeSubmissionService(variant, api)
    private val solutions = LeetCodeSolutions(variant, api)
    private val testEvaluationService = LeetCodeTestEvaluationService(variant, api)

    override val id: PlatformId = variant.id
    override val domain: String = variant.domain
    override val info: PlatformInfo =
        PlatformInfo(
            displayName = variant.displayName,
            iconPath = null,
            supportedLanguages = LeetCodeLanguages.ALL,
            templateStyle = TemplateStyle.LEETCODE,
        )

    // 显式委托(而非 `by` 委托):避免超类列表委托表达式在属性初始化前求值的顺序坑

    override suspend fun ensureLoggedIn(): LoginSession = auth.ensureLoggedIn()

    override fun isLoggedIn(): Boolean = auth.isLoggedIn()

    override fun currentSession(): LoginSession? = auth.currentSession()

    override suspend fun restoreSession(): LoginSession? = auth.restoreSession()

    override suspend fun logout() = auth.logout()

    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> = problemService.queryProblems(filter)

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement = problemService.fetchProblemDetail(remoteId)

    override suspend fun fetchCompanies(): List<CompanyTag> = problemService.fetchCompanies()

    override suspend fun queryCompanyProblems(query: CompanyProblemQuery): CompanyProblemPage = problemService.queryCompanyProblems(query)

    override fun studyPlans(): List<FavoriteList> = problemService.studyPlans()

    override suspend fun fetchMyFavorites(): List<FavoriteList> = problemService.fetchMyFavorites()

    override suspend fun queryFavoriteProblems(
        favoriteSlug: String,
        filters: ProblemFilter,
        offset: Int,
        limit: Int,
    ): FavoriteProblemPage = problemService.queryFavoriteProblems(favoriteSlug, filters, offset, limit)

    override suspend fun submit(request: SubmitRequest): SubmissionHandle = submissionService.submit(request)

    override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> = submissionService.watchSubmission(handle)

    override suspend fun fetchSolutions(
        remoteId: String,
        orderBy: String?,
    ): List<SolutionArticleRef> = solutions.fetchSolutions(remoteId, orderBy)

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

    override fun watchTestRun(request: TestRunRequest): Flow<TestRunStatus> = testEvaluationService.watchTestRun(request)

    /** 1.7.0 F1:登录用户的竞赛分(评分着色基准);未登录/无 userSlug/未参赛 → null。 */
    override suspend fun fetchUserContestRanking(): UserContestRanking? {
        val status = api.fetchUserStatus()
        if (!status.isSignedIn) return null
        val userSlug = status.userSlug ?: status.username ?: return null
        return api.fetchUserContestRankingInfo(userSlug)?.let {
            UserContestRanking(rating = it.rating, attendedContestsCount = it.attendedContestsCount)
        }
    }

    /**
     * 1.9.0 F1:收藏写(题面页星标)——主类实现,两变体共用(收藏非 CN 专属,
     * 与 ContestCapable/DailyQuestionCapable 的 CN 子类追加不同)。默认夹定位
     * 与幂等语义见 [LeetCodeApi.addToDefaultFavorite];未登录抛
     * [com.codequest.core.model.AuthExpiredException](REST 401 提示重登)。
     */
    override suspend fun addToDefaultFavorite(questionId: String) = api.addToDefaultFavorite(questionId)

    /** 1.9.0 F1:收藏移除,幂等语义(不在夹中视为成功)见 SPI KDoc。 */
    override suspend fun removeFromDefaultFavorite(questionId: String) = api.removeFromDefaultFavorite(questionId)

    /** 1.9.0 F2:题目提示(懒加载折叠数据源;API 层失败降级空表,两变体共用)。 */
    override suspend fun fetchHints(remoteId: String): List<String> = api.fetchHints(remoteId)

    /** 1.7.0 F2:站内提交历史分页(纯透传映射,未登录走 GraphQL errors 抛领域异常)。 */
    override suspend fun fetchSubmissionHistory(
        offset: Int,
        limit: Int,
    ): RemoteSubmissionPage {
        val page = api.fetchSubmissionPage(offset, limit)
        return RemoteSubmissionPage(
            submissions =
                page.submissions.map {
                    RemoteSubmission(
                        remoteId = it.remoteId,
                        titleSlug = it.titleSlug,
                        title = it.title,
                        statusDisplay = it.statusDisplay,
                        lang = it.lang,
                        runtimeMs = it.runtimeMs,
                        memoryKb = it.memoryKb,
                        submittedAt = it.submittedAt,
                    )
                },
            hasMore = page.hasMore,
        )
    }
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

/**
 * 力扣 CN 平台(1.8.0 F1/F2):[LeetCodePlatform] + 竞赛回顾与每日一题能力。
 * 以子类而非基类直接实现,是**能力探测的硬约束**——COM 变体无竞赛/每日一题
 * API,`is ContestCapable` / `is DailyQuestionCapable` 必须为 false(REST
 * /api/contests、/api/daily-question 按实例类型探测)。[contestService] 自建
 * [LeetCodeApi] 实例:api 无状态(cookie 在 transport 会话),与父类私有
 * api 实例零分裂。
 */
class LeetCodeCnPlatform(
    transport: OJTransport,
    credentialStore: OJCredentialStore,
    cookiePrompt: LeetCodeAuth.CookiePrompt = LeetCodeAuth.CookiePrompt { null },
) : LeetCodePlatform(LeetCodeVariant.LEETCODE_CN, transport, credentialStore, cookiePrompt),
    ContestCapable,
    DailyQuestionCapable {
    private val contestService = LeetCodeContestService(LeetCodeApi(LeetCodeVariant.LEETCODE_CN, transport))

    override fun contests(): Flow<ContestSummary> = contestService.contests()

    override suspend fun contestDetail(remoteId: String): ContestDetail = contestService.contestDetail(remoteId)

    override suspend fun fetchDailyQuestion(): DailyQuestion? = contestService.fetchDailyQuestion()
}

/** leetcode.cn 注册(CN 变体,附竞赛回顾/每日一题能力)。 */
class LeetCodeCnProvider : OJPlatformProvider {
    override fun create(): LeetCodeCnPlatform =
        LeetCodeCnPlatform(
            transport = OJRuntime.transport(),
            credentialStore = OJRuntime.credentialStore(),
        )
}
