package com.codequest.infra.i18n

import java.util.ResourceBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [unit] i18n bundle 完整性(WP15):中英键集合一致 + 必需键存在。
 *
 * 直接以 ResourceBundle 加载测试 classpath 上的 messages/PluginBundle*.properties
 * (与运行时 DynamicBundle 读取的是同一份资源)。
 */
class PluginBundleKeysTest {
    private val english = bundleFor("")
    private val chinese = bundleFor("_zh")

    private fun bundleFor(suffix: String): ResourceBundle =
        ResourceBundle.getBundle(
            "messages.PluginBundle$suffix",
            java.util.Locale.ROOT,
            PluginBundle::class.java.classLoader,
        )

    private val requiredKeys = setOf(
        "settings.displayName",
        "settings.databaseFolder.label",
        "settings.databaseFolder.title",
        "settings.databaseFolder.description",
        "settings.databaseFolder.error.invalid",
        "settings.databaseFolder.error.notDirectory",
        // WP21:提交 action/对话框/通知
        "action.submitToOj.text",
        "action.submitToOj.description",
        "submission.progress.submitting",
        "submission.progress.judging",
        "submission.notification.title",
        "submission.notification.accepted",
        "submission.notification.failed",
        "submission.error.alreadyInProgress",
        "submission.error.notChallengeFile",
        "submission.error.noLanguage",
        "submission.error.languageNotSubmittable",
        "submission.error.platformNotSubmittable",
        "submission.error.regionMarkersMissing",
        "submission.error.emptyRegion",
        "submission.error.cookieRequired",
        "submission.error.authExpired",
        "submission.error.submitFailed",
        "submission.verdict.accepted",
        "submission.verdict.wrongAnswer",
        "submission.verdict.timeLimitExceeded",
        "submission.verdict.memoryLimitExceeded",
        "submission.verdict.outputLimitExceeded",
        "submission.verdict.runtimeError",
        "submission.verdict.compileError",
        "submission.verdict.partialScore",
        "submission.verdict.unknown",
        "submission.verdict.pending",
        "submission.verdict.judging",
        "cookieDialog.title",
        "cookieDialog.description",
        "cookieDialog.cookiesLabel",
        // WP51:主侧栏三 tab + 工具窗显示名 + 挑战面板/对话框/运行输出
        "toolwindow.panel.problems.title",
        "toolwindow.panel.problems.description",
        "toolwindow.panel.contest.title",
        "toolwindow.panel.contest.description",
        "toolwindow.panel.stats.title",
        "toolwindow.panel.stats.description",
        "toolwindow.panel.jcefFallback",
        "toolwindow.displayName.main",
        "toolwindow.displayName.problemView",
        "toolwindow.displayName.challenge",
        "challenge.panel.tab.workspace",
        "challenge.panel.tab.submissions",
        "challenge.panel.submissionsFallback",
        "challenge.panel.challenge.label",
        "challenge.panel.run.case",
        "challenge.panel.run.summary",
        "challenge.dialog.new.title",
        "challenge.dialog.import.title",
    )

    private fun keysOf(bundle: ResourceBundle): Set<String> = bundle.keys.toList().toSet()

    @Test
    fun `english and chinese bundles expose identical key sets`() {
        assertEquals(keysOf(english), keysOf(chinese))
    }

    @Test
    fun `required settings keys are present in both bundles`() {
        requiredKeys.forEach { key ->
            assertTrue(english.containsKey(key), "missing in PluginBundle.properties: $key")
            assertTrue(chinese.containsKey(key), "missing in PluginBundle_zh.properties: $key")
        }
    }

    @Test
    fun `no placeholder mismatch between locales`() {
        // {0} 之类参数占位符在中英文中数量必须一致,否则运行时 format 抛异常
        val placeholder = Regex("""\{\d+}""")
        keysOf(english).forEach { key ->
            assertEquals(
                placeholder.findAll(english.getString(key)).count(),
                placeholder.findAll(chinese.getString(key)).count(),
                "placeholder count mismatch for key: $key",
            )
        }
    }
}
