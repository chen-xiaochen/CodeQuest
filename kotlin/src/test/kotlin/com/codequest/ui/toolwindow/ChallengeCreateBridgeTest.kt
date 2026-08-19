package com.codequest.ui.toolwindow

import com.codequest.core.model.PlatformId
import com.codequest.spi.LanguageInfo
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * 一键开刷(1.0.x,参考原版 OpenChallengeActionGroup 交互):题面页
 * `challenge:create` 桥命令——题目信息全自动,用户只选语言;同题同语言
 * 已存在则直接打开(幂等),否则创建工作区并打开编辑器。
 */
@Suppress("FunctionName")
class ChallengeCreateBridgeTest {
    private class FakePlatform(
        platformId: PlatformId,
        languages: List<LanguageInfo>,
    ) : OJPlatform {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info =
            PlatformInfo(displayName = platformId.value, iconPath = null, supportedLanguages = languages)
    }

    private fun language(id: String) = LanguageInfo(id = "x-$id", displayName = id, workspaceLanguageId = id)

    @Test
    fun `parses challenge create payload`() {
        val request =
            parseChallengeCreatePayload(
                """{"platform":"leetcodecn","remoteId":"two-sum","title":"两数之和","difficulty":"Easy","url":"https://leetcode.cn/problems/two-sum/"}""",
            )
        assertEquals("leetcodecn", request?.platform)
        assertEquals("two-sum", request?.remoteId)
        assertEquals("两数之和", request?.title)
        assertEquals("Easy", request?.difficulty)
        assertEquals("https://leetcode.cn/problems/two-sum/", request?.url)
    }

    @Test
    fun `rejects payload without platform remoteId or title`() {
        assertNull(parseChallengeCreatePayload("""{"remoteId":"two-sum","title":"x","difficulty":"","url":""}"""))
        assertNull(parseChallengeCreatePayload("""{"platform":"leetcodecn","title":"x","difficulty":"","url":""}"""))
        assertNull(parseChallengeCreatePayload("""{"platform":"leetcodecn","remoteId":"two-sum","difficulty":"","url":""}"""))
        assertNull(parseChallengeCreatePayload("not-json"))
    }

    @Test
    fun `language plan fails for unknown platform or empty languages`() {
        assertTrue(languagePlanForPlatform(FakePlatform(PlatformId("x"), emptyList())).isFailure)
        assertTrue(languagePlanForPlatform(null).isFailure)
    }

    @Test
    fun `single language is chosen automatically`() {
        val plan = languagePlanForPlatform(FakePlatform(PlatformId("x"), listOf(language("java"))))
        assertEquals(ChallengeLanguagePlan.Auto("java"), plan.getOrNull())
    }

    @Test
    fun `multiple languages require user choice`() {
        val plan =
            languagePlanForPlatform(
                FakePlatform(PlatformId("x"), listOf(language("java"), language("kotlin"))),
            )
        assertEquals(
            ChallengeLanguagePlan.Choice(listOf("java", "kotlin")),
            plan.getOrNull(),
        )
    }

    @Test
    fun `languages without workspace mapping are skipped`() {
        val plan =
            languagePlanForPlatform(
                FakePlatform(
                    PlatformId("x"),
                    listOf(
                        LanguageInfo(id = "py2", displayName = "Python 2", workspaceLanguageId = null),
                        language("python"),
                    ),
                ),
            )
        assertEquals(ChallengeLanguagePlan.Auto("python"), plan.getOrNull())
    }

    @Test
    fun `createOrOpen reuses existing workspace without creating`() =
        runBlocking {
            val existing = Path.of("/ws/existing/Main.java")
            var created: Path? = null
            var existingLookedUp = false

            val result =
                createOrOpen(
                    languageId = "java",
                    findExisting = {
                        existingLookedUp = true
                        existing
                    },
                    doCreate = {
                        created = Path.of("/ws/new/Main.java")
                        created!!
                    },
                )

            assertTrue(existingLookedUp)
            assertEquals(existing, result)
            assertNull(created)
        }

    @Test
    fun `createOrOpen creates when no existing workspace matches`() =
        runBlocking {
            val created = Path.of("/ws/new/Main.java")
            var createCalled = false

            val result =
                createOrOpen(
                    languageId = "java",
                    findExisting = { null },
                    doCreate = {
                        createCalled = true
                        created
                    },
                )

            assertTrue(createCalled)
            assertEquals(created, result)
        }

    @Test
    fun `result json is stable`() {
        assertEquals("""{"ok":true}""", ChallengeCreateResult(ok = true).toJson())
        assertEquals(
            """{"ok":false,"error":"boom"}""",
            ChallengeCreateResult(ok = false, error = "boom").toJson(),
        )
        assertFalse(ChallengeCreateResult(ok = false, error = "boom").ok)
    }
}
