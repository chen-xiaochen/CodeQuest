package com.codequest.ui.toolwindow

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 插件自带图标(1.5.0):工具窗/插件徽章——蓝绿渐变 CQ 字母。
 * `cqToolWindow.svg` 与 `cqToolWindow_dark.svg` 成对,IconLoader 按明暗主题自动切换。
 */
object CQIcons {
    val toolWindow: Icon = IconLoader.getIcon("/icons/cqToolWindow.svg", CQIcons::class.java)
}
