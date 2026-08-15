package com.codequest.core.workspace

/**
 * 工作区语言描述(WP16)。
 *
 * 与旧 Scala `Language` 枚举对应,但只保留工作区需要的最小信息
 * (id / 文件扩展名 / 行注释前缀);新语言接入 = 在 [ALL] 里加一行。
 */
data class WorkspaceLanguage(
    /** 语言标识,与 ChallengeLanguage.language 一致,如 "kotlin"。 */
    val id: String,
    /** 源文件扩展名(不含点),如 "kt"。 */
    val fileExtension: String,
    /** 展示名,如 "Kotlin"。 */
    val displayName: String,
    /** 行注释前缀,用于提交区域标记与文件头注释,如 "//"。 */
    val commentPrefix: String,
) {
    override fun toString(): String = displayName

    companion object {
        val KOTLIN = WorkspaceLanguage("kotlin", "kt", "Kotlin", "//")
        val JAVA = WorkspaceLanguage("java", "java", "Java", "//")
        val PYTHON = WorkspaceLanguage("python3", "py", "Python", "#")
        val CPP = WorkspaceLanguage("cpp", "cc", "C++", "//")
        val GO = WorkspaceLanguage("go", "go", "Go", "//")

        /** 已知语言注册表;未列出的语言创建工作区时被拒绝(扩展点:追加条目即可)。 */
        val ALL: List<WorkspaceLanguage> = listOf(KOTLIN, JAVA, PYTHON, CPP, GO)

        fun fromId(id: String): WorkspaceLanguage? = ALL.firstOrNull { it.id == id }
    }
}
