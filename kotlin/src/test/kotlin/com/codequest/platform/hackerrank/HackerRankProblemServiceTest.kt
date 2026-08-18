package com.codequest.platform.hackerrank

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.ProblemFilter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP27:HackerRankProblemService——无 keyword 走 REST 题库分页
 * (difficulty/tags 维度映射)、有 keyword 走 appsearch 双竞赛并查 + 详情补难度
 * (旧 KeywordQueryPresenter 形态)、详情 body_html → HTML 题面;
 * remoteId 约定(master 裸 slug / projecteuler 前缀)。经 FakeOJTransport,不上网络。
 */
class HackerRankProblemServiceTest {
    private fun transport(): FakeOJTransport = FakeOJTransport()

    private fun listResponse(): String =
        """{"total":2,"models":[
           {"id":44,"slug":"solve-me-first","name":"Solve Me First","contest_slug":"master",
            "difficulty_name":"easy","max_score":10},
           {"id":190001,"slug":"cigrtsxnngsq","name":"PE 1","contest_slug":"projecteuler",
            "difficulty_name":"hard","max_score":100}
           ]}""".trimIndent().replace("\n", "")

    @Test
    fun `list query maps offset limit difficulty and tags to legacy params`() = runBlocking {
        val transport = transport()
        transport.handler = { request ->
            if (request.url ==
                "https://www.hackerrank.com/rest/contests/master/tracks/algorithms/challenges" +
                "?offset=10&limit=5&filters[subdomains][]=recursion&filters[difficulty][]=easy"
            ) {
                listResponse()
            } else {
                throw OJException("unexpected ${request.method} ${request.url}")
            }
        }
        val service = HackerRankProblemService(HackerRankApi(transport))

        val items =
            service
                .queryProblems(
                    ProblemFilter(
                        difficulty = Difficulty("Easy"),
                        tags = listOf("recursion"),
                        limit = 5,
                        offset = 10,
                    ),
                ).toList()

        assertEquals(2, items.size)
        assertEquals("solve-me-first", items[0].remoteId)
        assertEquals("Solve Me First", items[0].title)
        assertEquals("easy", items[0].difficulty.value)
        assertEquals("https://www.hackerrank.com/challenges/solve-me-first/problem", items[0].url)
        // projecteuler 竞赛题 remoteId 带前缀,URL 带 contests 段
        assertEquals("projecteuler/cigrtsxnngsq", items[1].remoteId)
        assertEquals("https://www.hackerrank.com/contests/projecteuler/challenges/cigrtsxnngsq/problem", items[1].url)
    }

    @Test
    fun `keyword query merges both contests then details each hit`() = runBlocking {
        val transport = transport()
        val problemUrl = "https://www.hackerrank.com/challenges/solve-me-first/problem"
        transport.handler = { request ->
            when (request.url) {
                "https://www.hackerrank.com/appsearch?contest_slug=master&query=solve" ->
                    """{"challenges":[{"contest_slug":"master","challenge_slug":"solve-me-first",
                        "challenge_name":"Solve Me First","name":"x"}]}""".trimIndent().replace("\n", "")
                "https://www.hackerrank.com/appsearch?contest_slug=projecteuler&query=solve" ->
                    """{"challenges":[{"contest_slug":"projecteuler","challenge_slug":"pe-1",
                        "challenge_name":"PE One","name":"y"},
                        {"contest_slug":"projecteuler","challenge_slug":"broken","challenge_name":"Broken"}]}"""
                        .trimIndent().replace("\n", "")
                "https://www.hackerrank.com/contests/projecteuler/challenges/pe-1/problem" ->
                    problemHtml("projecteuler/pe-1", "PE One", "easy")
                problemUrl -> problemHtml("master/solve-me-first", "Solve Me First", "medium")
                // 详情失败项跳过(旧实现 recover 跳过事实)
                else -> throw OJException("unexpected ${request.url}")
            }
        }
        val service = HackerRankProblemService(HackerRankApi(transport))

        val items = service.queryProblems(ProblemFilter(keyword = "solve", limit = 10)).toList()

        assertEquals(listOf("solve-me-first", "projecteuler/pe-1", "projecteuler/broken"), items.map { it.remoteId })
        // 详情补齐难度;失败项回退 appsearch 数据 + unknown 难度
        assertEquals("medium", items[0].difficulty.value)
        assertEquals("easy", items[1].difficulty.value)
        assertEquals("unknown", items[2].difficulty.value)
        assertEquals("Broken", items[2].title)
    }

