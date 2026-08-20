package com.codequest.spi

import com.codequest.core.model.PlatformId
import com.codequest.core.workspace.TemplateStyle
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] ojAuthPromptProvider EP 解析(WP23):多实现按 platformIds 认领、
 * 未知平台返回 null(调用方回退注册表默认实例),消除按平台 id 的 when 分发。
 */
class OJAuthPromptsTest {
    private open class FakePlatform(
        id: String,
    ) : OJPlatform {
        override val id = PlatformId(id)
        override val domain = "$id.example.com"
        override val info = PlatformInfo(displayName = id.uppercase(), templateStyle = TemplateStyle.ACM)
    }

    /** 只认领自己 platformIds 的假 provider;prompt 以标签区分来源。 */
    private class FakePromptProvider(
        vararg ids: String,
    ) : OJAuthPromptProvider {
        val label = "prompt-${ids.first()}"
        override val platformIds: Set<PlatformId> = ids.map(::PlatformId).toSet()

        override fun promptFor(
            project: Project,
            platformId: PlatformId,
        ): OJAuthPrompt? = platformIds.singleOrNull { it == platformId }?.let { OJAuthPrompt { label } }

        override fun createPlatform(
            project: Project,
            platformId: PlatformId,
        ): OJPlatform? = platformIds.singleOrNull { it == platformId }?.let { FakePlatform(it.value) }
    }

    private val leetcodeProvider = FakePromptProvider("leetcode")
    private val cnProvider = FakePromptProvider("leetcodecn")
    private val cfProvider = FakePromptProvider("codeforces")

    @BeforeTest
    fun setUp() {
        OJAuthPrompts.providerSource = { listOf(leetcodeProvider, cnProvider, cfProvider) }
    }

    @AfterTest
    fun tearDown() {
        OJAuthPrompts.resetToDefaults()
    }

    @Test
    fun `providerFor routes by declared platform ids across multiple providers`() {
        assertEquals(leetcodeProvider, OJAuthPrompts.providerFor(PlatformId("leetcode")))
        assertEquals(cnProvider, OJAuthPrompts.providerFor(PlatformId("leetcodecn")))
        assertEquals(cfProvider, OJAuthPrompts.providerFor(PlatformId("codeforces")))
    }

    @Test
    fun `providerFor returns null for unknown platform`() {
        assertNull(OJAuthPrompts.providerFor(PlatformId("no-such-oj")))
    }

    @Test
    fun `promptFor returns owning provider hook`() {
        assertEquals("prompt-leetcodecn", runBlocking { OJAuthPrompts.promptFor(dummyProject, PlatformId("leetcodecn"))?.requestCredentials() })
        assertNull(OJAuthPrompts.promptFor(dummyProject, PlatformId("no-such-oj")))
    }

    @Test
    fun `createPlatform assembles platform via owning provider and null for unknown`() {
        val assembled = OJAuthPrompts.createPlatform(project = dummyProject, platformId = PlatformId("codeforces"))
        assertEquals(PlatformId("codeforces"), assembled?.id)
        assertNull(OJAuthPrompts.createPlatform(project = dummyProject, platformId = PlatformId("no-such-oj")))
    }

    @Test
    fun `no providers registered yields null without throwing`() {
        OJAuthPrompts.providerSource = { emptyList() }
        assertNull(OJAuthPrompts.providerFor(PlatformId("leetcode")))
        assertTrue(OJAuthPrompts.promptFor(dummyProject, PlatformId("leetcode")) == null)
    }

    private companion object {
        /** 假 provider 不消费 project;动态代理占位即可(createPlatform 仅透传)。 */
        val dummyProject: Project =
            Proxy.newProxyInstance(
                Project::class.java.classLoader,
                arrayOf(Project::class.java),
            ) { _, _, _ -> null } as Project
    }
}
