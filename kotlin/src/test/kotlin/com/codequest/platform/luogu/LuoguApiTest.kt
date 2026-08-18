package com.codequest.platform.luogu

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import com.codequest.platform.leetcode.FakeOJTransport
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP26:LuoguApi MockEngine 回放夹具——走**真实栈**
 * (MockEngine → OJHttpClient → HttpOJTransport),断言 lentille-context 提取
 * (列表/详情/题解)、题面 Markdown 拼装、提交 JSON body 与公共头**逐字**
 * (`{lang,enableO2,code}` + x-csrf-token/x-requested-with/Referer)、record
 * decodeURIComponent 判定与 503 重试;C3VK 反爬通道经 FakeOJTransport 断言
 * cookie 注入。全程不上网络。
 */
class LuoguApiTest {
    private data class Recorded(
        val method: String,
        val path: String,
        val body: String,
    )

    private val recorded = mutableListOf<Recorded>()

    /** 路径(含 query)→ 响应体;503 场景单独脚本化。 */
    private val responses = mutableMapOf<String, String>()

    private val engine =
        MockEngine { request ->
            val path =
                request.url.encodedPath +
                    (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            recorded += Recorded(
                request.method.value,
                path,
                runCatching { request.body.toByteArray().decodeToString() }.getOrDefault(""),
            )
            val content = responses[path] ?: error("未脚本化请求: ${request.method.value} $path")
            respond(content = content, status = HttpStatusCode.OK)
        }

    private lateinit var client: OJHttpClient
    private lateinit var api: LuoguApi

    @BeforeTest
    fun setUp() {
        client = OJHttpClient(engine)
        api = LuoguApi(HttpOJTransport(client))
        responses[HOME_PATH] = HOME_HTML
    }

    @AfterTest
    fun tearDown() {
        client.close()
        recorded.clear()
    }

    @Test
    fun `problem page parsed from lentille json with name and legacy title fallback`() = runBlocking {
        responses[LIST_PAGE1_PATH] = listHtml()

        val page = api.fetchProblemPage(page = 1)

        assertEquals(17296, page.count)
        assertEquals(50, page.perPage)
        assertEquals(2, page.items.size)
        assertEquals("P1000", page.items[0].pid)
        assertEquals("超级玛丽游戏", page.items[0].name)
        assertEquals(1, page.items[0].difficulty)
        // 洛谷现行字段为 name;旧 case class 为 title,两者兼容
        assertEquals("legacy title field", page.items[1].name)
    }

    @Test
    fun `problem page sends difficulty and encoded keyword params`() = runBlocking {
        responses["/problem/list?page=2&_contentOnly=1&difficulty=2&keyword=dp"] = listHtml()

        api.fetchProblemPage(page = 2, difficulty = 2, keyword = "dp")

        assertTrue(recorded.any { it.path == "/problem/list?page=2&_contentOnly=1&difficulty=2&keyword=dp" })
    }

    @Test
    fun `problem detail markdown assembled from legacy field layout`() = runBlocking {
        responses["/problem/P1001"] = problemHtml()

        val data = api.fetchProblemData("P1001")

        assertEquals("P1001", data.pid)
        assertEquals("A+B Problem", data.title)
        assertEquals(listOf("28", "7"), data.acceptLanguages)
        assertEquals(listOf("1 2" to "3"), data.samples)
        val md = data.markdown
        assertTrue(md.startsWith("# P1001 A+B Problem"), "md 应以标题行开头: $md")
        assertTrue("## 时间限制\n\n1.0s" in md, "时间限制 1000ms → 1.0s")
        assertTrue("## 内存限制\n\n256.0MB" in md, "内存 262144KB → 256.0MB")
        assertTrue("## 题目描述\n\n计算 ${'$'}a+b${'$'}" in md)
        assertTrue("## 输入格式" in md)
        assertTrue("## 输出格式" in md)
        assertTrue("## 题意翻译\n\nEnglish desc" in md)
        assertTrue("## 样例1" in md && "#### 输入\n\n```\n1 2\n```" in md && "#### 输出\n\n```\n3\n```" in md)
        assertTrue("## 说明/提示" in md)
    }

    @Test
    fun `solution list parsed with title fallback to author name`() = runBlocking {
        responses["/problem/solution/P1001?_contentOnly=1"] = solutionHtml()

        val solutions = api.fetchSolutionList("P1001")

        assertEquals(2, solutions.size)
        assertEquals("123456", solutions[0].id)
        assertEquals("一份题解", solutions[0].title)
        // title 字段缺失时回退作者名
        assertEquals("bob", solutions[1].title)
    }

    @Test
    fun `submit posts legacy json body with csrf referer and requested-with headers`() = runBlocking {
        responses["/fe/api/problem/submit/P1001"] = """{"rid":9876543}"""

        val rid = api.submitCode(pid = "P1001", langId = "28", code = "int main(){}")

        assertEquals("9876543", rid)
        val submitCall = recorded.single { it.path == "/fe/api/problem/submit/P1001" }
        assertEquals("POST", submitCall.method)
        assertEquals("""{"lang":28,"enableO2":1,"code":"int main(){}"}""", submitCall.body)
        val submitRequest = engine.requestHistory.single { it.url.encodedPath == "/fe/api/problem/submit/P1001" }
        assertEquals("csrf-1", submitRequest.headers["x-csrf-token"])
        assertEquals("XMLHttpRequest", submitRequest.headers["x-requested-with"])
        assertEquals("https://www.luogu.com.cn/problem/P1001", submitRequest.headers["Referer"])
    }

    @Test
    fun `submit response without rid maps to OJException`() {
        responses["/fe/api/problem/submit/P1001"] = """{"errorMessage":"nope"}"""
        assertFailsWith<OJException> {
            runBlocking { api.submitCode("P1001", "28", "x") }
        }
    }

    @Test
    fun `record status mapped from url-encoded json`() = runBlocking {
        responses["/record/9876543"] = recordHtml("""{"currentData":{"record":{"status":12,"detail":{}}}}""")

        val snapshot = api.fetchRecordStatus("9876543")

        assertEquals(SubmissionResult.Accepted, snapshot.result)
        assertEquals(null, snapshot.timeMs)
        assertEquals(null, snapshot.memoryKb)
        // record 拉取不带公共头(旧实现事实:getSubmitAnswerResult 直拉,无 commonHeaders)
        val recordRequest = engine.requestHistory.single { it.url.encodedPath == "/record/9876543" }
        assertEquals(null, recordRequest.headers["x-csrf-token"])
    }

    @Test
    fun `unspecified record status falls back to concrete test case verdict`() = runBlocking {
        val json =
            """
            {"currentData":{"record":{"status":6,"detail":{"judgeResult":{"subtasks":[
              {"testCases":[{"status":12},{"status":5}]},{"testCases":[{"status":5}]}
            ]}}}}}
            """.trimIndent().replace("\n", "")
        responses["/record/1"] = recordHtml(json)

        val snapshot = api.fetchRecordStatus("1")

        assertEquals(SubmissionResult.TimeLimitExceeded, snapshot.result)
    }

    @Test
    fun `record 503 is retried with legacy 2s backoff`() = runBlocking {
        val delays = mutableListOf<Long>()
        var recordCalls = 0
        val flakyEngine =
            MockEngine { request ->
                val path = request.url.encodedPath
                if (path == HOME_PATH) {
                    return@MockEngine respond(HOME_HTML, HttpStatusCode.OK)
                }
                recordCalls++
                if (recordCalls <= 2) {
                    respond("service unavailable", HttpStatusCode.ServiceUnavailable)
                } else {
                    respond(recordHtml("""{"currentData":{"record":{"status":12,"detail":{}}}}"""), HttpStatusCode.OK)
                }
            }
        val flakyClient = OJHttpClient(flakyEngine)
        try {
            val snapshot = LuoguApi(HttpOJTransport(flakyClient), retryDelay = { ms -> delays += ms }).fetchRecordStatus("9")
            assertEquals(SubmissionResult.Accepted, snapshot.result)
            assertEquals(listOf(2_000L, 2_000L), delays, "旧实现 sleep 2s 重试事实")
        } finally {
            flakyClient.close()
        }
    }

    @Test
    fun `c3vk anti-crawl round injects cookie and retries home`() = runBlocking {
        val transport = FakeOJTransport()
        var homeCalls = 0
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> {
                    homeCalls++
                    if (homeCalls == 1) "blocked C3VK=abc123; retry" else HOME_HTML
                }
                else -> HOME_HTML
            }
        }
        val fakeApi = LuoguApi(transport)

