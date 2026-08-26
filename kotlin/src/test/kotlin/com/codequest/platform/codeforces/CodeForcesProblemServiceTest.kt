package com.codequest.platform.codeforces

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.ProblemFilter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP24:CodeForcesProblemService——全量题库 + 内存过滤
 * (keyword/difficulty/tags/offset-limit 窗口)、题面 HTML 详情(acmsguru URL 分支)。
 * CF REST 无服务端过滤参数(旧实现事实),过滤在本层完成。
 */
class CodeForcesProblemServiceTest {
    private fun problemsetBody(): String =
        """
        {"status":"OK","result":{"problems":[
          {"contestId":1927,"index":"A","name":"Satyam and Counting","type":"PROGRAMMING","points":500.0,"rating":800,"tags":["math","implementation"]},
          {"contestId":1927,"index":"B","name":"Satyam and Party","type":"PROGRAMMING","points":1000.0,"rating":1200,"tags":["greedy","math"]},
          {"contestId":1926,"index":"A","name":"Vlad and Array","type":"PROGRAMMING","points":500.0,"rating":1600,"tags":["dp"]},
          {"contestId":1926,"index":"B","name":"Vlad and Boring String","type":"PROGRAMMING","points":750.0,"rating":1900,"tags":["strings","two pointers"]},
          {"contestId":null,"index":"Z","name":"Unrated Problem","type":"PROGRAMMING","points":null,"rating":null,"tags":["bitmasks"]}
        ],"problemStatistics":[]}}
        """.trimIndent()

    private val acmsguruBody =
        """
        {"status":"OK","result":{"problems":[
          {"contestId":null,"problemsetName":"acmsguru","index":"A","name":"Spreadsheets","type":"PROGRAMMING","points":null,"rating":null,"tags":[]}
        ],"problemStatistics":[]}}
        """.trimIndent()

    private fun transportWith(problemset: String = problemsetBody()): FakeOJTransport {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when {
                request.method == "GET" && request.url.contains("problemsetName=acmsguru") -> acmsguruBody
                request.method == "GET" && request.url.contains("/api/problemset.problems") -> problemset
                request.method == "GET" && request.url.endsWith("/problemset/problem/1927/A") ->
                    """<div id="pageContent"><div class="problem-statement"><div class="ttypography">body</div></div></div>"""
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        return transport
    }

    private fun service(transport: FakeOJTransport) = CodeForcesProblemService(CodeForcesApi(transport))

    @Test
    fun `queryProblems emits remoteId title rating difficulty and tags`() = runBlocking {
        val problems = service(transportWith()).queryProblems(ProblemFilter(limit = 3)).toList()

        assertEquals(listOf("1927A", "1927B", "1926A"), problems.map { it.remoteId })
        assertEquals(Difficulty("800"), problems[0].difficulty)
        assertEquals(Difficulty("1600"), problems[2].difficulty)
        assertEquals(listOf("math", "implementation"), problems[0].tags.map { it.name })
        assertEquals("https://codeforces.com/problemset/problem/1927/A", problems[0].url)
    }

    @Test
    fun `unrated problem keeps raw difficulty and null contest prefix`() = runBlocking {
        val problems = service(transportWith()).queryProblems(ProblemFilter(limit = 10)).toList()

        // 主响应 contestId=null 条目保留空前缀(与旧 DB contestidindex 拼接一致)
        assertEquals("Z", problems[4].remoteId)
        assertEquals(Difficulty("unrated"), problems[4].difficulty)
        // acmsguru 合并条目:99999 前缀(旧实现 copy(contestId=99999) 事实)
        assertEquals("99999A", problems.last().remoteId)
        assertEquals(Difficulty("unrated"), problems.last().difficulty)
    }

    @Test
    fun `keyword filter matches name case-insensitively`() = runBlocking {
        val problems =
            service(transportWith())
                .queryProblems(ProblemFilter(keyword = "vlad", limit = 10))
                .toList()

        assertEquals(listOf("1926A", "1926B"), problems.map { it.remoteId })
    }

    @Test
    fun `difficulty filter supports exact rating and legacy band words`() = runBlocking {
        // 精确 rating(新 Difficulty 原始串语义)
        assertEquals(
            listOf("1927A"),
            service(transportWith())
                .queryProblems(ProblemFilter(difficulty = Difficulty("800"), limit = 10))
                .map { it.remoteId }
                .toList(),
        )
        // 旧五档词 → 旧 codeForcesDifficultyToRatingRange 区间事实
        assertEquals(
            listOf("1927B"),
            service(transportWith())
                .queryProblems(ProblemFilter(difficulty = Difficulty("medium"), limit = 10))
                .map { it.remoteId }
                .toList(),
        )
        assertEquals(
            listOf("1926B"),
            service(transportWith())
                .queryProblems(ProblemFilter(difficulty = Difficulty("advanced"), limit = 10))
                .map { it.remoteId }
                .toList(),
        )
    }

    @Test
    fun `tag filter matches any tag`() = runBlocking {
        val problems =
            service(transportWith())
                .queryProblems(ProblemFilter(tags = listOf("dp", "strings"), limit = 10))
                .toList()

        assertEquals(listOf("1926A", "1926B"), problems.map { it.remoteId })
    }

    @Test
    fun `offset and limit window applies after filtering`() = runBlocking {
        val problems =
            service(transportWith())
                .queryProblems(ProblemFilter(limit = 2, offset = 2))
                .toList()

        assertEquals(listOf("1926A", "1926B"), problems.map { it.remoteId })
    }

    @Test
    fun `fetchProblemDetail extracts problem statement html`() = runBlocking {
        val statement = service(transportWith()).fetchProblemDetail("1927A")

        assertEquals("1927A", statement.remoteId)
        assertTrue(statement.content.contains("problem-statement"))
        assertEquals(ContentFormat.HTML, statement.contentFormat)
    }

    @Test
    fun `acmsguru remote id routes to problemsets url`() = runBlocking {
        val transport = transportWith()
        transport.handler = { request ->
            when {
                request.method == "GET" && request.url.contains("problemsetName=acmsguru") -> acmsguruBody
                request.method == "GET" && request.url.contains("/api/problemset.problems") -> problemsetBody()
                request.method == "GET" && request.url.endsWith("/problemsets/acmsguru/problem/99999/A") ->
                    """<div id="pageContent"><div class="problem-statement">acmsguru</div></div>"""
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }

        val statement = service(transport).fetchProblemDetail("99999A")

        assertTrue(statement.content.contains("acmsguru"))
        assertTrue(transport.recorded.any { it.url.endsWith("/problemsets/acmsguru/problem/99999/A") })
    }

    @Test
    fun `invalid remote id or missing statement throws OJException`() {
        assertFailsWith<OJException> {
            runBlocking { service(transportWith()).fetchProblemDetail("not-a-cf-id") }
        }
        val transport = transportWith()
        transport.handler = { request ->
            when {
                request.method == "GET" && request.url.contains("/api/problemset.problems") -> problemsetBody()
                else -> "<html>page without statement</html>"
            }
        }
        assertFailsWith<OJException> {
            runBlocking { service(transport).fetchProblemDetail("1927A") }
        }
    }
}
