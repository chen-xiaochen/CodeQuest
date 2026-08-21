package com.codequest.core.workspace

/**
 * 模版可用性纯函数(WP45):按风格从持久化模版表推导「可用语言」与默认语言。
 *
 * 原版语义 = 只有用户**显式配置**过模版的 (风格,语言) 才能创建挑战;空白/缺失 =
 * 不可用(与内置默认无关——内置默认只是编辑起点,不构成可用性)。平台→风格映射
 * 已在 spi 层 `PlatformInfo.templateStyle`(WP44),调用方自取后传入本函数,core
 * 层不做平台映射。
 */
object TemplateAvailability {
    /**
     * 该风格下「已配置模版」的可用语言:[WorkspaceLanguage.ALL] 中
     * `persisted[style.storageKey(id)]` 非空白者,保持 [WorkspaceLanguage.ALL] 声明顺序。
     */
    fun usableLanguages(
        style: TemplateStyle,
        persisted: Map<String, String>,
    ): List<WorkspaceLanguage> = WorkspaceLanguage.ALL.filter { persisted[style.storageKey(it.id)].orEmpty().isNotBlank() }

    /**
     * 默认语言:Java(id="java")优先,否则取列表第一个;空列表返回 null。
     */
    fun defaultLanguage(available: List<WorkspaceLanguage>): WorkspaceLanguage? =
        available.firstOrNull { it == WorkspaceLanguage.JAVA } ?: available.firstOrNull()
}
