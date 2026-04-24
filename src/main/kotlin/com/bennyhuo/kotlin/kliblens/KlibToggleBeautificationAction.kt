package com.bennyhuo.kotlin.kliblens

import com.bennyhuo.kotlin.kliblens.utils.knmFile
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionButtonComponent
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.actionSystem.impl.IdeaActionButtonLook
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JComponent

/**
 * A toggle action for switching between beautified and raw metadata views.
 *
 * Following [com.intellij.codeInsight.actions.ReaderModeActionProvider.ReaderModeAction] pattern:
 * the [Editor] reference is stored directly in the action (passed from
 * [KlibInspectionWidgetActionProvider.createAction]) instead of relying on the event's data context.
 */
class KlibToggleBeautificationAction(
    private val editor: Editor
) : DumbAwareToggleAction(
    "Beautified",
    "Toggle between raw metadata and beautified Kotlin source",
    AllIcons.Actions.PrettyPrint
), CustomComponentAction {

    companion object {
        // Lighter pressed/selected background so text (#6E6E6E) stays readable
        private val LIGHT_LOOK = object : IdeaActionButtonLook() {
            override fun getStateBackground(component: JComponent, state: Int): Color? {
                if (state == ActionButtonComponent.PUSHED) {
                    return JBColor(Color(0x00, 0x00, 0x00, 0x20), Color(0xFF, 0xFF, 0xFF, 0x20))
                }
                return super.getStateBackground(component, state)
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        // Override getButtonLook() so the toolbar cannot replace our custom look.
        // Override updateUI() to re-apply font after theme switches (Swing resets it).
        val button = object : ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
            
            init {
                setupButtonStyle()
            }
            
            override fun getButtonLook(): ActionButtonLook = LIGHT_LOOK

            override fun updateUI() {
                super.updateUI()
                setupButtonStyle()
            }
            
            private fun setupButtonStyle() {
                font = JBUI.Fonts.create("Inter", 11)
                foreground = JBColor(Color(0x6E6E6E), Color(0xBBBBBB))
            }
        }
        return button
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val knmFile = editor.knmFile ?: return true
        return knmFile.isBeautified
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val knmFile = editor.knmFile ?: return
        if (knmFile.isBeautified != state) {
            knmFile.toggleBeautification()
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = editor.knmFile != null
    }
}
