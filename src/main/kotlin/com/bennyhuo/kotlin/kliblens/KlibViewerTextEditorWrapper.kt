package com.bennyhuo.kotlin.kliblens

import com.bennyhuo.kotlin.kliblens.file.KnmFile
import com.bennyhuo.kotlin.kliblens.navigate.KlibLensNavigationCache
import com.bennyhuo.kotlin.kliblens.navigate.KlibNavigationHandler
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.NavigatableFileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtNamedDeclaration

class KlibViewerTextEditorWrapper(
    private val delegate: TextEditor,
    private val knmFile: KnmFile,
) : FileEditor by delegate, NavigatableFileEditor {

    private val project: Project
        get() = knmFile.project
    private val originalFile: VirtualFile
        get() = knmFile.originalFile

    private val navigationHandler = KlibNavigationHandler()

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        if (navigatable is OpenFileDescriptor && navigatable.file == originalFile) {
            return true
        }
        return delegate.canNavigateTo(navigatable)
    }

    override fun navigateTo(navigatable: Navigatable) {
        val targetElementInOriginal = when (navigatable) {
            is PsiElement -> navigatable
            is OpenFileDescriptor -> {
                KlibLensNavigationCache.pendingNavigations.remove(navigatable.file)
                    ?: knmFile.originalPsiFile.findElementAt(navigatable.offset)
            }
            else -> null
        } ?: return

        val originalDecl = targetElementInOriginal as? KtNamedDeclaration
            ?: PsiTreeUtil.getParentOfType(targetElementInOriginal, KtNamedDeclaration::class.java, false)
        if (originalDecl != null) {
            val targetDecl = navigationHandler.findCounterpart(knmFile.newPsiFile, originalDecl)
            if (targetDecl != null) {
                val newDescriptor = OpenFileDescriptor(project, delegate.file, targetDecl.textOffset)
                delegate.navigateTo(newDescriptor)
                return
            }
        }

        delegate.navigateTo(navigatable)
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        return delegate.structureViewBuilder
    }

    override fun getFile(): VirtualFile {
        return originalFile
    }
}