package com.codequest.core.workspace

/**
 * 命名工具(WP16):沿用旧 VelocityTool 的语义(spec 2026-08-03),
 * 供模板上下文 `velocity.*` 工具方法与工作区目录命名使用。
 */
object Naming {
    /** 按空白/连字符/下划线分词、逐词首字母大写后拼接:two-sum → TwoSum。 */
    fun camelCaseName(str: String?): String = tokenize(str).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    /** 同 [camelCaseName] 但首字母小写:two-sum → twoSum。 */
    fun smallCamelCaseName(str: String?): String =
        camelCaseName(str).let { name -> if (name.isEmpty()) "" else name[0].lowercaseChar() + name.substring(1) }

    /** 转 snake_case:TwoSum / two-sum → two_sum。 */
    fun snakeCaseName(str: String?): String =
        (str ?: "")
            .split(Regex("(?=[A-Z])"))
            .flatMap { it.split(WORD_SEPARATOR) }
            .filter { it.isNotEmpty() }
            .joinToString("_") { it.lowercase() }

    /** 转连字符小写 slug:Two Sum → two-sum。 */
    fun slugify(str: String?): String = tokenize(str).joinToString("-") { it.lowercase() }

    private fun tokenize(str: String?): List<String> = (str ?: "").split(WORD_SEPARATOR).filter { it.isNotEmpty() }

    private val WORD_SEPARATOR = Regex("[\\s_-]")
}
