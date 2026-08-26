package com.codequest.infra.messaging

import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.ProblemStore
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformProvider
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.PlatformInfo
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [integration-ish unit] 1.3.1:落库阶段(题单/公司/普通列表共用 [ProblemApi.persistAndRespond])
 * 的 DB 异常必须映射为 JSON 错误响应,不得逃逸成 ResourceHttpServer 顶层的
 * 纯文本 500(前端 unwrap 解析不到 error/message,只能显示 http-error)。
 *
 * 用户实测事故:库文件被外部清零后选题单 → DELETE problem 报 no such table →
 * 裸 500。本测试以「store 恒抛」模拟损坏库,锁定三路径的 JSON 契约。
 */
class ProblemApiPersistFailureHttpTest {
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    private inner class OneProblemPlatform :
        OJPlatform,
        ProblemQueryCapable,
        com.codequest.spi.CompanyQueryCapable,
        com.codequest.spi.FavoriteQueryCapable {
        override val id = PlatformId("oneproblem")
        override val domain = "oneproblem.example.com"
        override val info = PlatformInfo(displayName = "One", templateStyle = TemplateStyle.ACM)

        private val summary =
            ProblemSummary(remoteId = "p-1", title = "P1", difficulty = com.codequest.core.model.Difficulty("easy"), url = "https://x/p1")

        override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
            flow { emit(summary) }

        override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement =
            throw IllegalStateException("unreachable")

        override suspend fun fetchCompanies(): List<com.codequest.spi.CompanyTag> = emptyList()

        override suspend fun queryCompanyProblems(query: com.codequest.spi.CompanyProblemQuery): com.codequest.spi.CompanyProblemPage =
            com.codequest.spi.CompanyProblemPage(total = 1, questions = listOf(summary))

        override fun studyPlans(): List<com.codequest.spi.FavoriteList> = emptyList()

        override suspend fun fetchMyFavorites(): List<com.codequest.spi.FavoriteList> = emptyList()

        override suspend fun queryFavoriteProblems(
            favoriteSlug: String,
            filters: ProblemFilter,
            offset: Int,
            limit: Int,
        ): com.codequest.spi.FavoriteProblemPage =
            com.codequest.spi.FavoriteProblemPage(total = 1, questions = listOf(summary))
    }

    /** 恒抛的损坏库端口(模拟外部清零后的 no such table)。 */
    private inner class BrokenStore : ProblemStore {
        override suspend fun save(problem: Problem) = throw RuntimeException("no such table: problem")

        override suspend fun findByRemoteId(
            platformId: PlatformId,
            remoteId: String,
        ): Problem? = throw RuntimeException("no such table: problem")

        override suspend fun findDetail(problemId: ProblemId): ProblemDetail? = null

        override suspend fun saveDetail(detail: ProblemDetail) = Unit
    }

    @BeforeTest
    fun setUp() {
        OJPlatformRegistryTestHelper.inject(OneProblemPlatform())
        server =
            ResourceHttpServer(
                problemApi =
                    ProblemApi(
                        store = { BrokenStore() },
                        statuses = { throw RuntimeException("no such table: problem") },
                    ),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        OJPlatformRegistryTestHelper.reset()
    }

    private fun get(path: String): Pair<Int, String> {
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.statusCode() to response.body()
    }

    private fun assertJsonError(path: String) {
        val (status, body) = get(path)
        assertEquals(500, status, "$path 应 500: $body")
        assertTrue("\"error\"" in body, "$path 应为 JSON 错误体: $body")
        assertTrue("internal-error" in body, "$path 应含 internal-error: $body")
        assertTrue("text/plain" !in body, "$path 不应是顶层纯文本兜底: $body")
    }

    @Test
    fun `persist failure maps to json error on all three list paths`() {
        assertJsonError("/api/problems?platform=oneproblem&limit=5")
        assertJsonError("/api/problems?platform=oneproblem&company=amazon&limit=5")
        assertJsonError("/api/problems?platform=oneproblem&favorite=top-100-liked&limit=5")
    }
}
