package com.codequest.ui.challenge

import com.codequest.ui.submission.ChallengeEditorGate
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider

/**
 * 编辑器浮动工具栏 provider(WP55,EP `com.intellij.editorFloatingToolbarProvider`):
 *
 * 当前编辑器文件属于任一挑战工作区时,在编辑器右上角浮出三键组
 * `CodeQuest.ChallengeEditor.FloatingToolbar`(修改测试用例/提交测试/提交)。
 *
 * 2026.2 平台接口核对结果:`FloatingToolbarProvider` 为 Kotlin 接口,抽象方法仅
 * `getActionGroup()`;可见性判定挂点为 `isApplicableAsync(DataContext): Boolean`
 * (suspend,默认实现转同步 `isApplicable`)。基类 [AbstractFloatingToolbarProvider]
 * 构造参数即 actionGroupId(懒解析为 ActionGroup),故子类只需覆写可见性判定。
 *
 * 判定走 [ChallengeEditorGate](TTL 快照缓存,路径前缀匹配),不逐次查库;
 * 组内各 action 自行做「平台无能力隐藏/未登录置灰」的 update 门控。
 */
class ChallengeFloatingToolbarProvider : AbstractFloatingToolbarProvider(ChallengeFloatingToolbarProvider.ACTION_GROUP_ID) {
    override suspend fun isApplicableAsync(dataContext: DataContext): Boolean {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return false
        val file = dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return false
        val path = runCatching { file.toNioPath() }.getOrNull() ?: return false
        return project.getService(ChallengeEditorGate::class.java).isChallengeFile(path)
    }

    companion object {
        /** plugin.xml 中 `<group id>` 与三键注册处同名字符串的唯一来源。 */
        const val ACTION_GROUP_ID = "CodeQuest.ChallengeEditor.FloatingToolbar"
    }
}
