package com.codequest.platform.hackerrank

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP27:HackerRankApi MockEngine 回放夹具——走**真实栈**
 * (MockEngine → OJHttpClient → HttpOJTransport),断言 REST 列表/过滤参数
 * (filters 多值形态)、appsearch 关键字、题目页 initialData(URL 编码 JSON)
 * 提取、CSRF、提交 JSON body + x-csrf-token 头**逐字**、提交轮询的
 * status/score_processed 终止语义、登录态 2xx 判定与 dashboard
 * `__NEXT_DATA__` 领域/用户解析。全程不上网络。
 *
 * 响应脚本按**URL 解码后**的 path(含 query)索引——ktor 对 query 中字面
 * `[]`/编码 `%5B` 的处理不承担断言稳定性,解码后两者归一。
 */
class HackerRankApiTest {
    private data class Recorded(
        val method: String,
        /** URL 解码后的 path(含 query;`%5B`/字面 `[]` 归一为 `[]`)。 */
        val path: String,
        /** 原始 query(未解码,断言编码形态用)。 */
        val rawQuery: String,
        val body: String,
    )

    private val recorded = mutableListOf<Recorded>()

    /** URL 解码 path(含 query)→ 响应体队列(支持轮询多次拉取)。 */
    private val responses = mutableMapOf<String, ArrayDeque<String>>()

    private val engine =
        MockEngine { request ->
            val rawQuery = request.url.encodedQuery
            val path = decode(request.url.encodedPath + (if (rawQuery.isEmpty()) "" else "?$rawQuery"))
            recorded += Recorded(
                request.method.value,
                path,
                rawQuery,
                runCatching { request.body.toByteArray().decodeToString() }.getOrDefault(""),
            )
            val queue = responses[path] ?: error("未脚本化请求: ${request.method.value} $path")
            val content = queue.removeFirstOrNull() ?: error("响应脚本耗尽: ${request.method.value} $path")
            respond(content = content, status = HttpStatusCode.OK)
        }

    private lateinit var client: OJHttpClient
    private lateinit var api: HackerRankApi

    @BeforeTest
    fun setUp() {
        client = OJHttpClient(engine)
        api = HackerRankApi(HttpOJTransport(client))
    }

    @AfterTest
    fun tearDown() {
        client.close()
        recorded.clear()
    }

    // -------------------------------------------------------------------
    // 登录态 / 用户 / 领域(dashboard __NEXT_DATA__)
    // -------------------------------------------------------------------

    @Test
    fun `check login maps 2xx to true`() = runBlocking {
        responses[LOGIN_CHECK_PATH] = ArrayDeque(listOf("""{"slots":[]}"""))

        assertTrue(api.checkLogin())
        assertEquals("GET", recorded.single().method)
        assertEquals(LOGIN_CHECK_PATH, recorded.single().path)
    }

    @Test
    fun `check login maps non-2xx transport error to false`() = runBlocking {
        val unauthorizedEngine =
            MockEngine { _ ->
                respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
            }
        val unauthorizedClient = OJHttpClient(unauthorizedEngine)
        try {
            assertEquals(false, HackerRankApi(HttpOJTransport(unauthorizedClient)).checkLogin())
        } finally {
            unauthorizedClient.close()
        }
    }

    @Test
    fun `domains parsed sorted by id with project euler appended`() = runBlocking {
        // fetchDomains + fetchUserInfo 各拉一次 dashboard
        responses[DASHBOARD_PATH] = ArrayDeque(listOf(dashboardHtml(), dashboardHtml()))

        val domains = api.fetchDomains()

        assertEquals(listOf("algorithms", "mathematics", "projecteuler"), domains.map { it.slug })
        assertEquals(listOf(6, 8, 99), domains.map { it.id })
        assertEquals(listOf("master", "master", "projecteuler"), domains.map { it.contestSlug })
        // children → subdomains;缺失 id/name/slug 的分类条目被跳过
        assertEquals(listOf(HackerRankApi.HackerRankSubdomain("Number Theory", "number-theory")), domains[1].subdomains)
        assertEquals("Project Euler", domains.last().name)

        val user = api.fetchUserInfo()
        assertEquals("kaguya", user?.username)
    }

    @Test
    fun `dashboard without next data maps to OJException`() {
        responses[DASHBOARD_PATH] = ArrayDeque(listOf("<html><body>login wall</body></html>"))
        assertFailsWith<OJException> {
            runBlocking { api.fetchDomains() }
        }
    }

