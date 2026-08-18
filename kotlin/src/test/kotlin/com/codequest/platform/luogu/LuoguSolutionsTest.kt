package com.codequest.platform.luogu

import com.codequest.core.model.ContentFormat
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
import kotlin.test.assertTrue

/**
 * [unit] WP31:LuoguSolutions 题解正文 SPI 映射——solution 页 payload 按 id 筛
 * 楼层取 content(Markdown),contentFormat 一律 MARKDOWN(洛谷题解原文即 MD,
 * 含 LaTeX/代码块,交前端管线渲染)。MockEngine 回放,不上网络。
 */
class LuoguSolutionsTest {
    private val engine =
        MockEngine { request ->
            val path =
                request.url.encodedPath +
                    (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            when (path) {
                "/" -> respond("""<html><head><meta name="csrf-token" content="csrf-1"></head><body>ok</body></html>""")
                "/problem/solution/P1001?_contentOnly=1" ->
                    respond(
                        """<html><body><script id="lentille-context" type="application/json">
                        {"data":{"solutions":{"count":1,"perPage":10,"result":[
                          {"id":123456,"title":"一份题解","author":{"uid":1,"name":"alice"},
                           "content":"## 思路\n\n前缀和求 ${'$'}S_n${'$'}。\n\n```cpp\nint main() {}\n```"}
                        ]}}}
                        </script></body></html>""".trimIndent().replace("\n", ""),
                    )

                else -> error("unexpected $path")
            }
        }

    private lateinit var client: OJHttpClient

    @BeforeTest
    fun setUp() {
        client = OJHttpClient(engine)
    }

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `solution content maps markdown floor from solution page`() = runBlocking {
        val solutions = LuoguSolutions(LuoguApi(HttpOJTransport(client)))

        val content = solutions.fetchSolutionContent(remoteId = "P1001", solutionId = "123456")

        assertEquals("123456", content.id)
        assertEquals("一份题解", content.title)
        assertEquals(ContentFormat.MARKDOWN, content.contentFormat)
        assertTrue(content.content.startsWith("## 思路"), content.content)
        assertTrue("```cpp" in content.content, content.content)
    }
}