    @Test
    fun `keyword query applies offset and limit window in memory`() = runBlocking {
        val transport = transport()
        transport.handler = { request ->
            if (request.url.startsWith("https://www.hackerrank.com/appsearch")) {
                """{"challenges":[
                   {"contest_slug":"master","challenge_slug":"a","challenge_name":"A"},
                   {"contest_slug":"master","challenge_slug":"b","challenge_name":"B"},
                   {"contest_slug":"master","challenge_slug":"c","challenge_name":"C"}
                   ]}""".trimIndent().replace("\n", "")
            } else {
                throw OJException("unexpected ${request.url}")
            }
        }
        val service = HackerRankProblemService(HackerRankApi(transport))

        val items = service.queryProblems(ProblemFilter(keyword = "x", limit = 1, offset = 1)).toList()

        assertEquals(listOf("b"), items.map { it.remoteId })
    }

    @Test
    fun `problem detail returns body html as html content`() = runBlocking {
        val transport = transport()
        transport.handler = { _ ->
            problemHtml("master/solve-me-first", "Solve Me First", "easy", bodyHtml = "<p>read two integers</p>")
        }
        val service = HackerRankProblemService(HackerRankApi(transport))

        val statement = service.fetchProblemDetail("solve-me-first")

        assertEquals("solve-me-first", statement.remoteId)
        assertEquals("<p>read two integers</p>", statement.content)
        assertEquals(ContentFormat.HTML, statement.contentFormat)
    }

    @Test
    fun `problem detail for projecteuler remote id hits contest problem page`() = runBlocking {
        val transport = transport()
        val peUrl = "https://www.hackerrank.com/contests/projecteuler/challenges/pe-1/problem"
        transport.handler = { request ->
            if (request.url == peUrl) problemHtml("projecteuler/pe-1", "PE One", "easy") else throw OJException("unexpected ${request.url}")
        }
        val service = HackerRankProblemService(HackerRankApi(transport))

        val statement = service.fetchProblemDetail("projecteuler/pe-1")

        assertEquals("projecteuler/pe-1", statement.remoteId)
        assertTrue(statement.content.isNotEmpty())
    }

    @Test
    fun `problem detail with empty body html maps to OJException`() {
        val transport = transport()
        transport.handler = { _ -> problemHtml("master/solve-me-first", "Solve Me First", "easy", bodyHtml = "") }
        val service = HackerRankProblemService(HackerRankApi(transport))
        assertFailsWith<OJException> {
            runBlocking { service.fetchProblemDetail("solve-me-first") }
        }
    }

    @Test
    fun `remote id parsing follows master bare and prefixed conventions`() {
        assertEquals("master" to "solve-me-first", parseChallengeRemoteId("solve-me-first"))
        assertEquals("projecteuler" to "pe-1", parseChallengeRemoteId("projecteuler/pe-1"))
        assertFailsWith<OJException> { parseChallengeRemoteId("") }
        assertFailsWith<OJException> { parseChallengeRemoteId("projecteuler/") }
        assertEquals("solve-me-first", hackerrankChallengeRemoteId("master", "solve-me-first"))
        assertEquals("projecteuler/pe-1", hackerrankChallengeRemoteId("projecteuler", "pe-1"))
    }

    private fun problemHtml(
        challengeKey: String,
        name: String,
        difficulty: String,
        bodyHtml: String = "<p>default body</p>",
    ): String {
        val detail =
            """{"community":{"challenges":{"challenge":{"$challengeKey":{"detail":{
               "id":1,"slug":"${challengeKey.substringAfter('/')}","name":"$name",
               "contest_slug":"${challengeKey.substringBefore('/')}",
               "difficulty_name":"$difficulty","max_score":10,"body_html":"$bodyHtml"
               }}}}}}""".trimIndent().replace("\n", "")
        return """<html><head><meta id="csrf-token" content="csrf-1"></head><body>
               <script id="initialData" type="application/json">${urlEncode(detail)}</script>
               </body></html>""".trimIndent().replace("\n", "")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
