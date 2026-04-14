package com.bennyhuo.kotlin.kliblens

import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.codeInsight.daemon.impl.analysis.HighlightLevelUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.NavigatableFileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.analysisContext

private val LOG = Logger.getInstance(KlibViewerEditorProvider::class.java)

class KlibViewerEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.isKnmFile()
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val originalPsi = PsiManager.getInstance(project).findFile(file)
        val extractor = KlibMetadataExtractor(file)
        val decompiler = KlibMetadataDecompiler(project)

        val text = decompiler.decompile(file, extractor)

        // Create the light file and editor immediately with partially formatted text
        val lightFile = LightVirtualFile(file.name, KotlinFileType.INSTANCE, text)
        lightFile.originalFile = file
        // Note: Do NOT mark as read-only here, otherwise CodeStyleManager will skip formatting!
        
        val editor = TextEditorProvider.getInstance().createEditor(project, lightFile) as TextEditor

        // Step 4: PSI formatting must run in a Write-safe context (EDT invokeLater)
        setupEditorFormatting(project, lightFile, originalPsi)

        return KlibViewerTextEditorWrapper(editor, project, file)
    }

    private fun setupEditorFormatting(project: Project, lightFile: LightVirtualFile, originalPsi: PsiFile?) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater

            val document = FileDocumentManager.getInstance().getDocument(lightFile) ?: return@invokeLater
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) as? KtFile ?: return@invokeLater
            // Link the light file to the original library context for symbol resolution
            if (originalPsi != null) {
                psiFile.analysisContext = originalPsi
            }
            
            // Set highlighting level to ESSENTIAL to keep semantic colors but skip heavy inspections
            HighlightLevelUtil.forceRootHighlighting(psiFile, FileHighlightingSetting.ESSENTIAL)

            WriteCommandAction.runWriteCommandAction(project, "Reformat Klib Code", null, {
                // Reformat using IntelliJ Code Style
                CodeStyleManager.getInstance(project).reformat(psiFile)
                // Sync PSI -> Document
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
                // Sync Document -> VirtualFile (crucial for OpenFileDescriptor)
                FileDocumentManager.getInstance().saveDocument(document)
                
                // Seal the file as read-only ONCE formatting is completely done
                lightFile.isWritable = false
            })
        }, ModalityState.defaultModalityState())
    }

    override fun getEditorTypeId(): String = "klib-lens-editor"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class KlibViewerTextEditorWrapper(
    private val delegate: TextEditor,
    private val project: Project,
    private val originalFile: VirtualFile
) : FileEditor by delegate, NavigatableFileEditor {

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
                val cachedSymbol = KlibLensNavigationCache.pendingNavigations.remove(navigatable.file)
                if (cachedSymbol != null) {
                    cachedSymbol
                } else {
                    val originalPsi = PsiManager.getInstance(project).findFile(originalFile) as? KtFile
                    originalPsi?.findElementAt(navigatable.offset)
                }
            }
            else -> null
        }

        if (targetElementInOriginal != null) {
            val originalDecl = targetElementInOriginal as? KtNamedDeclaration
                ?: PsiTreeUtil.getParentOfType(targetElementInOriginal, KtNamedDeclaration::class.java, false)
            if (originalDecl != null) {
                // Ensure all formatting changes are committed to PSI before searching
                PsiDocumentManager.getInstance(project).commitDocument(delegate.editor.document)
                
                val targetPsi = (PsiManager.getInstance(project).findFile(delegate.file) 
                    ?: PsiDocumentManager.getInstance(project).getPsiFile(delegate.editor.document)) as? KtFile
                    
                if (targetPsi != null) {
                    val targetDecl = navigationHandler.findCounterpart(targetPsi, originalDecl)
                    if (targetDecl != null) {
                        val newDescriptor = OpenFileDescriptor(project, delegate.file, targetDecl.textOffset)
                        delegate.navigateTo(newDescriptor)
                        return
                    }
                }
            }
        }
        
        delegate.navigateTo(navigatable)
    }

    override fun getFile(): VirtualFile {
        return originalFile
    }
}
