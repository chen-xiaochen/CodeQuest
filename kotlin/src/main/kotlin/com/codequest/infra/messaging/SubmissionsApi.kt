package com.codequest.infra.messaging

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.OJException
import com.codequest.core.model.RateLimitedException
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.repository.ChallengeRepository
import com.codequest.infra.db.ExposedSubmissionRepository
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.SubmissionDetailCapable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 提交记录数据端点逻辑(WP21,infra 层):submissions 页的数据源。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/submissions?challengeId=&limit=&offset=`:本地库 submission 表,
 *   按提交时间倒序(同刻按 id 升序稳定排序,与仓库契约一致);
 *   `challengeId` 可选——缺省列出全部工作区的提交;
 * - `GET /api/submission/{id}/detail`(WP36,M7-A):本地 submission → 平台实例
 *   (经 SPI 注册表)重拉详情并**合并**——verdict/耗时/内存/语言取平台侧最新值,
 *   本地快照兜底;message/错误用例三件套/extras/viewUrl 仅平台给出时携带;
 *   `code` 为本地代码快照(WP40 代码回看复用,零网络);**不落库**;
 * - verdict 为 [SubmissionResult] 多态 JSON(discriminator `type`,前端按此渲染徽标);
 * - `challengeName` 为尽力解析(challenge 主档被删时为 null);
 * - `hasMore` = 按「limit+1 预取」语义推断;参数非法回 400。
 *
 * 错误映射(WP36,语义与 SolutionsApi 对齐):详情端点的本地段错误——id 缺失 400 /
 * 未知提交 404;平台段错误(登录失效 401 / 限流 429 / 反爬 403 / 其余 OJException
 * 502)按既有映射整体回错(不半静默——前端按错误码渲染,auth-expired 可接登录入口)。
 *
 * 与 [ProblemApi] 同风格:纯 Kotlin、来源以 lambda 注入(数据库切换即时生效)。
 */
class SubmissionsApi(
    private val submissions: () -> ExposedSubmissionRepository,
    private val challenges: () -> ChallengeRepository,
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
) {
    suspend fun listSubmissions(query: Map<String, String>): ProblemApiResponse {
        val parsed = parseQuery(query) ?: return badRequest("limit must be in 1..$MAX_LIMIT; offset >= 0")
        // 预取 limit+1:多出一条即还有下一页
        val fetched = submissions().listPage(parsed.challengeId, limit = parsed.limit + 1, offset = parsed.offset)
        val page = fetched.take(parsed.limit)
        val names = challengeNames(page)
        return ok(
            SubmissionListResponseDto(
                challengeId = parsed.challengeId?.value,
                offset = parsed.offset,
                limit = parsed.limit,
                hasMore = fetched.size > parsed.limit,
                items = page.map { it.toDto(names) },
            ),
        )
    }

    /** `GET /api/submission/{id}/detail`:本地快照 + 平台详情合并(不落库)。 */
    suspend fun submissionDetail(id: String): ProblemApiResponse {
        if (id.isBlank()) return badRequest("missing submission id path segment")
        val submission =
            submissions().findById(SubmissionId(id)) ?: return errorResponse(
                STATUS_NOT_FOUND,
                SubmissionErrorResponseDto(CODE_NOT_FOUND, "no submission found for id '$id'"),
            )
        // challenge.problemId 形如 "<platform>:<remoteId>"(ProblemApi.problemId 确定性拼装),
        // 首个 ":" 后即 problemRemoteId(HR 的 "contest/slug" 形态不含 ":",各平台同理);
        // challenge 被删时置空串——LC 系详情可照常重拉,需要题目寻址的平台自行报领域异常
        val problemRemoteId =
            challenges()
                .findById(submission.challengeId)
                ?.problemId
                ?.value
                ?.takeIf { it.contains(PROBLEM_ID_SEPARATOR) }
                ?.substringAfter(PROBLEM_ID_SEPARATOR)
                .orEmpty()
        val capable =
            platforms().firstOrNull { it.id == submission.platformId } as? SubmissionDetailCapable
        val viewUrl =
            capable?.let { runCatching { it.submissionViewUrl(submission.remoteSubmissionId, problemRemoteId) }.getOrNull() }
        val detail =
            capable?.let {
                try {
                    it.fetchSubmissionDetail(submission.remoteSubmissionId, problemRemoteId)
                } catch (error: Throwable) {
                    return error.toErrorResponse()
                }
            }
        return ok(
            submission.toDetailDto(
                challengeName = challenges().findById(submission.challengeId)?.name,
                detail = detail,
                viewUrl = viewUrl,
            ),
        )
    }

    /** 本页涉及的 challenge → 名称(去重后逐个查主档,单页 challenge 种类极少)。 */
    private suspend fun challengeNames(page: List<Submission>): Map<String, String> =
        page
            .map { it.challengeId }
            .distinct()
            .mapNotNull { id -> challenges().findById(id)?.let { id.value to it.name } }
            .toMap()

    private data class ParsedQuery(
        val challengeId: ChallengeId?,
        val limit: Int,
        val offset: Int,
    )

    private fun parseQuery(query: Map<String, String>): ParsedQuery? {
        // 参数出现但不可解析 → 与越界同罪回 400(机器契约,不做静默回退)
        val limit = query["limit"]?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_LIMIT
        val offset = query["offset"]?.let { it.toIntOrNull() ?: return null } ?: 0
        if (limit !in 1..MAX_LIMIT || offset < 0) return null
        val challengeId = query["challengeId"]?.trim()?.takeIf { it.isNotEmpty() }
        return ParsedQuery(challengeId?.let { ChallengeId(it) }, limit, offset)
    }

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, SubmissionErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun errorResponse(
        status: Int,
        dto: SubmissionErrorResponseDto,
        headers: Map<String, String> = emptyMap(),
    ): ProblemApiResponse = ProblemApiResponse(status, json.encodeDto(dto), headers)

    /** 平台详情段错误 → HTTP(语义与 SolutionsApi/ProblemApi 对齐,WP36)。 */
    private fun Throwable.toErrorResponse(): ProblemApiResponse =
        when (this) {
            is AuthExpiredException -> {
                errorResponse(STATUS_UNAUTHORIZED, SubmissionErrorResponseDto(CODE_AUTH_EXPIRED, message))
            }

            is RateLimitedException -> {
                errorResponse(
                    STATUS_RATE_LIMITED,
                    SubmissionErrorResponseDto(CODE_RATE_LIMITED, message),
                    headers = mapOf(RETRY_AFTER_HEADER to retryAfterSeconds(retryAfterMs).toString()),
                )
            }

            is AntiBotChallengeException -> {
                errorResponse(STATUS_FORBIDDEN, SubmissionErrorResponseDto(CODE_ANTI_BOT, message))
            }

            is OJException -> {
                errorResponse(STATUS_BAD_GATEWAY, SubmissionErrorResponseDto(CODE_OJ_ERROR, message))
            }

            else -> {
                errorResponse(STATUS_INTERNAL_ERROR, SubmissionErrorResponseDto(CODE_INTERNAL, "unexpected server error"))
            }
        }

    /** 毫秒 → 秒向上取整(Retry-After 头,至少 1 秒;与 SolutionsApi 同语义)。 */
    private fun retryAfterSeconds(retryAfterMs: Long?): Long {
        val millis = retryAfterMs ?: DEFAULT_RETRY_AFTER_SECONDS * MILLIS_PER_SECOND
        return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(DEFAULT_RETRY_AFTER_SECONDS)
    }

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is SubmissionListResponseDto -> encodeToString(SubmissionListResponseDto.serializer(), dto)
            is SubmissionDetailResponseDto -> encodeToString(SubmissionDetailResponseDto.serializer(), dto)
            is SubmissionErrorResponseDto -> encodeToString(SubmissionErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private companion object {
        /** explicitNulls=false:null 字段(如 challengeName)直接省略,前端按缺省回退。 */
        val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        const val DEFAULT_LIMIT = 50

        const val MAX_LIMIT = 100

        const val PROBLEM_ID_SEPARATOR = ':'

        const val MILLIS_PER_SECOND = 1000L

        const val DEFAULT_RETRY_AFTER_SECONDS = 1L

        const val RETRY_AFTER_HEADER = "Retry-After"

        const val STATUS_OK = 200

        const val STATUS_BAD_REQUEST = 400

        const val STATUS_UNAUTHORIZED = 401

        const val STATUS_FORBIDDEN = 403

        const val STATUS_NOT_FOUND = 404

        const val STATUS_INTERNAL_ERROR = 500

        const val STATUS_BAD_GATEWAY = 502

        const val STATUS_RATE_LIMITED = 429

        const val CODE_BAD_REQUEST = "bad-request"

        const val CODE_NOT_FOUND = "not-found"

        const val CODE_AUTH_EXPIRED = "auth-expired"

        const val CODE_RATE_LIMITED = "rate-limited"

        const val CODE_ANTI_BOT = "anti-bot"

        const val CODE_OJ_ERROR = "oj-error"

        const val CODE_INTERNAL = "internal-error"
    }
}

private fun Submission.toDto(challengeNames: Map<String, String>) =
    SubmissionItemDto(
        id = id.value,
        challengeId = challengeId.value,
        challengeName = challengeNames[challengeId.value],
        platformId = platformId.value,
        remoteSubmissionId = remoteSubmissionId,
        language = language,
        verdict = verdict,
        timeMs = timeMs,
        memoryKb = memoryKb,
        submittedAt = submittedAt.toString(),
    )

/** 本地快照 + 平台详情合并(平台侧非 null 值覆盖本地;null 字段序列化时省略)。 */
private fun Submission.toDetailDto(
    challengeName: String?,
    detail: com.codequest.spi.SubmissionDetail?,
    viewUrl: String?,
) = SubmissionDetailResponseDto(
    id = id.value,
    challengeId = challengeId.value,
    challengeName = challengeName,
    platformId = platformId.value,
    remoteSubmissionId = remoteSubmissionId,
    language = detail?.language ?: language,
    verdict = detail?.verdict ?: verdict,
    timeMs = detail?.timeMs ?: timeMs,
    memoryKb = detail?.memoryKb ?: memoryKb,
    submittedAt = submittedAt.toString(),
    code = codeSnapshot,
    message = detail?.message,
    errorCase = detail?.errorCase,
    extras = detail?.extras,
    viewUrl = viewUrl,
)

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class SubmissionListResponseDto(
    /** 过滤的 challenge id;null = 全部工作区。 */
    val challengeId: String? = null,
    val offset: Int,
    val limit: Int,
    val items: List<SubmissionItemDto>,
    val hasMore: Boolean,
)

@Serializable
private data class SubmissionItemDto(
    val id: String,
    val challengeId: String,
    /** challenge 主档被删除时为 null(前端回退显示 challengeId)。 */
    val challengeName: String? = null,
    val platformId: String,
    val remoteSubmissionId: String,
    val language: String,
    val verdict: SubmissionResult,
    val timeMs: Int? = null,
    val memoryKb: Int? = null,
    /** ISO-8601 即时字符串。 */
    val submittedAt: String,
)

/** WP36:详情响应(合并形态)。verdict/耗时/内存/语言为「平台侧最新值 ?: 本地快照」。 */
@Serializable
private data class SubmissionDetailResponseDto(
    val id: String,
    val challengeId: String,
    val challengeName: String? = null,
    val platformId: String,
    val remoteSubmissionId: String,
    val language: String,
    val verdict: SubmissionResult,
    val timeMs: Int? = null,
    val memoryKb: Int? = null,
    /** ISO-8601 即时字符串。 */
    val submittedAt: String,
    /** 本地代码快照(WP40 代码回看复用;零网络)。 */
    val code: String,
    /** 平台侧错误/提示原文(如 LC 编译错误全文)。 */
    val message: String? = null,
    val errorCase: com.codequest.spi.SubmissionErrorCase? = null,
    /** 平台特有键值对(LC 百分位/用例计数、HR 用例通过计数)。 */
    val extras: Map<String, String>? = null,
    /** 平台网站提交详情页(浏览器兜底出口)。 */
    val viewUrl: String? = null,
)

/** 与 ProblemApi 的 ErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class SubmissionErrorResponseDto(
    val error: String,
    val message: String? = null,
)
