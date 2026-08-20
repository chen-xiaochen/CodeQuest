package com.codequest.infra.messaging

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
 * [unit] WP39:题面自定义 CSS 端点 `GET /api/custom.css`——text/css 透传设置中的
 * CSS 文本;未设置返回 200 空体(样式是附属能力,非数据 API,不回 503);
 * 非 GET 回 404(与既有 API 路由一致)。每次请求经 provider 现读设置
 * (settings apply 后无需重启,下次页面加载拉到新值)。
 */
class CustomCssHttpTest {
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        server = ResourceHttpServer(customCss = { CUSTOM_CSS })
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `returns configured css with text css content type`() {
        val response = getRaw("/api/custom.css")
        assertEquals(200, response.statusCode())
        assertEquals("text/css; charset=UTF-8", response.headers().firstValue("Content-Type").orElse(""))
        assertEquals(CUSTOM_CSS, response.body())
    }

    @Test
    fun `unset css returns 200 with empty body`() {
        server.stop()
        server = ResourceHttpServer(customCss = { "" })
        server.start()

        val response = getRaw("/api/custom.css")
        assertEquals(200, response.statusCode())
        assertEquals("", response.body())
    }

    @Test
    fun `default server without provider returns 200 with empty body`() {
        server.stop()
        server = ResourceHttpServer()
        server.start()

        val response = getRaw("/api/custom.css")
        assertEquals(200, response.statusCode())
        assertEquals("", response.body())
    }

    @Test
    fun `rejects non GET with 404`() {
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}/api/custom.css"))
                    .POST(HttpRequest.BodyPublishers.ofString("body {}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(404, response.statusCode())
        assertTrue(response.body().isNotEmpty())
    }

    private fun getRaw(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private companion object {
        const val CUSTOM_CSS = ".statement { font-size: 15px; }"
    }
}
