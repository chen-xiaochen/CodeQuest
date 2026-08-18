package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.infra.http.HttpOJTransport
import com.codequest.infra.http.OJHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [unit] WP31:LeetCodeSolutions 题解正文 SPI 映射——CN 原样透传、COM 反转义
 * (旧 UNESCAPE_LEETCODE 事实:中 octal/unicode/查找表 聚合)、
 * contentFormat 一律 MARKDOWN(旧 webview marked.parse 渲染事实)。
 * MockEngine 回放 /graphql/ 响应,不上网络。
 */
class LeetCodeSolutionsTest {
    private val engine =
        MockEngine { request ->
            when {
                request.url.encodedPath == "/api/home/" ->
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Set-Cookie", "csrftoken=csrf-test; Path=/"),
                    )

                request.url.encodedPath == "/graphql/" -> {
                    val body = runCatching { request.body.toByteArray().decodeToString() }.getOrDefault("")
                    val operation = if (body.contains("discussTopic")) "discussTopic" else "ugcArticleSolutionArticle"
                    respond(
                        when (operation) {
                            "discussTopic" ->
                                """{"data":{"solutionArticle":{"uuid":"u1","title":"[题解] two-sum","slug":"ts-sol","content":"## 思路\n\n$COVER_MARK"}}}"""

                            else ->
                                """{"data":{"ugcArticleSolutionArticle":{"uuid":"u2","title":"Official","slug":"off","content":"line1\\nline2 中文\\u4e2d \\\"q\\\" \\'s\\' \\\\ path \\101"}}}"""
                        },
                    )
                }

                else -> error("unexpected ${request.url}")
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

    private fun cnSolutions() = LeetCodeSolutions(LeetCodeVariant.LEETCODE_CN, LeetCodeApi(LeetCodeVariant.LEETCODE_CN, HttpOJTransport(client)))

    private fun comSolutions() = LeetCodeSolutions(LeetCodeVariant.LEETCODE, LeetCodeApi(LeetCodeVariant.LEETCODE, HttpOJTransport(client)))

    @Test
    fun `cn solution content passes through as markdown`() = runBlocking {
        val solutions = cnSolutions()

        val content = solutions.fetchSolutionContent(remoteId = "two-sum", solutionId = "ts-sol")

        assertEquals("ts-sol", content.id)
        assertEquals("[题解] two-sum", content.title)
        assertEquals(ContentFormat.MARKDOWN, content.contentFormat)
        // CN 站点正文原样(无二次转义)
        assertEquals("## 思路\n\n$COVER_MARK", content.content)
    }

    @Test
    fun `com solution content is unescaped per legacy translator and markdown`() = runBlocking {
        val solutions = comSolutions()

        val content = solutions.fetchSolutionContent(remoteId = "two-sum", solutionId = "off")

        assertEquals("off", content.id)
        assertEquals("Official", content.title)
        assertEquals(ContentFormat.MARKDOWN, content.contentFormat)
        // 旧 UNESCAPE_LEETCODE 聚合行为:中 →换行、\\uXXXX →字符、\\"/\\' →引号、\\\\ →反斜杠、\\ooo(八进制)→字符
        assertEquals("line1\nline2 中文中 \"q\" 's' \\ path A", content.content)
    }

    @Test
    fun `unescape keeps unrecognized escape sequences verbatim`() {
        assertEquals("a\\db\\x", unescapeLeetCodeArticleContent("""a\db\x"""))
        assertEquals(
            "keep trailing a\\",
            unescapeLeetCodeArticleContent("""keep trailing a\"""),
        )
        // \u 后非 4 位十六进制:原样保留(UnicodeUnescaper 语义)
        assertEquals("""\uzzzz""", unescapeLeetCodeArticleContent("""\uzzzz"""))
        assertEquals("中文原文", unescapeLeetCodeArticleContent("中文原文"))
        assertTrue(unescapeLeetCodeArticleContent("").isEmpty())
        assertFalse(unescapeLeetCodeArticleContent("""\41""").contains('\\'))
    }

    private companion object {
        /** 夹具正文标记(避开 Kotlin 字符串插值的 $ 符号)。 */
        const val COVER_MARK = "哈希表一次遍历"
    }
}
