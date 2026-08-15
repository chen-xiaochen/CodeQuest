package com.codequest.core.workspace

/**
 * 内置入口文件模板与文件名规则(WP16)。
 *
 * 模板上下文沿用 spec 2026-08-03 的 Velocity 变量约定:
 * `challenge.{name, frontendQuestionId, difficulty, codeDojo, slug, url, code}` +
 * `velocity.{camelCaseName, smallCamelCaseName, snakeCaseName, slugify, dateTime}` +
 * `beginRegion` / `endRegion`(提交区域标记,M3 提交链路按标记提取)。
 */
object ChallengeTemplates {
    /** 提交区域标记(与旧实现 extractCodeFromRegion 约定一致,勿改文案)。 */
    const val REGION_BEGIN = "IMPORTANT!! Submit Code Region Begin(Do not remove this line)"
    const val REGION_END = "IMPORTANT!! Submit Code Region End(Do not remove this line)"

    /** 各语言默认入口模板;未知语言抛 [IllegalArgumentException]。 */
    fun entryTemplate(languageId: String): String {
        val language = requireNotNull(WorkspaceLanguage.fromId(languageId)) { "unsupported language: $languageId" }
        val header = language.commentPrefix
        return when (languageId) {
            "java" -> {
                """
import java.util.*;

/**
 * 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
 * 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
 * 链接:${'$'}{challenge.url}
 * 生成时间:${'$'}{velocity.dateTime()}
 */
class ${'$'}{velocity.camelCaseName(${'$'}{challenge.slug})} {

    ${'$'}{beginRegion}
    ${'$'}{challenge.code}
    ${'$'}{endRegion}

    public static void main(String[] args) {
        // TODO: 调用 Solution 方法并用 System.out.println 打印结果验证
    }
}

                """.trimMargin()
            }

            "kotlin" -> {
                """
// 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
// 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
// 链接:${'$'}{challenge.url}
// 生成时间:${'$'}{velocity.dateTime()}

${'$'}{beginRegion}
${'$'}{challenge.code}
${'$'}{endRegion}

fun main() {
    // TODO: readLine() 读取输入并 println 结果
}
                """.trimMargin()
            }

            else -> {
                defaultPlainTemplate(language)
            }
        }
    }

    /** 文件名规则(默认 PascalCase + 语言扩展名)。 */
    fun fileNameRule(languageId: String): String {
        val language = requireNotNull(WorkspaceLanguage.fromId(languageId)) { "unsupported language: $languageId" }
        return "\${velocity.camelCaseName(\${challenge.slug})}.${language.fileExtension}"
    }

    /** 语言默认代码桩(用户未提供平台代码时兜底,保证本地可编译运行)。 */
    fun defaultCode(languageId: String): String =
        when (languageId) {
            "java" -> "class Solution {}"
            "kotlin" -> "class Solution"
            else -> ""
        }

    /** 提交区域标记对(按语言注释前缀包装)。 */
    fun regionMarkers(languageId: String): Pair<String, String> {
        val language = requireNotNull(WorkspaceLanguage.fromId(languageId)) { "unsupported language: $languageId" }
        return "${language.commentPrefix}$REGION_BEGIN" to "${language.commentPrefix}$REGION_END"
    }

    private fun defaultPlainTemplate(language: WorkspaceLanguage): String {
        val header = language.commentPrefix
        return """
            |$header 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
            |$header 链接:${'$'}{challenge.url}
            |
            |${'$'}{beginRegion}
            |${'$'}{challenge.code}
            |${'$'}{endRegion}
            |
            """.trimMargin()
    }
}