    // -------------------------------------------------------------------
    // 题目列表(REST 分页 + filters)
    // -------------------------------------------------------------------

    @Test
    fun `challenge list parsed with totals and legacy snake case fields`() = runBlocking {
        responses["$MASTER_LIST_BASE?offset=0&limit=2"] = ArrayDeque(listOf(listJson()))

        val page = api.searchChallenges(offset = 0, limit = 2, contestSlug = "master", domainSlug = "algorithms")

        assertEquals(117, page.total)
        assertEquals(2, page.items.size)
        assertEquals("solve-me-first", page.items[0].slug)
        assertEquals("Solve Me First", page.items[0].name)
        assertEquals("master", page.items[0].contestSlug)
        assertEquals("easy", page.items[0].difficultyName)
        assertEquals(10, page.items[0].maxScore)
        assertNull(page.items[0].bodyHtml)
        // 列表项缺 contest_slug 时回退 master(旧默认)
        assertEquals("master", page.items[1].contestSlug)
    }

    @Test
    fun `challenge list sends filter multi-value params in legacy order`() = runBlocking {
        responses["$MASTER_LIST_BASE?offset=10&limit=5&filters[subdomains][]=recursion&filters[difficulty][]=easy"] =
            ArrayDeque(listOf("""{"total":0,"models":[]}"""))

        api.searchChallenges(
            offset = 10,
            limit = 5,
            contestSlug = "master",
            domainSlug = "algorithms",
            filters =
                HackerRankApi.ChallengeFilters(
                    difficulties = listOf("easy"),
                    subdomains = listOf("recursion"),
                ),
        )

        assertEquals(
            "$MASTER_LIST_BASE?offset=10&limit=5&filters[subdomains][]=recursion&filters[difficulty][]=easy",
            recorded.single().path,
        )
    }

    @Test
    fun `project euler list url has no tracks segment`() = runBlocking {
        responses["/rest/contests/projecteuler/challenges?offset=0&limit=1"] =
            ArrayDeque(listOf("""{"total":1,"models":[{"id":190001,"slug":"cigrtsxnngsq","name":"PE 1","contest_slug":"projecteuler","difficulty_name":"easy","max_score":100}]}"""))

        val page = api.searchChallenges(offset = 0, limit = 1, contestSlug = "projecteuler", domainSlug = "projecteuler")

        assertEquals(1, page.items.size)
        assertEquals("cigrtsxnngsq", page.items[0].slug)
        assertEquals("projecteuler", page.items[0].contestSlug)
    }

    @Test
    fun `list response without total maps to OJException`() {
        responses["$MASTER_LIST_BASE?offset=0&limit=5"] = ArrayDeque(listOf("""{"models":[]}"""))
        assertFailsWith<OJException> {
            runBlocking { api.searchChallenges(0, 5, "master", "algorithms") }
        }
    }

    // -------------------------------------------------------------------
    // 关键字搜索(appsearch)
    // -------------------------------------------------------------------

    @Test
    fun `appsearch items parsed with contest and challenge identity`() = runBlocking {
        responses["/appsearch?contest_slug=master&query=solve me"] = ArrayDeque(listOf(appSearchJson()))

        val items = api.searchChallengesWithKeyword("master", "solve me")

        assertEquals(2, items.size)
        assertEquals("solve-me-first", items[0].challengeSlug)
        assertEquals("Solve Me First", items[0].challengeName)
        assertEquals("master", items[0].contestSlug)
        // 条目缺 contest_slug 时回退请求竞赛;challenge_name 缺失回退 name
        assertEquals("projecteuler", items[1].contestSlug)
        assertEquals("fallback name", items[1].challengeName)
    }

    @Test
    fun `keyword is url encoded with percent-20 spaces`() = runBlocking {
        responses["/appsearch?contest_slug=master&query=solve me"] = ArrayDeque(listOf(appSearchJson()))

        api.searchChallengesWithKeyword("master", "solve me")

        assertTrue(recorded.single().rawQuery.endsWith("query=solve%20me"), "keyword 应 %20 编码: ${recorded.single().rawQuery}")
    }

    // -------------------------------------------------------------------
    // 题目详情(initialData URL 编码 JSON)与 CSRF
    // -------------------------------------------------------------------

