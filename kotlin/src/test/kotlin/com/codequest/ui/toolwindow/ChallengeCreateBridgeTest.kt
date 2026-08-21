package com.codequest.ui.toolwindow

import com.codequest.core.model.PlatformId
import com.codequest.core.workspace.TemplateStyle
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
 * `challenge:create` 桥命令——题目信息全自动,语言自动选择(WP47:平台风格下
 * 已配置模版,Java 优先);同题同语言已存在则直接打开(幂等),否则创建工作区
 * 并打开编辑器。
 */
@Suppress("FunctionName")
class ChallengeCreateBridgeTest {
    private class FakePlatform(
        platformId: PlatformId,
        private val style: TemplateStyle = TemplateStyle.ACM,
    ) : OJPlatform {
        override val id = platformId
        override val domain = "${platformId.value}.example.com"
        override val info = PlatformInfo(displayName = platformId.value, iconPath = null, templateStyle = style)
    }

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

    /** (WP47) 语言三态之一:平台未知 → 失败。 */
    @Test
    fun `language decision fails for unknown platform`() {
        assertTrue(languageIdForCreate(null, mapOf("acm:java" to "j")).isFailure)
    }

    /** (WP47) 语言三态之二:Java 优先——风格下已配置(即使 kotlin 排在声明顺序首位)。 */
    @Test
    fun `java is preferred when configured under the platform style`() {
        val decision =
            languageIdForCreate(
                FakePlatform(PlatformId("codeforces")),
                mapOf("acm:kotlin" to "k", "acm:java" to "j"),
            )
        assertEquals("java", decision.getOrNull())
    }

    /** (WP47) 语言三态之二回退:Java 未配置(含空白条目)→ 第一个可用语言;跨风格键不串。 */
    @Test
    fun `falls back to first usable language when java unconfigured`() {
        val decision =
            languageIdForCreate(
                FakePlatform(PlatformId("codeforces")),
                mapOf("acm:java" to "", "leetcode:java" to "j", "acm:python3" to "p"),
            )
        assertEquals("python3", decision.getOrNull())
    }

    /** (WP47) 语言三态之三:该风格下全无可用语言 → 失败(调用方转引导配置模版文案)。 */
    @Test
    fun `language decision fails when no language configured under the platform style`() {
        val decision =
            languageIdForCreate(
                FakePlatform(PlatformId("codeforces")),
                mapOf("leetcode:java" to "j"),
            )
        assertTrue(decision.isFailure)
    }

    /** (WP47) 风格维度:LEETCODE 风格平台读 `leetcode:` 键。 */
    @Test
    fun `leetcode style platform reads leetcode keys`() {
        val decision =
            languageIdForCreate(
                FakePlatform(PlatformId("leetcode"), style = TemplateStyle.LEETCODE),
                mapOf("leetcode:go" to "g"),
            )
        assertEquals("go", decision.getOrNull())
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
