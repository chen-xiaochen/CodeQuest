package com.codequest.ui.challenge

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.core.workspace.TemplateAvailability
import com.codequest.core.workspace.TemplateStyle
import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedTestCaseRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.infra.settings.CodeQuestSettings
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformProvider
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.PlatformInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [unit] WP39/WP47:创建链路的模板接线——[createChallengeWithTemplate] 是两条创建路径
 * (NewChallengeDialog 手填 / ChallengeCreateBridge 一键开刷)共用的调用点:
 * 创建前按**平台风格**读项目设置 `templates[style:languageId]`,非空白则作为
 * `template=` 传入 [ChallengeWorkspaceService.createChallenge](core 零改动,参数已有);
 * 空/缺失回退内置模板。
 *
 * 服务用真实实现 + 临时 SQLite 库(测试代码可跨层组装),断言入口文件内容
 * 与落库的语言配置模板确实来自设置覆盖;[templateStyleOf] 经注入的假注册表
 * 解析平台风格(leetcode→LEETCODE / codeforces→ACM)。
 */
class ChallengeTemplateWiringTest {
    private val fixedInstant = Instant.parse("2026-08-20T08:00:00Z")

    private class FakePlatform(
        id: String,
        style: TemplateStyle,
    ) : OJPlatform {
        override val id = PlatformId(id)
        override val domain = "$id.example.com"
        override val info = PlatformInfo(displayName = id, templateStyle = style)
    }

    private class Provider(private val platform: OJPlatform) : OJPlatformProvider {
        override fun create() = platform
    }

    @BeforeTest
    fun installPlatforms() {
        OJPlatformRegistry.providerSource = {
            listOf(
                Provider(FakePlatform("leetcode", TemplateStyle.LEETCODE)),
                Provider(FakePlatform("codeforces", TemplateStyle.ACM)),
            )
        }
    }

    @AfterTest
    fun restorePlatforms() {
        OJPlatformRegistry.providerSource = { OJPlatformRegistry.extensionPointProviders() }
    }

    private fun problem(platform: String = "leetcode") =
        Problem(
            id = ProblemId("p-1"),
            platformId = PlatformId(platform),
            remoteId = "two-sum",
            title = "两数之和",
            difficulty = Difficulty("easy"),
            url = "https://leetcode.cn/problems/two-sum/",
            tags = emptyList(),
            fetchedAt = fixedInstant,
        )

    /** 键为 WP45 新格式 "style:languageId"(loadState 归一化直通)。 */
    private fun settingsWithTemplates(vararg templates: Pair<String, String>): CodeQuestSettings =
        CodeQuestSettings().apply {
            loadState(CodeQuestSettings.State(templates = linkedMapOf(*templates)))
        }

    @Test
    fun `settings override is passed as template and rendered into entry file`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val override = "// CUSTOM-WP39 \${challenge.name}\n\${beginRegion}\n\${endRegion}\n"
            val settings = settingsWithTemplates("leetcode:kotlin" to override)

            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem(), "kotlin", settings)
                }

            val content = Files.readString(created.entryFile)
            assertTrue(content.contains("CUSTOM-WP39"), content)
            assertTrue(content.contains("两数之和"), content)
            // 落库的语言配置同样保存覆盖模板(多解法渲染复用)
            val languages = runBlocking { challenges.findLanguages(created.challenge.id) }
            assertEquals(override, languages.single().template)
        }
    }

    @Test
    fun `blank override falls back to built-in template`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val settings = settingsWithTemplates("leetcode:kotlin" to "   ")

            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem(), "kotlin", settings)
                }

            val content = Files.readString(created.entryFile)
            assertTrue(content.contains("fun main()"), content)
            assertTrue(!content.contains("CUSTOM-WP39"), content)
        }
    }

    /** (WP47) 风格维度:CF(ACM 风格) 平台创建读 `acm:` 键覆盖。 */
    @Test
    fun `acm style override applies when creating on codeforces`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val override = "// CUSTOM-ACM \${challenge.name}\n\${beginRegion}\n\${endRegion}\n"
            val settings = settingsWithTemplates("acm:java" to override)

            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem("codeforces"), "java", settings)
                }

            val content = Files.readString(created.entryFile)
            assertTrue(content.contains("CUSTOM-ACM"), content)
            val languages = runBlocking { challenges.findLanguages(created.challenge.id) }
            assertEquals(override, languages.single().template)
        }
    }

    /**
     * (WP47) 未配置(该风格下无键)→ override 为 null,core 内置默认(LEETCODE 形态)
     * 是最后防线;跨风格键不串(仅 `leetcode:java` 配置不影响 CF 平台)。
     */
    @Test
    fun `unconfigured language passes null and core built-in template is last resort`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val settings = settingsWithTemplates("leetcode:java" to "// X")

            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem("codeforces"), "java", settings)
                }

            val content = Files.readString(created.entryFile)
            assertTrue(!content.contains("// X"), content)
            // 落库模板 = 内置默认原文,证明接线如实传了 null(而非任何覆盖)
            val languages = runBlocking { challenges.findLanguages(created.challenge.id) }
            assertEquals(ChallengeTemplates.entryTemplate("java"), languages.single().template)
        }
    }

    /** (WP47) 未知平台兜底 LEETCODE:读 `leetcode:` 键(历史默认风格)。 */
    @Test
    fun `unknown platform falls back to leetcode style override`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val override = "// CUSTOM-UNKNOWN \${challenge.name}\n\${beginRegion}\n\${endRegion}\n"
            val settings = settingsWithTemplates("leetcode:java" to override)

            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem("no-such-oj"), "java", settings)
                }

            val content = Files.readString(created.entryFile)
            assertTrue(content.contains("CUSTOM-UNKNOWN"), content)
        }
    }

    /**
     * (WP47) NewChallengeDialog 语言门控组合逻辑(平台→可用语言+默认)——对话框为
     * Swing 紧耦合,此处锁纯函数组合:CF 平台 + `acm:` 已配置语言 → 保持 ALL 声明
     * 顺序、默认 Java;ACM 风格无配置(仅 leetcode 键)→ 空 + null(UI 据此拦截创建)。
     */
    @Test
    fun `language gate for platform lists usable languages in declaration order and defaults to java`() {
        val templates = mapOf("acm:java" to "j", "acm:kotlin" to "k")
        val usable = TemplateAvailability.usableLanguages(templateStyleOf(PlatformId("codeforces")), templates)
        assertEquals(listOf(WorkspaceLanguage.KOTLIN, WorkspaceLanguage.JAVA), usable)
        assertEquals(WorkspaceLanguage.JAVA, TemplateAvailability.defaultLanguage(usable))
    }

    @Test
    fun `language gate is empty when style has no configured templates`() {
        val usable =
            TemplateAvailability.usableLanguages(
                templateStyleOf(PlatformId("codeforces")),
                mapOf("leetcode:java" to "j"),
            )
        assertTrue(usable.isEmpty())
        assertNull(TemplateAvailability.defaultLanguage(usable))
    }

    private fun service(
        db: com.codequest.infra.db.CodeQuestDatabase,
        challenges: ExposedChallengeRepository,
        dir: Path,
    ): ChallengeWorkspaceService =
        ChallengeWorkspaceService(
            challengeRepository = challenges,
            testCaseRepository = ExposedTestCaseRepository(db),
            workspaceRoot = { dir.resolveSibling("ws").resolve(it.id.value) },
            clock = { fixedInstant },
        )
}