    @Test
    fun `challenge detail parsed from url-encoded initial data`() = runBlocking {
        responses[MASTER_PROBLEM_PATH] = ArrayDeque(listOf(problemHtml()))

        val detail = api.fetchChallengeDetail("solve-me-first", "master")

        assertEquals(44, detail.id)
        assertEquals("Solve Me First", detail.name)
        assertEquals("easy", detail.difficultyName)
        assertEquals("<p>read two integers</p>", detail.bodyHtml)
    }

    @Test
    fun `csrf token extracted from meta tag`() = runBlocking {
        responses[MASTER_PROBLEM_PATH] = ArrayDeque(listOf(problemHtml()))

        assertEquals("csrf-1", api.fetchCsrfToken("solve-me-first", "master"))
    }

    @Test
    fun `problem page without initial data maps to OJException`() {
        responses[MASTER_PROBLEM_PATH] = ArrayDeque(listOf("<html><head></head><body>gone</body></html>"))
        assertFailsWith<OJException> {
            runBlocking { api.fetchChallengeDetail("solve-me-first", "master") }
        }
    }

    @Test
    fun `detail key mismatch maps to OJException`() {
        responses[MASTER_PROBLEM_PATH] = ArrayDeque(listOf(problemHtml(contestSlug = "projecteuler")))
        assertFailsWith<OJException> {
            runBlocking { api.fetchChallengeDetail("solve-me-first", "master") }
        }
    }

    // -------------------------------------------------------------------
    // 提交与轮询
    // -------------------------------------------------------------------

    @Test
    fun `submit posts legacy json body with csrf header`() = runBlocking {
        responses[MASTER_PROBLEM_PATH] = ArrayDeque(listOf(problemHtml()))
        responses[MASTER_SUBMIT_PATH] = ArrayDeque(listOf("""{"model":{"id":73920325}}"""))

        val submissionId = api.submitAnswer("solve-me-first", "master", language = "python3", code = "print(1)")

        assertEquals("73920325", submissionId)
        val submitCall = recorded.single { it.path == MASTER_SUBMIT_PATH }
        assertEquals("POST", submitCall.method)
        assertEquals(
            """{"language":"python3","contest_slug":"master","challenge_slug":"solve-me-first","code":"print(1)"}""",
            submitCall.body,
        )
        val submitRequest = engine.requestHistory.single { it.url.encodedPath == MASTER_SUBMIT_PATH.substringBefore('?') }
        assertEquals("csrf-1", submitRequest.headers["x-csrf-token"])
    }

    @Test
    fun `submit response without model id maps to OJException`() {
        responses[MASTER_PROBLEM_PATH] = ArrayDeque(listOf(problemHtml()))
        responses[MASTER_SUBMIT_PATH] = ArrayDeque(listOf("""{"model":{}}"""))
        assertFailsWith<OJException> {
            runBlocking { api.submitAnswer("solve-me-first", "master", "python3", "x") }
        }
    }

    @Test
    fun `submission result polling transitions judging to accepted`() = runBlocking {
        responses[MASTER_SUBMISSION_PATH] =
            ArrayDeque(
                listOf(
                    """{"model":{"id":73920325,"status":"Processing","score_processed":0}}""",
                    """{"model":{"id":73920325,"status":"Processing","score_processed":1}}""",
                    """{"model":{"id":73920325,"status":"Accepted","score_processed":3,"solved":10}}""",
                ),
            )

        val first = api.fetchSubmissionResult("master", "solve-me-first", "73920325").toSnapshot()
        val second = api.fetchSubmissionResult("master", "solve-me-first", "73920325").toSnapshot()
        val third = api.fetchSubmissionResult("master", "solve-me-first", "73920325").toSnapshot()

        assertEquals(SubmissionResult.Judging, first.result)
        assertEquals(SubmissionResult.Judging, second.result)
        assertEquals(SubmissionResult.Accepted, third.result)
        // 旧实现未提取耗时/内存快照,指标如实为 null
        assertNull(third.timeMs)
        assertNull(third.memoryKb)
    }

