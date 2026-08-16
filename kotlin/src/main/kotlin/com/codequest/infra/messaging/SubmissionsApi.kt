package com.codequest.infra.messaging

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionResult
import com.codequest.core.repository.ChallengeRepository
import com.codequest.infra.db.ExposedSubmissionRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 提交记录数据端点逻辑(WP21,infra 层):submissions 页的数据源。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/submissions?challengeId=&limit=&offset=`:本地库 submission 表,
 *   按提交时间倒序(同刻按 id 升序稳定排序,与仓库契约一致);
 *   `challengeId` 可选——缺省列出全部工作区的提交;
 * - verdict 为 [SubmissionResult] 多态 JSON(discriminator `type`,前端按此渲染徽标);
 * - `challengeName` 为尽力解析(challenge 主档被删时为 null);
 * - `hasMore` = 按「limit+1 预取」语义推断;参数非法回 400。
 *
 * 与 [ProblemApi] 同风格:纯 Kotlin、来源以 lambda 注入(数据库切换即时生效)。
 */
class SubmissionsApi(
    private val submissions: () -> ExposedSubmissionRepository,
    private val challenges: () -> ChallengeRepository,
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
        ProblemApiResponse(
            STATUS_BAD_REQUEST,
            json.encodeDto(SubmissionErrorResponseDto("bad-request", message)),
        )

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is SubmissionListResponseDto -> encodeToString(SubmissionListResponseDto.serializer(), dto)
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

        const val STATUS_OK = 200

        const val STATUS_BAD_REQUEST = 400
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

/** 与 ProblemApi 的 ErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class SubmissionErrorResponseDto(
    val error: String,
    val message: String? = null,
)