        val token = fakeApi.ensureCsrfToken()

        assertEquals("csrf-1", token)
        assertEquals(2, homeCalls, "首页应被反爬重试拉取两次")
        assertEquals("abc123", transport.cookieJars[LUOGU_PLATFORM_ID]?.get("C3VK"))
    }

    @Test
    fun `missing lentille context maps to OJException`() {
        responses["/problem/P9999"] = "<html><body>blocked</body></html>"
        assertFailsWith<OJException> {
            runBlocking { api.fetchProblemData("P9999") }
        }
    }

    // -------------------------------------------------------------------
    // 夹具
    // -------------------------------------------------------------------

    private companion object {
        const val HOME_PATH = "/"

        val HOME_HTML = """<html><head><meta name="csrf-token" content="csrf-1"></head><body>ok</body></html>"""

        const val LIST_PAGE1_PATH = "/problem/list?page=1&_contentOnly=1"

        fun listHtml(): String =
            """<html><head><title>题库 - 洛谷</title></head><body>
            <script id="lentille-context" type="application/json">
            {"instance":"main","template":"problem.list","status":200,"locale":"zh-CN",
             "data":{"problems":{"perPage":50,"count":17296,"result":[
               {"pid":"P1000","type":"P","name":"超级玛丽游戏","difficulty":1,"tags":[2,108],"totalSubmit":1822699,"totalAccepted":698809,"flag":5},
               {"pid":"CF1A","type":"CF","title":"legacy title field","difficulty":2,"tags":[],"totalSubmit":10,"totalAccepted":5,"flag":0}
             ]}}}
            </script></body></html>""".trimIndent().replace("\n", "")

        fun problemHtml(): String =
            """<html><head><title>A+B Problem - 洛谷</title></head><body>
            <script id="lentille-context" type="application/json">
            {"data":{"problem":{"pid":"P1001","title":"A+B Problem","acceptLanguages":[28,7],
             "translation":"English desc",
             "content":{"description":"计算 ${'$'}a+b${'$'}","formatI":"两整数","formatO":"一个整数","hint":"注意范围"},
             "limits":{"time":[1000],"memory":[262144]},"samples":[["1 2","3"]]}}}
            </script></body></html>""".trimIndent().replace("\n", "")

        fun solutionHtml(): String =
            """<html><head><title>题解 - 洛谷</title></head><body>
            <script id="lentille-context" type="application/json">
            {"data":{"solutions":{"count":42,"perPage":10,"result":[
               {"id":123456,"title":"一份题解","author":{"uid":1,"name":"alice"}},
               {"id":123457,"author":{"uid":2,"name":"bob"}}
            ]}}}
            </script></body></html>""".trimIndent().replace("\n", "")

        fun recordHtml(json: String): String =
            """<html><body><script>var feInjection = decodeURIComponent("${urlEncode(json)}");</script></body></html>"""

        fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
