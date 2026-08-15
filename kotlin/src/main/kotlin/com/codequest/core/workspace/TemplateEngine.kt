package com.codequest.core.workspace

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 轻量字符串模板引擎(WP16):沿用旧 Velocity 模板的变量约定(spec 2026-08-03),
 * 不引入 Velocity 本体。
 *
 * 支持语法:
 * - `${challenge.name}` / `$challenge.name` 变量(嵌套属性以 `.` 访问)
 * - `${velocity.camelCaseName($challenge.slug)}` 工具方法调用
 * - `${velocity.camelCaseName(${challenge.slug})}` 工具方法参数内嵌 `${}`(旧文件名规则形态)
 * - `${velocity.dateTime()}` 时间工具(时钟可注入,便于测试)
 *
 * 未知变量/未知工具**原样保留**(生成代码宁可不替换也不破坏),与未知 topic 不抛异常的
 * 桥协议哲学一致。
 */
class TemplateEngine(
    private val dateTimeProvider: () -> String = {
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    },
) {
    /** 渲染模板;[context] 为变量根映射,叶子值为字符串。 */
    fun render(
        template: String,
        context: TemplateContext,
    ): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val nextIndex = renderAt(template, i, context, sb)
            i = if (nextIndex > i) nextIndex else i + 1
        }
        return sb.toString()
    }

    /** 渲染 [template] 中从 [start] 起的一个片段;返回下一个扫描位置(未消费时返回 start)。 */
    private fun renderAt(
        template: String,
        start: Int,
        context: TemplateContext,
        sb: StringBuilder,
    ): Int {
        val c = template[start]
        if (c != '$' || start + 1 >= template.length) {
            sb.append(c)
            return start
        }
        val next = template[start + 1]
        return when {
            next == '{' -> {
                renderBraced(template, start, context, sb)
            }

            next.isIdentifierStart() -> {
                renderBareReference(template, start, context, sb)
            }

            else -> {
                sb.append(c)
                start
            }
        }
    }

    private fun renderBraced(
        template: String,
        start: Int,
        context: TemplateContext,
        sb: StringBuilder,
    ): Int {
        val end = matchingBrace(template, start + 1)
        if (end < 0) {
            sb.append(template[start])
            return start
        }
        val original = template.substring(start, end + 1)
        sb.append(eval(template.substring(start + 2, end), context) ?: original)
        return end + 1
    }

    private fun renderBareReference(
        template: String,
        start: Int,
        context: TemplateContext,
        sb: StringBuilder,
    ): Int {
        val end = identifierEnd(template, start + 1)
        sb.append(eval(template.substring(start + 1, end), context) ?: template.substring(start, end))
        return end
    }

    /**
     * 渲染文件名规则;渲染结果若未以 `.<ext>` 结尾则自动补齐扩展名
     * (规则漏写扩展名时兜底,规则显式带扩展名时以规则为准)。
     */
    fun renderFileName(
        rule: String,
        context: TemplateContext,
        extension: String,
    ): String {
        val rendered = render(rule, context)
        return if (rendered.endsWith(".$extension")) rendered else "$rendered.$extension"
    }

    // ------------------------------------------------------------------
    // 表达式求值(引用 / 工具方法)
    // ------------------------------------------------------------------

    private fun eval(
        expr: String,
        context: TemplateContext,
    ): String? {
        val trimmed = expr.trim()
        val call = TOOL_CALL.matchEntire(trimmed)
        if (call != null) {
            val (name, rawArg) = call.destructured
            if (name == DATE_TIME) return dateTimeProvider()
            val tool = TOOLS[name] ?: return null
            val arg = evalArgument(rawArg, context) ?: return null
            return tool(arg)
        }
        return resolveReference(trimmed, context)
    }

    /** 工具方法实参:`$ref`、`${ref}` 或裸 `ref` 引用;解析失败返回 null。 */
    private fun evalArgument(
        raw: String,
        context: TemplateContext,
    ): String? {
        var arg = raw.trim()
        if (arg.startsWith("\${") && arg.endsWith("}")) {
            arg = arg.substring(2, arg.length - 1)
        } else if (arg.startsWith("$")) {
            arg = arg.substring(1)
        }
        arg = arg.trim()
        if (arg.isEmpty()) return null
        return resolveReference(arg, context)
    }

    /** 逐段解析 `a.b.c`;解析失败返回 null(调用方原样保留)。 */
    @Suppress("UNCHECKED_CAST")
    private fun resolveReference(
        path: String,
        context: TemplateContext,
    ): String? {
        var node: Any? = context.root
        for (segment in path.split('.')) {
            val map = node as? Map<String, Any?> ?: return null
            node = map[segment] ?: return null
        }
        return node as? String ?: node?.toString()
    }

    /** 从 `${` 起点找配对 `}`(工具参数内嵌 `${}` 时深度 +1)。 */
    private fun matchingBrace(
        text: String,
        openIndex: Int,
    ): Int {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            if (text[i] == '{') {
                depth++
            } else if (text[i] == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun identifierEnd(
        text: String,
        start: Int,
    ): Int {
        var i = start
        while (i < text.length && (text[i].isIdentifierPart())) i++
        return i
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || isLetter()

    private fun Char.isIdentifierPart(): Boolean = this == '_' || this == '.' || isLetterOrDigit()

    private companion object {
        const val DATE_TIME = "dateTime"

        /** `velocity.func(arg)`,实参允许含 `${}` / `$ref` / 空括号。 */
        val TOOL_CALL = Regex("""velocity\.(\w+)\((.*)\)""", RegexOption.DOT_MATCHES_ALL)

        val TOOLS: Map<String, (String) -> String> =
            mapOf(
                "camelCaseName" to Naming::camelCaseName,
                "smallCamelCaseName" to Naming::smallCamelCaseName,
                "snakeCaseName" to Naming::snakeCaseName,
                "slugify" to Naming::slugify,
                DATE_TIME to { "" },
            )
    }
}

/**
 * 模板上下文:根映射的轻包装。键与旧 Velocity 约定一致
 * (`challenge.*` / `velocity.*` 工具 / `beginRegion` / `endRegion`)。
 */
class TemplateContext internal constructor(
    internal val root: Map<String, Any?>,
)

/** 构建模板上下文:`templateContext("challenge" to mapOf(...), "beginRegion" to "// ...")`。 */
fun templateContext(vararg entries: Pair<String, Any?>): TemplateContext = TemplateContext(linkedMapOf(*entries))
