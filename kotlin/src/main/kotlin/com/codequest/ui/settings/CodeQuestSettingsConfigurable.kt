package com.codequest.ui.settings

import com.codequest.infra.db.DatabaseFolders
import com.codequest.infra.i18n.PluginBundle
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JComponent

/**
 * CodeQuest 设置面板(WP15):Kotlin UI DSL,Configurable + [TextFieldWithBrowseButton]。
 *
 * 「数据库文件夹」校验语义(与旧版"路径不能为空"的差别,写明行为):
 * - **空值 = 默认态**,始终合法:数据库落在默认目录 `~/.codequest/`(面板注释展示当前默认目录),
 *   因此空值不报错——这正是回退默认目录的入口;
 * - **非空值**要求:必须是绝对路径(`settings.databaseFolder.error.invalid`),
 *   且若已存在则必须是目录而非文件(`settings.databaseFolder.error.notDirectory`);
 *   校验时机在 [apply](面板 OK/Apply 时抛 [ConfigurationException] 阻止保存)。
 *
 * apply 成功后经项目消息总线发布 [CodeQuestSettings.DATABASE_FOLDER_TOPIC],
 * `infra.db.DatabaseManager` 收到后 close 旧库并按新路径重建。
 */
class CodeQuestSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private var folderField: TextFieldWithBrowseButton? = null

    override fun getDisplayName(): String = PluginBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val field = TextFieldWithBrowseButton()
        field.addBrowseFolderListener(
            PluginBundle.message("settings.databaseFolder.title"),
            null,
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
        folderField = field
        return panel {
            row(PluginBundle.message("settings.databaseFolder.label")) {
                cell(field).align(Align.FILL)
            }
            row {
                comment(
                    PluginBundle.message(
                        "settings.databaseFolder.description",
                        DatabaseFolders.defaultFolder().toString(),
                    ),
                )
            }
        }
    }

    override fun isModified(): Boolean {
        val stored = CodeQuestSettings.getInstance(project).state.databaseFolder
        return folderField?.text?.trim() != stored
    }

    override fun reset() {
        folderField?.text = CodeQuestSettings.getInstance(project).state.databaseFolder
    }

    override fun apply() {
        val text = folderField?.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val path = Paths.get(text)
            if (!path.isAbsolute) {
                throw ConfigurationException(PluginBundle.message("settings.databaseFolder.error.invalid"))
            }
            if (Files.exists(path) && !Files.isDirectory(path)) {
                throw ConfigurationException(PluginBundle.message("settings.databaseFolder.error.notDirectory"))
            }
        }
        val effectiveFile = CodeQuestSettings.getInstance(project).updateDatabaseFolder(text)
        project.messageBus
            .syncPublisher(CodeQuestSettings.DATABASE_FOLDER_TOPIC)
            .databaseFolderChanged(effectiveFile.parent)
    }

    override fun disposeUIResources() {
        folderField = null
    }
}
