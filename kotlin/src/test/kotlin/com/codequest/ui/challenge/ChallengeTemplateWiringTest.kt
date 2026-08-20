package com.codequest.ui.challenge

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedSolutionRepository
import com.codequest.infra.db.ExposedTestCaseRepository
import com.codequest.infra.db.newTempDatabase
import com.codequest.infra.settings.CodeQuestSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] WP39:创建链路的模板接线——[createChallengeWithTemplate] 是两条创建路径
 * (NewChallengeDialog 手填 / ChallengeCreateBridge 一键开刷)共用的调用点:
 * 创建前读项目设置,`templates[languageId]` 非空则作为 `template=` 传入
 * [ChallengeWorkspaceService.createChallenge](core 零改动,参数已有);
 * 空/缺失回退内置模板。
 *
 * 服务用真实实现 + 临时 SQLite 库(测试代码可跨层组装),断言入口文件内容
 * 与落库的语言配置模板确实来自设置覆盖。
 */
class ChallengeTemplateWiringTest {
    private val fixedInstant = Instant.parse("2026-08-20T08:00:00Z")

    private fun problem() =
        Problem(
            id = ProblemId("p-1"),
            platformId = PlatformId("leetcode"),
            remoteId = "two-sum",
            title = "两数之和",
            difficulty = Difficulty("easy"),
            url = "https://leetcode.cn/problems/two-sum/",
            tags = emptyList(),
            fetchedAt = fixedInstant,
        )

    private fun settingsWithTemplate(
        languageId: String,
        template: String?,
    ): CodeQuestSettings {
        val settings = CodeQuestSettings()
        val templates =
            template?.let { linkedMapOf(languageId to it) } ?: linkedMapOf()
        settings.loadState(CodeQuestSettings.State(templates = templates))
        return settings
    }

    @Test
    fun `settings override is passed as template and rendered into entry file`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val override = "// CUSTOM-WP39 \${challenge.name}\n\${beginRegion}\n\${endRegion}\n"
            val settings = settingsWithTemplate("kotlin", override)

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
            val settings = settingsWithTemplate("kotlin", "   ")

            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem(), "kotlin", settings)
                }

            val content = Files.readString(created.entryFile)
            assertTrue(content.contains("fun main()"), content)
            assertTrue(!content.contains("CUSTOM-WP39"), content)
        }
    }

    /**
     * [regression] 修复#45:新建解法按**当前**设置覆盖渲染(生成时读设置,对齐原版
     * LanguageSettings 语义),而非工作区落库时的旧模板。
     */
    @Test
    fun `createSolution renders with current settings override instead of stored template`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            // challenge 以无覆盖创建(落库内置模板),之后用户在设置里加了覆盖
            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem(), "kotlin", settingsWithTemplate("kotlin", null))
                }

            val solution =
                runBlocking {
                    service.createSolution(
                        created,
                        "approach two",
                        "",
                        template = "// SOLUTION-OVERRIDE \${challenge.name}",
                    )
                }

            val content = Files.readString(created.workspaceRoot.resolve(solution.filePath))
            assertTrue(content.contains("SOLUTION-OVERRIDE"), content)
            assertTrue(content.contains("两数之和"), content)
        }
    }

    @Test
    fun `createSolution without override keeps stored template`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service = service(it, challenges, dir)
            val override = "// STORED-OVERRIDE \${challenge.name}\n\${beginRegion}\n\${endRegion}\n"
            val created =
                runBlocking {
                    createChallengeWithTemplate(service, problem(), "kotlin", settingsWithTemplate("kotlin", override))
                }

            val solution =
                runBlocking {
                    service.createSolution(created, "approach one", "", template = null)
                }

            val content = Files.readString(created.workspaceRoot.resolve(solution.filePath))
            assertTrue(content.contains("STORED-OVERRIDE"), content)
        }
    }

    private fun service(
        db: com.codequest.infra.db.CodeQuestDatabase,
        challenges: ExposedChallengeRepository,
        dir: Path,
    ): ChallengeWorkspaceService =
        ChallengeWorkspaceService(
            challengeRepository = challenges,
            solutionRepository = ExposedSolutionRepository(db),
            testCaseRepository = ExposedTestCaseRepository(db),
            workspaceRoot = { dir.resolveSibling("ws").resolve(it.id.value) },
            clock = { fixedInstant },
        )
}
