package com.codequest.infra.messaging

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId
import com.codequest.core.model.TestCaseSource
import com.codequest.core.repository.ChallengeRepository
import com.codequest.core.repository.TestCaseRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 测试用例数据端点(1.5.0,infra 层):右窗「测试用例」页的数据源。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/testcases?challengeId=`:某挑战的全部用例(附尽力解析的 challengeName);
 * - `POST /api/testcases`(body `{challengeId, input, expected}`):新增(source=USER);
 * - `POST /api/testcases/update`(body `{id, input, expected}`):更新(保留来源);
 * - `DELETE /api/testcases?id=`:删除;
 * - 参数/正文缺失或 id 不存在回 400/404;`source` 为 USER|PLATFORM。
 *
 * 与 [SubmissionsApi] 同风格:纯 Kotlin、来源以 lambda 注入(数据库切换即时生效)。
 * 写操作直接走仓库接口([ChallengeWorkspaceService] 的用例方法即 saveTestCase/
 * deleteTestCase 的仓库薄转发,HTTP 端点无需再包一层服务)。
 */
class TestCasesApi(
    private val testCases: () -> TestCaseRepository,
    private val challenges: () -> ChallengeRepository,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = DEFAULT_JSON,
) {
    /** `GET /api/testcases?challengeId=`:列出挑战全部用例。 */
    suspend fun listTestCases(query: Map<String, String>): ProblemApiResponse {
        val challengeId =
            query["challengeId"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return badRequest("missing challengeId query parameter")
        val id = ChallengeId(challengeId)
        val items = testCases().findByChallenge(id)
        return ok(
            TestCaseListResponseDto(
                challengeId = challengeId,
                challengeName = challenges().findById(id)?.name,
                items = items.map { it.toDto() },
            ),
        )
    }

    /** `POST /api/testcases`:新增用例(source=USER)。 */
    suspend fun createTestCase(body: String): ProblemApiResponse {
        val request = json.decodeCreateRequest(body) ?: return badRequest("body must be {challengeId, input, expected}")
        val challengeId = ChallengeId(request.challengeId)
        val created =
            TestCase(
                id = TestCaseId(ids()),
                challengeId = challengeId,
                input = request.input,
                expected = request.expected,
                source = TestCaseSource.USER,
            )
        testCases().save(created)
        return ok(created.toDto())
    }

    /** `POST /api/testcases/update`:更新用例(保留 challengeId 与来源)。 */
    suspend fun updateTestCase(body: String): ProblemApiResponse {
        val request = json.decodeUpdateRequest(body) ?: return badRequest("body must be {id, input, expected}")
        val existing =
            testCases().findById(TestCaseId(request.id))
                ?: return notFound("no test case found for id '${request.id}'")
        val updated = existing.copy(input = request.input, expected = request.expected)
        testCases().save(updated)
        return ok(updated.toDto())
    }

    /** `DELETE /api/testcases?id=`:删除用例。 */
    suspend fun deleteTestCase(query: Map<String, String>): ProblemApiResponse {
        val id =
            query["id"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return badRequest("missing id query parameter")
        return if (testCases().delete(TestCaseId(id))) {
            ok(DeleteResultDto(deleted = true))
        } else {
            notFound("no test case found for id '$id'")
        }
    }

    // ------------------------------------------------------------------
    // 支撑
    // ------------------------------------------------------------------

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, TestCaseErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun notFound(message: String): ProblemApiResponse =
        errorResponse(STATUS_NOT_FOUND, TestCaseErrorResponseDto(CODE_NOT_FOUND, message))

    private fun errorResponse(
        status: Int,
        dto: TestCaseErrorResponseDto,
    ): ProblemApiResponse = ProblemApiResponse(status, json.encodeDto(dto))

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is TestCaseListResponseDto -> encodeToString(TestCaseListResponseDto.serializer(), dto)
            is TestCaseItemDto -> encodeToString(TestCaseItemDto.serializer(), dto)
            is DeleteResultDto -> encodeToString(DeleteResultDto.serializer(), dto)
            is TestCaseErrorResponseDto -> encodeToString(TestCaseErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private fun Json.decodeCreateRequest(body: String): CreateRequestDto? =
        runCatching { decodeFromString(CreateRequestDto.serializer(), body) }
            .getOrNull()
            ?.takeIf { it.challengeId.isNotBlank() }

    private fun Json.decodeUpdateRequest(body: String): UpdateRequestDto? =
        runCatching { decodeFromString(UpdateRequestDto.serializer(), body) }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() }

    private companion object {
        /** explicitNulls=false:null 字段(如 challengeName)直接省略,前端按缺省回退。 */
        val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        const val STATUS_OK = 200

        const val STATUS_BAD_REQUEST = 400

        const val STATUS_NOT_FOUND = 404

        const val CODE_BAD_REQUEST = "bad-request"

        const val CODE_NOT_FOUND = "not-found"
    }
}

private fun TestCase.toDto() =
    TestCaseItemDto(
        id = id.value,
        input = input,
        expected = expected,
        source = source,
    )

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class TestCaseListResponseDto(
    val challengeId: String,
    /** challenge 主档被删除时为 null(前端回退显示 challengeId)。 */
    val challengeName: String? = null,
    val items: List<TestCaseItemDto>,
)

@Serializable
private data class TestCaseItemDto(
    val id: String,
    val input: String,
    val expected: String,
    val source: TestCaseSource,
)

@Serializable
private data class CreateRequestDto(
    val challengeId: String,
    val input: String = "",
    val expected: String = "",
)

@Serializable
private data class UpdateRequestDto(
    val id: String,
    val input: String = "",
    val expected: String = "",
)

@Serializable
private data class DeleteResultDto(
    val deleted: Boolean,
)

/** 与 SubmissionsApi 的 ErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class TestCaseErrorResponseDto(
    val error: String,
    val message: String? = null,
)
