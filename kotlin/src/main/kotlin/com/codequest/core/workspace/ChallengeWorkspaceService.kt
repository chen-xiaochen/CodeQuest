package com.codequest.core.workspace

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.model.ChallengeLanguageId
import com.codequest.core.model.Problem
import com.codequest.core.model.Solution
import com.codequest.core.model.SolutionId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId
import com.codequest.core.model.TestCaseSource
import com.codequest.core.repository.ChallengeRepository
import com.codequest.core.repository.SolutionRepository
import com.codequest.core.repository.TestCaseRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * challenge 工作区用例服务(WP16,纯 Kotlin,可脱离 IntelliJ 测试)。
 *
 * 职责:按语言模板渲染入口文件 → 写入工作区目录 → 经 core 仓库接口落库;
 * 多解法与测试用例的创建/更新/删除同样经仓库接口持久化。
 * 文件落盘位置由 [workspaceRoot] 注入(ui 层按项目路径解析),服务本身不感知 IDE。
 */
class ChallengeWorkspaceService(
    private val challengeRepository: ChallengeRepository,
    private val solutionRepository: SolutionRepository,
    private val testCaseRepository: TestCaseRepository,
    private val workspaceRoot: suspend (Challenge) -> Path,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Instant = Instant::now,
) {
    /** createChallenge 的产物:落库后的工作区与入口文件位置。 */
    data class CreatedChallenge(
        val challenge: Challenge,
        val language: ChallengeLanguage,
        val problem: Problem,
        /** 工作区根目录(绝对路径)。 */
        val workspaceRoot: Path,
        /** 入口文件(绝对路径,内容已渲染写入)。 */
        val entryFile: Path,
    )

    /**
     * 从题目创建工作区:渲染入口模板 → 写入 `workspaceRoot/challengeId/` → 落库
     * challenge 主档与语言配置(challenge 自身不重复保存 problem,由调用方负责)。
     *
     * @param code 平台侧起始代码;空则用语言默认桩
     * @param template 覆盖默认模板(设置项接入前的扩展点);null 用内置模板
     * @param fileNameRule 覆盖默认文件名规则;null 用内置规则
     */
    suspend fun createChallenge(
        problem: Problem,
        languageId: String,
        code: String? = null,
        template: String? = null,
        fileNameRule: String? = null,
    ): CreatedChallenge {
        val language = requireNotNull(WorkspaceLanguage.fromId(languageId)) { "unsupported language: $languageId" }
        val slug = slugFor(problem)
        val (beginRegion, endRegion) = ChallengeTemplates.regionMarkers(languageId)
        val templateText = template ?: ChallengeTemplates.entryTemplate(languageId)
        val context =
            templateContext(
                "challenge" to
                    mapOf(
                        "name" to problem.title,
                        "frontendQuestionId" to problem.remoteId,
                        "difficulty" to problem.difficulty.value,
                        "codeDojo" to problem.platformId.value,
                        "slug" to slug,
                        "url" to problem.url,
                        "code" to (code?.takeIf { it.isNotBlank() } ?: ChallengeTemplates.defaultCode(languageId)),
                    ),
                "beginRegion" to beginRegion,
                "endRegion" to endRegion,
            )
        val engine = TemplateEngine()
        val rule = fileNameRule ?: ChallengeTemplates.fileNameRule(languageId)
        val entryFileName = engine.renderFileName(rule, context, language.fileExtension)
        val content = engine.render(templateText, context)

        val challenge = Challenge(ChallengeId(ids()), problem.id, problem.title, clock())
        val root = workspaceRoot(challenge)
        val entryFile = root.resolve(entryFileName)
        Files.createDirectories(entryFile.parent)
        Files.writeString(entryFile, ensureTrailingNewline(content))

        val languageConfig =
            ChallengeLanguage(
                id = ChallengeLanguageId(ids()),
                challengeId = challenge.id,
                language = languageId,
                languageVersion = "",
                template = templateText,
                entryFilePath = entryFileName,
            )
        challengeRepository.save(challenge)
        challengeRepository.saveLanguage(languageConfig)
        return CreatedChallenge(challenge, languageConfig, problem, root, entryFile)
    }

    /**
     * 在工作区下新建一个解法:按语言模板重新渲染为独立文件(solutions/ 子目录),
     * 附解法名/笔记头注释,连同 note 落库。
     *
     * @param template 覆盖模板(修复#45:对齐原版"生成时读设置"语义——设置面板改过
     *   模板后,新建解法用**当前**覆盖而非工作区落库时的旧模板);null 用落库模板
     */
    suspend fun createSolution(
        created: CreatedChallenge,
        name: String,
        note: String,
        template: String? = null,
    ): Solution {
        val language = requireNotNull(WorkspaceLanguage.fromId(created.language.language))
        val (beginRegion, endRegion) = ChallengeTemplates.regionMarkers(language.id)
        val slug = slugFor(created.problem)
        val context =
            templateContext(
                "challenge" to
                    mapOf(
                        "name" to created.problem.title,
                        "frontendQuestionId" to created.problem.remoteId,
                        "difficulty" to created.problem.difficulty.value,
                        "codeDojo" to created.problem.platformId.value,
                        "slug" to slug,
                        "url" to created.problem.url,
                        "code" to ChallengeTemplates.defaultCode(language.id),
                    ),
                "beginRegion" to beginRegion,
                "endRegion" to endRegion,
            )
        val fileName = "${Naming.camelCaseName(name)}.${language.fileExtension}"
        val header =
            listOf(
                "${language.commentPrefix} Solution: $name",
                note.lineSequence().firstOrNull()?.let { "${language.commentPrefix} Note: $it" },
            ).filterNotNull()
        val rendered = TemplateEngine().render(template ?: created.language.template, context)
        val content = header.joinToString("\n", postfix = "\n\n") + ensureTrailingNewline(rendered)
        val filePath = SOLUTIONS_DIR + "/" + fileName
        val file = created.workspaceRoot.resolve(filePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)

        val solution =
            Solution(
                id = SolutionId(ids()),
                challengeId = created.challenge.id,
                name = name,
                noteMd = note,
                filePath = filePath,
                createdAt = clock(),
            )
        solutionRepository.save(solution)
        return solution
    }

    /** 新建或更新测试用例([existingId] 非空即更新)。 */
    suspend fun saveTestCase(
        challengeId: ChallengeId,
        input: String,
        expected: String,
        existingId: TestCaseId? = null,
        source: TestCaseSource = TestCaseSource.USER,
    ): TestCase {
        val testCase =
            TestCase(
                id = existingId ?: TestCaseId(ids()),
                challengeId = challengeId,
                input = input,
                expected = expected,
                source = source,
            )
        testCaseRepository.save(testCase)
        return testCase
    }

    /** 删除测试用例。@return 是否确实删除了一条。 */
    suspend fun deleteTestCase(id: TestCaseId): Boolean = testCaseRepository.delete(id)

    /**
     * slug 推导:remoteId 可安全作标识符开头(字母开头、仅字母数字-_ )时直接用,
     * 否则(如 CF "1927A"、纯数字)回退 slugify(title)。
     */
    private fun slugFor(problem: Problem): String = problem.remoteId.takeIf { it.matches(SAFE_SLUG) } ?: Naming.slugify(problem.title)

    private fun ensureTrailingNewline(content: String): String = if (content.endsWith("\n")) content else content + "\n"

    private companion object {
        val SAFE_SLUG = Regex("""^[A-Za-z][A-Za-z0-9_-]*$""")

        /** 多解法文件子目录(相对工作区根)。 */
        const val SOLUTIONS_DIR = "solutions"
    }
}
