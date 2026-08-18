package com.codequest.platform.luogu

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.ProblemFilter
import com.codequest.spi.SolutionArticleRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [unit] WP26:洛谷题目查询服务——列表 offset/limit 分页游走与难度/关键字参数
 * 映射、题面 MARKDOWN 输出、题解引用列表(URL 锚点),经 FakeOJTransport,不上网络。
 */
class LuoguProblemServiceTest {
    private val homeHtml = """<html><head><meta name="csrf-token" content="csrf-1"></head></html>"""

    private fun itemJson(
        pid: String,
        name: String,
        difficulty: Int,
    ): String = """{"pid":"$pid","type":"P","name":"$name","difficulty":$difficulty,"totalSubmit":10,"totalAccepted":5,"flag":0}"""

    private fun listPage(
        count: Int,
        pids: List<Pair<String, Int>>,
    ): String =
        """<html><body><script id="lentille-context" type="application/json">
           {"data":{"problems":{"perPage":2,"count":$count,"result":[${pids.joinToString(",") { (pid, diff) -> itemJson(pid, pid, diff) }}]}}}
           </script></body></html>""".trimIndent().replace("\n", "")

    private fun service(script: MutableMap<String, String>): LuoguProblemService {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when {
                request.url == LUOGU_HOME_URL -> homeHtml
                request.url.startsWith("https://www.luogu.com.cn/problem/list") ->
                    script[request.url.substringAfter("luogu.com.cn")] ?: throw OJException("unexpected ${request.url}")
                else -> throw OJException("unexpected ${request.url}")
            }
        }
        return LuoguProblemService(LuoguApi(transport))
    }

    @Test
    fun `query problems walks pages by offset and limit`() = runBlocking {
        val script =
            mutableMapOf(
                "/problem/list?page=1&_contentOnly=1" to listPage(count = 6, pids = listOf("P1" to 1, "P2" to 2)),
                "/problem/list?page=2&_contentOnly=1" to listPage(count = 6, pids = listOf("P3" to 3, "P4" to 4)),
                "/problem/list?page=3&_contentOnly=1" to listPage(count = 6, pids = listOf("P5" to 5, "P6" to 6)),
            )
        // offset=2(跳过 P1/P2)limit=3 → P3/P4/P5
        val summaries = service(script).queryProblems(ProblemFilter(limit = 3, offset = 2)).toList()

        assertEquals(listOf("P3", "P4", "P5"), summaries.map { it.remoteId })
        assertEquals(listOf("普及/提高-", "普及+/提高", "提高+/省选-"), summaries.map { it.difficulty.value })
        assertEquals("https://www.luogu.com.cn/problem/P3", summaries[0].url)
    }

    @Test
    fun `query problems stops when count exhausted`() = runBlocking {
        val script =
            mutableMapOf(
                "/problem/list?page=1&_contentOnly=1" to listPage(count = 2, pids = listOf("P1" to 0, "P2" to 7)),
            )
        val summaries = service(script).queryProblems(ProblemFilter(limit = 50)).toList()

        assertEquals(listOf("P1", "P2"), summaries.map { it.remoteId })
        assertEquals(listOf("暂无评定", "NOI/NOI+/CTSC"), summaries.map { it.difficulty.value })
    }

    @Test
    fun `difficulty and keyword filter params are forwarded`() = runBlocking {
        val script =
            mutableMapOf(
                "/problem/list?page=1&_contentOnly=1&difficulty=1&keyword=dp" to
                    listPage(count = 1, pids = listOf("P1" to 1)),
            )
        val summaries =
            service(script)
                .queryProblems(ProblemFilter(keyword = "dp", difficulty = Difficulty("入门")))
                .toList()

        assertEquals(listOf("P1"), summaries.map { it.remoteId })
    }

    @Test
    fun `unknown difficulty label is ignored as filter`() = runBlocking {
        val script =
            mutableMapOf(
                "/problem/list?page=1&_contentOnly=1" to listPage(count = 1, pids = listOf("P1" to 1)),
            )
        val summaries = service(script).queryProblems(ProblemFilter(difficulty = Difficulty("easy"))).toList()

        assertEquals(listOf("P1"), summaries.map { it.remoteId })
    }

    @Test
    fun `problem detail returns markdown statement`() = runBlocking {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                luoguProblemUrl("P1001") ->
                    """<html><body><script id="lentille-context" type="application/json">
                       {"data":{"problem":{"pid":"P1001","title":"A+B","content":{"description":"d","formatI":"i","formatO":"o","hint":"h"},"limits":{"time":[1000],"memory":[131072]},"samples":[]}}}
                       </script></body></html>""".trimIndent().replace("\n", "")
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val statement = LuoguProblemService(LuoguApi(transport)).fetchProblemDetail("P1001")

        assertEquals("P1001", statement.remoteId)
        assertEquals(ContentFormat.MARKDOWN, statement.contentFormat)
        assertEquals(true, statement.content.startsWith("# P1001 A+B"))
    }

    @Test
    fun `solutions map to article refs with anchored urls`() = runBlocking {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when (request.url) {
                LUOGU_HOME_URL -> homeHtml
                luoguSolutionListUrl("P1001") ->
                    """<html><body><script id="lentille-context" type="application/json">
                       {"data":{"solutions":{"count":1,"result":[{"id":77,"title":"题解","author":{"uid":1,"name":"a"}}]}}}
                       </script></body></html>""".trimIndent().replace("\n", "")
                else -> throw OJException("unexpected ${request.url}")
            }
        }

        val refs = LuoguSolutions(LuoguApi(transport)).fetchSolutions("P1001")

        assertEquals(
            listOf(SolutionArticleRef(id = "77", title = "题解", url = "https://www.luogu.com.cn/problem/solution/P1001#solution-77")),
            refs,
        )
    }
}
