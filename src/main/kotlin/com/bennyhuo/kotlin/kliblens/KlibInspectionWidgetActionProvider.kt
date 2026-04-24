package com.bennyhuo.kotlin.kliblens

import com.bennyhuo.kotlin.kliblens.file.KnmFile
import com.bennyhuo.kotlin.kliblens.utils.isKnmFile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.InspectionWidgetActionProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.LightVirtualFile

/**
 * Provides the "Beautified" toggle action to the EditorInspectionsActionToolbar,
 * placing it alongside Reader Mode and the traffic light widget.
 *
 * Follows the same pattern as [com.intellij.codeInsight.actions.ReaderModeActionProvider]:
 * passes the [Editor] reference directly to the action, wraps it in a [DefaultActionGroup].
 */
class KlibInspectionWidgetActionProvider : InspectionWidgetActionProvider {
    override fun createAction(editor: Editor): AnAction? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        if (file is LightVirtualFile) {
            if (file.originalFile?.isKnmFile() == true) {
                // Wrap in a DefaultActionGroup, same pattern as ReaderModeActionProvider
                val action = KlibToggleBeautificationAction(editor)
                return object : DefaultActionGroup(action, Separator.create()) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT

                    override fun update(e: AnActionEvent) {
                        // Always visible when created by the provider:
                        // the provider already verified this is a klib editor.
                        e.presentation.isEnabledAndVisible = true
                    }
                }
            }
        }
        return null
    }
}
