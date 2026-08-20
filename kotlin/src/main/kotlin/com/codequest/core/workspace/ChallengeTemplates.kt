package com.codequest.core.workspace

/**
 * 内置入口文件模板与文件名规则(WP16;WP44 增补风格双默认)。
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

    /** 各语言默认入口模板(LEETCODE 函数版,既有调用方零破坏);未知语言抛 [IllegalArgumentException]。 */
    fun entryTemplate(languageId: String): String = entryTemplate(TemplateStyle.LEETCODE, languageId)

    /**
     * 按风格返回各语言默认入口模板(WP44);未知语言抛 [IllegalArgumentException]。
     *
     * - [TemplateStyle.LEETCODE]:Solution 函数版,与历史单参版本字节级一致;
     * - [TemplateStyle.ACM]:stdin/stdout 竞赛骨架,main 读输入留 TODO。
     */
    fun entryTemplate(
        style: TemplateStyle,
        languageId: String,
    ): String {
        val language = requireNotNull(WorkspaceLanguage.fromId(languageId)) { "unsupported language: $languageId" }
        return when (style) {
            TemplateStyle.LEETCODE -> leetcodeEntryTemplate(language)
            TemplateStyle.ACM -> acmEntryTemplate(language)
        }
    }

    /** LEETCODE 函数版入口模板(WP19 历史默认,WP44 抽出;内容保持字节级不变)。 */
    private fun leetcodeEntryTemplate(language: WorkspaceLanguage): String =
        when (language.id) {
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

    /** ACM 竞赛版入口模板(WP44):stdin/stdout 骨架,main 读输入留 TODO。 */
    private fun acmEntryTemplate(language: WorkspaceLanguage): String =
        when (language.id) {
            "java" -> {
                """
                |import java.io.BufferedReader;
                |import java.io.IOException;
                |import java.io.InputStreamReader;
                |import java.util.*;
                |
                |/**
                | * 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
                | * 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
                | * 链接:${'$'}{challenge.url}
                | * 生成时间:${'$'}{velocity.dateTime()}
                | */
                |class ${'$'}{velocity.camelCaseName(${'$'}{challenge.slug})} {
                |
                |    ${'$'}{beginRegion}
                |    ${'$'}{challenge.code}
                |    ${'$'}{endRegion}
                |
                |    public static void main(String[] args) throws IOException {
                |        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                |        // TODO: 按题面输入格式 reader.readLine() 读取,System.out.println 输出
                |    }
                |}
                """.trimMargin()
            }

            "kotlin" -> {
                """
                |// 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
                |// 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
                |// 链接:${'$'}{challenge.url}
                |// 生成时间:${'$'}{velocity.dateTime()}
                |
                |${'$'}{beginRegion}
                |${'$'}{challenge.code}
                |${'$'}{endRegion}
                |
                |fun main() {
                |    // TODO: readLine() 按题面输入格式读取,println 输出
                |}
                """.trimMargin()
            }

            "python3" -> {
                """
                |# 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
                |# 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
                |# 链接:${'$'}{challenge.url}
                |# 生成时间:${'$'}{velocity.dateTime()}
                |
                |${'$'}{beginRegion}
                |${'$'}{challenge.code}
                |${'$'}{endRegion}
                |
                |# TODO: input()/sys.stdin 按题面输入格式读取,print 输出
                """.trimMargin()
            }

            "cpp" -> {
                """
                |#include <bits/stdc++.h>
                |using namespace std;
                |
                |// 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
                |// 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
                |// 链接:${'$'}{challenge.url}
                |// 生成时间:${'$'}{velocity.dateTime()}
                |
                |${'$'}{beginRegion}
                |${'$'}{challenge.code}
                |${'$'}{endRegion}
                |
                |int main() {
                |    // TODO: cin 按题面输入格式读取,cout 输出
                |    return 0;
                |}
                """.trimMargin()
            }

            "go" -> {
                """
                |package main
                |
                |import (
                |    "bufio"
                |    "os"
                |)
                |
                |// 题目:${'$'}{challenge.name}(题号:${'$'}{challenge.frontendQuestionId})
                |// 难度:${'$'}{challenge.difficulty} 平台:${'$'}{challenge.codeDojo}
                |// 链接:${'$'}{challenge.url}
                |// 生成时间:${'$'}{velocity.dateTime()}
                |
                |${'$'}{beginRegion}
                |${'$'}{challenge.code}
                |${'$'}{endRegion}
                |
                |func main() {
                |    in := bufio.NewScanner(os.Stdin)
                |    for in.Scan() {
                |        // TODO: in.Text() 按题面输入格式解析并输出(需打印时自行补 import "fmt")
                |    }
                |}
                """.trimMargin()
            }

            else -> {
                defaultPlainTemplate(language)
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