    @Test
    fun `terminal verdicts mapped per legacy contains order`() {
        fun result(status: String, scoreProcessed: Int = 3) = HackerRankApi.submissionStatusToResult(status, scoreProcessed)

        assertEquals(SubmissionResult.Judging, result("Accepted", scoreProcessed = 2))
        assertEquals(SubmissionResult.Accepted, result("Accepted"))
        assertEquals(SubmissionResult.WrongAnswer, result("Wrong Answer in testcase 3"))
        assertEquals(SubmissionResult.CompileError, result("Compilation error"))
        assertEquals(SubmissionResult.TimeLimitExceeded, result("Terminated due to timeout"))
        assertEquals(SubmissionResult.RuntimeError, result("Runtime Error"))
        // scoreProcessed 已终但 status 仍为 Processing:归 Unknown 终态保留原文,不轮询到超时
        assertEquals(SubmissionResult.Unknown("Processing"), result("Processing"))
        assertEquals(SubmissionResult.Unknown("Segmentation Fault"), result("Segmentation Fault"))
    }

    @Test
    fun `submission result without model maps to OJException`() {
        responses["$MASTER_SUBMIT_PATH/1"] = ArrayDeque(listOf("""{"error":"gone"}"""))
        assertFailsWith<OJException> {
            runBlocking { api.fetchSubmissionResult("master", "solve-me-first", "1") }
        }
    }

    // -------------------------------------------------------------------
    // 夹具
    // -------------------------------------------------------------------

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        const val LOGIN_CHECK_PATH = "/community/v1/promotion_slots/banner-dashboard"

        const val DASHBOARD_PATH = "/dashboard"

        const val MASTER_LIST_BASE = "/rest/contests/master/tracks/algorithms/challenges"

        const val MASTER_PROBLEM_PATH = "/challenges/solve-me-first/problem"

        const val MASTER_SUBMIT_PATH = "/rest/contests/master/challenges/solve-me-first/submissions"

        const val MASTER_SUBMISSION_PATH = "/rest/contests/master/challenges/solve-me-first/submissions/73920325"

        fun dashboardHtml(): String =
            """<html><body>
               <script id="__NEXT_DATA__" type="application/json">
               {"props":{"pageProps":{"prefetchedData":{
                 "profile":{"username":"kaguya","name":"Kaguya","avatar":"a.png"},
                 "contest":{"categories":[
                   {"id":8,"name":"Mathematics","slug":"mathematics","children":[{"name":"Number Theory","slug":"number-theory"}]},
                   {"id":6,"name":"Algorithms","slug":"algorithms","children":[]},
                   {"nope":true}
                 ]}}}}}
               </script></body></html>""".trimIndent().replace("\n", "")

        fun listJson(): String =
            """{"total":117,"models":[
               {"id":44,"slug":"solve-me-first","name":"Solve Me First","bookmarked":false,"solved":true,
                "attempted":true,"contest_slug":"master","user_score":10.0,"preview":null,"body_html":null,
                "difficulty":1.0,"difficulty_name":"easy","solved_score":10.0,"skill":null,
                "success_ratio":0.85,"total_count":1000,"solved_count":850,"max_score":10},
               {"id":2712,"slug":"simple-array-sum","name":"Simple Array Sum","bookmarked":null,"solved":null,
                "attempted":null,"user_score":0.0,"preview":null,"body_html":null,
                "difficulty":1.4,"difficulty_name":"easy","solved_score":10.0,"skill":null,
                "success_ratio":0.9,"total_count":500,"solved_count":450,"max_score":10}
               ]}""".trimIndent().replace("\n", "")

        fun appSearchJson(): String =
            """{"challenges":[
               {"contest_name":"master","contest_slug":"master","challenge_id":44,
                "challenge_name":"Solve Me First","challenge_slug":"solve-me-first","name":"Solve Me First"},
               {"contest_name":"projecteuler","contest_slug":"projecteuler","challenge_slug":"cigrtsxnngsq",
                "challenge_id":190001,"challenge_name":"","name":"fallback name"}
               ]}""".trimIndent().replace("\n", "")

        fun problemHtml(contestSlug: String = "master"): String {
            val detail =
                """{"community":{"challenges":{"challenge":{"$contestSlug/solve-me-first":{"detail":{
                   "id":44,"slug":"solve-me-first","name":"Solve Me First","contest_slug":"master",
                   "difficulty_name":"easy","max_score":10,"preview":null,
                   "body_html":"<p>read two integers</p>",
                   "cpp_template":"#include <bits/stdc++.h>","python3_template":"import sys"
                   }}}}}}""".trimIndent().replace("\n", "")
            return """<html><head>
                   <meta id="csrf-token" name="csrf-token" content="csrf-1">
                   </head><body>
                   <script id="initialData" type="application/json">${urlEncode(detail)}</script>
                   </body></html>""".trimIndent().replace("\n", "")
        }

        fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
