package com.codequest.platform.atcoder

import com.codequest.core.model.OJException
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP25:AtCoderApi MockEngine 回放夹具——走**真实栈**
 * (MockEngine → OJHttpClient → HttpOJTransport),断言 kenkoooo JSON 解析、
 * 难度换算、题面/提交详情页 URL、提交表单 POST 的**编码 body 逐字**
 * (data.TaskScreenName 等五字段,旧 UrlForm 事实)。全程不上网络。
 */
class AtCoderApiTest {
    private data class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val bodyContentType: String?,
    )

    private val recorded = mutableListOf<Recorded>()

    /** 路径 → 响应体(测试内按需覆盖)。 */
    private val responses = mutableMapOf<String, String>()

    private val engine =
        MockEngine { request ->
            val path =
                request.url.encodedPath +
                    (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            recorded +=
                Recorded(
                    request.method.value,
                    path,
                    body = runCatching { request.body.toByteArray().decodeToString() }.getOrDefault(""),
                    bodyContentType = request.body.contentType?.toString(),
                )
            val content =
                responses[path] ?: responses[request.url.encodedPath] ?: error("未脚本化请求: ${request.method.value} $path")
            respond(content = content, status = HttpStatusCode.OK)
        }

    private lateinit var client: OJHttpClient
    private lateinit var api: AtCoderApi

    @BeforeTest
    fun setUp() {
        client = OJHttpClient(engine)
        api = AtCoderApi(HttpOJTransport(client))
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private val mergedProblemsBody =
        """
        [
          {"id":"abc100_a","contest_id":"abc100","problem_index":"A","name":"Happy Birthday!","title":"AtCoder Beginner Contest 100 A - Happy Birthday!","shortest_submission_id":123,"solver_count":12345},
          {"id":"abc100_b","contest_id":"abc100","problem_index":"B","name":"Ringo's Favorite Numbers","title":"AtCoder Beginner Contest 100 B - Ringo's Favorite Numbers","solver_count":9999},
          {"id":"practice_1","contest_id":"practice","problem_index":"A","name":"Welcome to AtCoder","title":"practice A - Welcome to AtCoder","first_user_id":null}
        ]
        """.trimIndent()

    private val problemModelsBody =
        """
        {
          "abc100_a": {"difficulty": 990.0, "slope": 1.0},
          "abc100_b": {"difficulty": -123.45},
          "practice_1": {"slope": 2.0, "intercept": 0.5}
        }
        """.trimIndent()

    @Test
    fun `problems parsed from kenkoooo merged-problems json`() = runBlocking {
        responses["/atcoder/resources/merged-problems.json"] = mergedProblemsBody

        val problems = api.fetchProblems()

        assertEquals(3, problems.size)
        assertEquals("abc100_a", problems[0].id)
        assertEquals("abc100", problems[0].contestId)
        assertEquals("A", problems[0].problemIndex)
        assertEquals("Happy Birthday!", problems[0].name)
        assertEquals("AtCoder Beginner Contest 100 A - Happy Birthday!", problems[0].title)
        assertEquals("practice", problems[2].contestId)
    }

    @Test
    fun `difficulties converted from irt values with kenkoooo formula`() = runBlocking {
        responses["/atcoder/resources/problem-models.json"] = problemModelsBody

        val difficulties = api.fetchProblemDifficulties()

        assertEquals(3, difficulties.size)
        // >=400 原样
        assertEquals(990, difficulties["abc100_a"])
        // 负值按 400/exp(1-irt/400) 换算:-123 → 400/exp(1.308625) ≈ 108
        assertEquals(108, difficulties["abc100_b"])
        // difficulty 字段缺失按 0(旧 getOrElse(0) 事实)→ 400/e ≈ 147
        assertEquals(147, difficulties["practice_1"])
        // 换算公式边界
        assertEquals(990, AtCoderApi.calculateDisplayDifficulty(990))
        assertEquals(147, AtCoderApi.calculateDisplayDifficulty(0))
        assertEquals(399, AtCoderApi.calculateDisplayDifficulty(399))
    }

    @Test
    fun `non-json resource body maps to OJException`() {
        responses["/atcoder/resources/merged-problems.json"] = "<html>blocked</html>"
        assertFailsWith<OJException> {
            runBlocking { api.fetchProblems() }
        }
        responses["/atcoder/resources/problem-models.json"] = "[]"
        assertFailsWith<OJException> {
            runBlocking { api.fetchProblemDifficulties() }
        }
    }

    @Test
    fun `task and settings pages fetched from atcoder urls`() = runBlocking {
        responses["/contests/abc100/tasks/abc100_a"] = "<div id=\"main-container\"></div>"
        responses["/settings"] = "<div id=\"main-container\"></div>"

        api.fetchTaskPageHtml("abc100", "abc100_a")
        api.fetchSettingsPageHtml()

        assertEquals(
            listOf("GET /contests/abc100/tasks/abc100_a", "GET /settings"),
            recorded.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun `submit posts legacy form fields urlencoded`() = runBlocking {
        responses["/contests/abc100/submit"] =
            """<table><td class="submission-score" data-id="65432101">0</td></table>"""

        val submissionId =
            api.submitAnswer(
                contestId = "abc100",
                problemId = "abc100_a",
                languageId = "5005",
                source = "fun main() {}",
                tokens = AtCoderApi.SubmitTokens("csrf-1", "ts-1"),
            )

        assertEquals("65432101", submissionId)
        val request = recorded.single()
        assertEquals("POST", request.method)
        assertTrue(
            request.bodyContentType?.startsWith("application/x-www-form-urlencoded") == true,
            "Content-Type 应为表单编码,实际 ${request.bodyContentType}",
        )
        // 字段名/顺序为旧 UrlForm 逐字事实;值按表单编码转义
        assertEquals(
            "data.TaskScreenName=abc100_a&data.LanguageId=5005&sourceCode=fun+main%28%29+%7B%7D" +
                "&csrf_token=csrf-1&cf-turnstile-response=ts-1",
            request.body,
        )
    }

    @Test
    fun `submit alert div maps to OJException with page text`() {
        responses["/contests/abc100/submit"] =
            """<div role="alert">You have already submitted exactly the same code.</div>"""

        val error =
            assertFailsWith<OJException> {
                runBlocking {
                    api.submitAnswer("abc100", "abc100_a", "5005", "x", AtCoderApi.SubmitTokens("c", "t"))
                }
            }
        assertTrue(error.message!!.contains("same code"))
    }

    @Test
    fun `submit response without submission id maps to OJException`() {
        responses["/contests/abc100/submit"] = """<div>No submission row</div>"""
        assertFailsWith<OJException> {
            runBlocking { api.submitAnswer("abc100", "abc100_a", "5005", "x", AtCoderApi.SubmitTokens("c", "t")) }
        }
    }

    @Test
    fun `submission status page fetched from contests url`() = runBlocking {
        responses["/contests/abc100/submissions/65432101"] = "<td id=\"judge-status\"></td>"

        api.fetchSubmissionStatusPage("abc100", "65432101")

        assertEquals("GET /contests/abc100/submissions/65432101", recorded.single().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `submit tokens parser rejects wrong arity and blanks`() {
        assertEquals(AtCoderApi.SubmitTokens("a", "b"), AtCoderApi.SubmitTokens.parse("a,b"))
        assertEquals(null, AtCoderApi.SubmitTokens.parse("a,b,c"))
        assertEquals(null, AtCoderApi.SubmitTokens.parse("csrf-only"))
        assertEquals(null, AtCoderApi.SubmitTokens.parse("a,"))
    }
}
