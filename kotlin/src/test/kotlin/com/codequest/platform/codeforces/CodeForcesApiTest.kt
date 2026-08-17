package com.codequest.platform.codeforces

import com.codequest.core.model.OJException
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP24:CodeForcesApi REST 层 MockEngine 回放夹具——走**真实栈**
 * (MockEngine → OJHttpClient → HttpOJTransport),断言公开 REST 端点的
 * 请求 URL 与响应解析(problemset.problems 双调用/acmsguru 99999 修正、
 * contest.list、contest.standings、题面页 URL、FAILED 错误信封)。
 * 全程不上网络。表单 POST 端点(提交/轮询)的传输缺口见 FormPostCapable,
 * 由 CodeForcesSubmissionServiceTest 以假传输覆盖。
 */
class CodeForcesApiTest {
    private val requested = mutableListOf<String>()

    /** 路径 → 响应体(测试内按需覆盖)。 */
    private val responses = mutableMapOf<String, String>()

    private val engine =
        MockEngine { request ->
            val path = request.url.encodedPath + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            requested += "${request.method.value} $path"
            // 先查「方法 + 完整路径(含 query)」键(区分 problemset 默认/acmsguru),再回退裸 path
            val content =
                responses["${request.method.value} $path"]
                    ?: responses[request.url.encodedPath]
                    ?: error("未脚本化请求: ${request.method.value} $path")
            respond(content = content, status = HttpStatusCode.OK)
        }

    private lateinit var client: OJHttpClient
    private lateinit var api: CodeForcesApi

