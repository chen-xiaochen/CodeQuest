package com.codequest.infra.messaging

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ProblemId
import com.codequest.infra.db.CodeQuestDatabase
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedTestCaseRepository
import com.codequest.infra.db.newTempDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [integration-ish unit] 1.5.0:testcases 数据端点——真实 ResourceHttpServer + 真实
 * SQLite 仓库,覆盖列表(challengeName 附带)/新增(source=USER)/更新(保留来源)/
 * 删除/错误分支(缺参 400、未知 id 404、坏正文 400)。
 */
class TestCasesApiHttpTest {
    private lateinit var db: CodeQuestDatabase
    private lateinit var testCases: ExposedTestCaseRepository
    private lateinit var challenges: ExposedChallengeRepository
    private lateinit var server: ResourceHttpServer
    private val client = HttpClient.newHttpClient()
    private var idmark = 0

    @BeforeTest
    fun setUp() {
        val (database, _) = newTempDatabase()
        db = database
        testCases = ExposedTestCaseRepository(db)
        challenges = ExposedChallengeRepository(db)
        server =
            ResourceHttpServer(
                testCasesApi =
                    TestCasesApi(
                        testCases = { testCases },
                        challenges = { challenges },
                        ids = { "tc-%03d".format(idmark++) },
                    ),
            )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        db.close()
    }

    private fun seedChallenge() {
        runBlocking {
            challenges.save(Challenge(ChallengeId("c-1"), ProblemId("p-1"), "Two Sum", Instant.parse("2026-08-15T08:00:00Z")))
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
    ): Pair<Int, JsonObject> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.listeningPort}$path"))
        when (body) {
            null -> builder.method(method, HttpRequest.BodyPublishers.noBody())
            else -> builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val parsed = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return Pair(response.statusCode(), parsed)
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    @Test
    fun `lists cases with challenge name`() {
        seedChallenge()
        runBlocking {
            testCases.save(com.codequest.core.model.TestCase(com.codequest.core.model.TestCaseId("x1"), ChallengeId("c-1"), "1\n2", "3", com.codequest.core.model.TestCaseSource.PLATFORM))
            testCases.save(com.codequest.core.model.TestCase(com.codequest.core.model.TestCaseId("x2"), ChallengeId("c-1"), "4\n5", "9", com.codequest.core.model.TestCaseSource.USER))
        }

        val (status, body) = request("GET", "/api/testcases?challengeId=c-1")
        assertEquals(200, status)
        assertEquals("c-1", body["challengeId"]!!.jsonPrimitive.content)
        assertEquals("Two Sum", body["challengeName"]!!.jsonPrimitive.content)
        val items = body["items"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("x1", "x2"), items.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("PLATFORM", items[0].jsonObject["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list without challengeId is 400`() {
        val (status, body) = request("GET", "/api/testcases")
        assertEquals(400, status)
        assertEquals("bad-request", body["error"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // 新增/更新/删除
    // ------------------------------------------------------------------

    @Test
    fun `create persists a user-source case and list reflects it`() {
        seedChallenge()
        val (status, body) =
            request(
                "POST",
                "/api/testcases",
                """{"challengeId":"c-1","input":"7\n8","expected":"15"}""",
            )
        assertEquals(200, status)
        assertEquals("USER", body["source"]!!.jsonPrimitive.content)
        assertEquals("15", body["expected"]!!.jsonPrimitive.content)
        assertTrue(body["id"]!!.jsonPrimitive.content.isNotBlank())

        val (_, listBody) = request("GET", "/api/testcases?challengeId=c-1")
        val items = listBody["items"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, items.size)
        assertEquals("7\n8", items[0].jsonObject["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create with malformed body is 400`() {
        val (status, body) = request("POST", "/api/testcases", "{not-json")
        assertEquals(400, status)
        assertEquals("bad-request", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update changes input and expected but keeps source`() {
        seedChallenge()
        runBlocking {
            testCases.save(com.codequest.core.model.TestCase(com.codequest.core.model.TestCaseId("x1"), ChallengeId("c-1"), "1", "2", com.codequest.core.model.TestCaseSource.PLATFORM))
        }

        val (status, body) =
            request(
                "POST",
                "/api/testcases/update",
                """{"id":"x1","input":"9","expected":"11"}""",
            )
        assertEquals(200, status)
        assertEquals("9", body["input"]!!.jsonPrimitive.content)
        assertEquals("11", body["expected"]!!.jsonPrimitive.content)
        // 来源保留(平台样例不因手改变手动)
        assertEquals("PLATFORM", body["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update with unknown id is 404`() {
        seedChallenge()
        val (status, body) =
            request("POST", "/api/testcases/update", """{"id":"nope","input":"","expected":""}""")
        assertEquals(404, status)
        assertEquals("not-found", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete removes case and unknown id is 404`() {
        seedChallenge()
        runBlocking {
            testCases.save(com.codequest.core.model.TestCase(com.codequest.core.model.TestCaseId("x1"), ChallengeId("c-1"), "1", "2", com.codequest.core.model.TestCaseSource.USER))
        }

        val (status, body) = request("DELETE", "/api/testcases?id=x1")
        assertEquals(200, status)
        assertEquals("true", body["deleted"]!!.jsonPrimitive.content)

        val (status2, _) = request("DELETE", "/api/testcases?id=x1")
        assertEquals(404, status2)

        val (status3, _) = request("DELETE", "/api/testcases")
        assertEquals(400, status3)
    }

    @Test
    fun `challenge name is absent when challenge row is gone`() {
        runBlocking {
            testCases.save(com.codequest.core.model.TestCase(com.codequest.core.model.TestCaseId("x1"), ChallengeId("c-gone"), "1", "2", com.codequest.core.model.TestCaseSource.USER))
        }
        val (status, body) = request("GET", "/api/testcases?challengeId=c-gone")
        assertEquals(200, status)
        assertNull(body["challengeName"])
        assertFalse(body.containsKey("challengeName"))
        assertTrue(body.containsKey("items"))
    }
}
