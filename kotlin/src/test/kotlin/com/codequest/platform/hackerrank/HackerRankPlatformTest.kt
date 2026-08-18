package com.codequest.platform.hackerrank

import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.infra.messaging.ProblemApi
import com.codequest.infra.messaging.ResourceHttpServer
import com.codequest.platform.atcoder.AtCoderProvider
import com.codequest.platform.codeforces.CodeForcesProvider
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.platform.leetcode.InMemoryCredentialStore
import com.codequest.platform.leetcode.LeetCodeCnProvider
import com.codequest.platform.leetcode.LeetCodeProvider
import com.codequest.platform.luogu.LuoguProvider
import com.codequest.spi.AuthCapable
import com.codequest.spi.ContestCapable
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.OJRuntime
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.SolutionViewCapable
import com.codequest.spi.SubmissionCapable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP27:平台注册与能力组合——provider 产出 hackerrank 平台实例,
 * auth/problem/submission 三能力实现且**不实现** ContestCapable/
 * SolutionViewCapable(spec 能力矩阵 HackerRank 无竞赛/题解消费);
 * **全平台注册回归(WP27 收官验收)**:六 provider 注入注册表后
 * `OJPlatformRegistry.all()` 恰为 6 平台,`/api/platforms` 端点全部出现。
 */
class HackerRankPlatformTest {
    @AfterTest
    fun tearDown() {
        OJRuntime.resetToDefaults()
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun withFakeRuntime(): List<HackerRankPlatform> {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = { listOf(HackerRankProvider()) }
        return OJPlatformRegistry.all().filterIsInstance<HackerRankPlatform>()
    }

    @Test
    fun `provider registers single hackerrank platform`() {
        val platforms = withFakeRuntime()

        assertEquals(listOf("hackerrank"), platforms.map { it.id.value })
        assertEquals(listOf("www.hackerrank.com"), platforms.map { it.domain })
        assertEquals(listOf("HackerRank"), platforms.map { it.info.displayName })
    }

    @Test
    fun `platform implements auth problem and submission capabilities`() {
        val platform = withFakeRuntime().single()

        assertTrue(platform is AuthCapable, "缺少 AuthCapable")
        assertTrue(platform is ProblemQueryCapable, "缺少 ProblemQueryCapable")
        assertTrue(platform is SubmissionCapable, "缺少 SubmissionCapable")
        assertTrue(platform.info.supportedLanguages.isNotEmpty())
    }

    @Test
    fun `platform is not contest or solution capable per spec matrix`() {
        withFakeRuntime()

        val contestCapable = OJPlatformRegistry.allWith<ContestCapable>()
        val solutionCapable = OJPlatformRegistry.allWith<SolutionViewCapable>()

        assertTrue(contestCapable.isEmpty(), "HackerRank 无 ContestCapable(spec 矩阵:无竞赛消费)")
        assertTrue(solutionCapable.isEmpty(), "HackerRank 无 SolutionViewCapable(spec 矩阵:无题解消费)")
    }

    @Test
    fun `all six platform providers register exactly six platforms`() {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = {
            listOf(
                LeetCodeProvider(),
                LeetCodeCnProvider(),
                CodeForcesProvider(),
                AtCoderProvider(),
                LuoguProvider(),
                HackerRankProvider(),
            )
        }

        val platforms = OJPlatformRegistry.all()

        assertEquals(6, platforms.size, "M4 收官:全平台注册数应为 6")
        assertEquals(
            setOf("leetcode", "leetcodecn", "codeforces", "atcoder", "luogu", "hackerrank"),
            platforms.map { it.id.value }.toSet(),
        )
    }

    @Test
    fun `platforms endpoint lists all six registered platforms`() {
        OJRuntime.transportSource = { FakeOJTransport() }
        OJRuntime.credentialStoreSource = { InMemoryCredentialStore() }
        OJPlatformRegistry.providerSource = {
            listOf(
                LeetCodeProvider(),
                LeetCodeCnProvider(),
                CodeForcesProvider(),
                AtCoderProvider(),
                LuoguProvider(),
                HackerRankProvider(),
            )
        }
        val (db, _) = newTempDatabase()
        val server = ResourceHttpServer(problemApi = ProblemApi(store = { ExposedProblemRepository(db) }))
        val client = HttpClient.newHttpClient()
        try {
            server.start()

            val response =
                client
                    .send(
                        HttpRequest
                            .newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}/api/platforms"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )

            assertEquals(200, response.statusCode())
            val platforms =
                Json.parseToJsonElement(response.body()).jsonObject["platforms"]!!.jsonArray.toList()
            val idList = platforms.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(
                setOf("leetcode", "leetcodecn", "codeforces", "atcoder", "luogu", "hackerrank"),
                idList.toSet(),
                "/api/platforms 应返回全部 6 平台",
            )
            assertEquals(6, idList.size)
        } finally {
            server.stop()
            client.close()
            db.close()
        }
    }
}