    @BeforeTest
    fun setUp() {
        client = OJHttpClient(engine)
        api = CodeForcesApi(HttpOJTransport(client))
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private val problemsetBody =
        """
        {"status":"OK","result":{
          "problems":[
            {"contestId":1927,"problemsetName":null,"index":"A","name":"Satyam and Counting","type":"PROGRAMMING","points":500.0,"rating":800,"tags":["math","implementation"]},
            {"contestId":1926,"problemsetName":null,"index":"B2","name":"Vlad and Shapes","type":"PROGRAMMING","points":1000.0,"rating":1100,"tags":["constructive algorithms"]},
            {"contestId":null,"problemsetName":null,"index":"A","name":"Legacy no contest","type":"PROGRAMMING","points":null,"rating":null,"tags":[]}
          ],
          "problemStatistics":[
            {"contestId":1927,"index":"A","solvedCount":12345},
            {"contestId":1926,"index":"B2","solvedCount":999}
          ]
        }}
        """.trimIndent()

    private val acmsguruBody =
        """
        {"status":"OK","result":{
          "problems":[{"contestId":null,"problemsetName":"acmsguru","index":"A","name":"Spreadsheets","type":"PROGRAMMING","points":null,"rating":null,"tags":[]}],
          "problemStatistics":[{"contestId":null,"index":"A","solvedCount":10}]
        }}
        """.trimIndent()

    @Test
    fun `problemset merges two calls and fixes acmsguru contest id to 99999`() = runBlocking {
        responses["/api/problemset.problems"] = problemsetBody
        // 带 query 的 acmsguru 调用按 path 分发(MockEngine encodedQuery 已并入 key 优先匹配)
        responses["GET /api/problemset.problems?problemsetName=acmsguru"] = acmsguruBody

        val problems = api.fetchProblemset()

        assertEquals(4, problems.size)
        assertEquals("1927A", problems[0].remoteId())
        assertEquals("1926B2", problems[1].remoteId())
        assertEquals(800, problems[0].rating)
        assertEquals(listOf("math", "implementation"), problems[0].tags)
        assertEquals(500.0, problems[0].points)
        // 主响应里 contestId 为 null 的题(协议允许)保留 null → remoteId 前缀空
        assertNull(problems[2].contestId)
        // acmsguru:99999 合成 contestId(旧实现事实)
        assertEquals(ACMSGURU_CONTEST_ID, problems[3].contestId)
        assertEquals("99999A", problems[3].remoteId())
        // 两次请求:默认 + problemsetName=acmsguru
        assertTrue(requested.any { it == "GET /api/problemset.problems" })
        assertTrue(requested.any { it.endsWith("problemsetName=acmsguru") })
    }

    @Test
    fun `contest list parses fields and gym=false param`() = runBlocking {
        responses["/api/contest.list"] =
            """
            {"status":"OK","result":[
              {"id":1900,"name":"Codeforces Round 950 (Div. 2)","type":"CF","phase":"BEFORE","frozen":false,
               "durationSeconds":7200,"startTimeSeconds":1755600000,"relativeTimeSeconds":-100000},
              {"id":1899,"name":"Educational Round","type":"ICPC","phase":"CODING","frozen":false,
               "durationSeconds":9000,"startTimeSeconds":1755500000,"relativeTimeSeconds":3000},
              {"id":1898,"name":"Old Round","type":"CF","phase":"FINISHED","frozen":true,
               "durationSeconds":7200,"startTimeSeconds":1755000000,"relativeTimeSeconds":700000}
            ]}
            """.trimIndent()

        val contests = api.contestList()

        assertEquals(listOf(1900L, 1899L, 1898L), contests.map { it.id })
        assertEquals("BEFORE", contests[0].phase)
        assertEquals(7200L, contests[0].durationSeconds)
        assertEquals(1755600000L, contests[0].startTimeSeconds)
        assertTrue(requested.single().endsWith("contest.list?gym=false"))
    }

    @Test
    fun `contest standings parses problems and rows`() = runBlocking {
        responses["/api/contest.standings"] =
            """
            {"status":"OK","result":{
              "contest":{"id":1899,"name":"Educational Round","type":"ICPC","phase":"CODING","frozen":false,
                "durationSeconds":9000,"startTimeSeconds":1755500000,"relativeTimeSeconds":3000},
              "problems":[
                {"contestId":1899,"index":"A","name":"Typical Problem","type":"PROGRAMMING","points":500.0,"rating":800,"tags":[]},
                {"contestId":1899,"index":"B","name":"Harder Problem","type":"PROGRAMMING","points":1000.0,"rating":1500,"tags":[]}
              ],
              "rows":[
                {"party":{"members":[{"handle":"tourist"}],"participantType":"CONTESTANT"},"rank":1,"points":4,"penalty":120,
                 "problemResults":[{"points":500,"rejectedAttemptCount":0,"type":"FINAL"},{"points":1000,"rejectedAttemptCount":0,"type":"FINAL"}]},
                {"party":{"members":[{"handle":"benq"}],"participantType":"CONTESTANT"},"rank":2,"points":3,"penalty":300,
                 "problemResults":[]}
              ]
            }}
            """.trimIndent()

        val standings = api.contestStandings(1899)

        assertEquals(listOf("A" to "Typical Problem", "B" to "Harder Problem"), standings.problems.map { it.index to it.name })
        assertEquals(listOf("tourist" to 1, "benq" to 2), standings.rows.map { it.handle to it.rank })
        assertEquals(4.0, standings.rows[0].points)
        assertTrue(requested.single().endsWith("contest.standings?contestId=1899"))
    }

    @Test
    fun `problem page html is fetched from problemset url`() = runBlocking {
        responses["/problemset/problem/1927/A"] =
            """<div id="pageContent"><div class="problem-statement"><div class="ttypography">Statement body</div></div></div>"""

        val html = api.fetchProblemPageHtml(1927, "A")

        assertTrue(html.contains("problem-statement"))
        assertTrue(requested.single().endsWith("/problemset/problem/1927/A"))
    }

    @Test
    fun `settings page html exposes login state source`() = runBlocking {
        responses["/settings/general"] =
            """<div class="personal-sidebar"><div class="avatar"><a href="/p/x"><img/></a> <a href="/p/tourist">tourist</a></div></div>
              <div class="userbox"><div class="info">tourist</div></div>"""

        val html = api.fetchSettingsPageHtml()

        assertTrue(CodeForcesHtml.hasDivElementWithClass(html, "userbox"))
        assertEquals("tourist", CodeForcesHtml.extractSidebarUsername(html))
    }

    @Test
    fun `FAILED envelope maps to OJException with api comment`() {
        responses["/api/contest.standings"] =
            """{"status":"FAILED","comment":"contest 999999 has not started"}"""

        val error =
            assertFailsWith<OJException> {
                runBlocking { api.contestStandings(999999) }
            }
        assertTrue(error.message!!.contains("has not started"))
    }

    @Test
    fun `non-OK status or missing result maps to OJException`() {
        responses["/api/contest.list"] = """{"status":"WEIRD"}"""
        assertFailsWith<OJException> {
            runBlocking { api.contestList() }
        }
        responses["/api/contest.list"] = """{"status":"OK"}"""
        assertFailsWith<OJException> {
            runBlocking { api.contestList() }
        }
    }
}
