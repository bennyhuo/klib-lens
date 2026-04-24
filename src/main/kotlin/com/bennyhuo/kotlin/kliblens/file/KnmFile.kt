package com.bennyhuo.kotlin.kliblens.file

import com.bennyhuo.kotlin.kliblens.metadata.KlibMetadataDecompiler
import com.bennyhuo.kotlin.kliblens.metadata.KlibMetadataExtractor
import com.bennyhuo.kotlin.kliblens.LOG
import com.bennyhuo.kotlin.kliblens.module.NonNativeDanglingFileModule
import com.bennyhuo.kotlin.kliblens.utils.invokeLater
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.codeInsight.daemon.impl.analysis.HighlightLevelUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaDanglingFileModuleImpl
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.explicitModule
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.platform.konan.NativePlatform
import org.jetbrains.kotlin.psi.KtFile

class KnmFile(
    val project: Project,
    val originalFile: VirtualFile
)  {

    var isBeautified: Boolean = true
        private set

    var beautifiedText: String? = null
        private set

    val originalPsiFile: KtFile by lazy {
        requireNotNull(PsiManager.getInstance(project).findFile(originalFile)) {
            "PsiFile not found for $newFile"
        } as KtFile
    }

    val newFile: LightVirtualFile
    
    val newDocument: Document by lazy {
        requireNotNull(FileDocumentManager.getInstance().getDocument(newFile)) {
            "Document not found for $newFile"
        }
    }
    
    val newPsiFile: KtFile by lazy {
        requireNotNull(PsiManager.getInstance(project).findFile(newFile)) {
            "PsiFile not found for $newFile"
        } as KtFile
    }
    
    init {
        newFile = createBeautifiedVirtualFile()
    }
    
    private fun createBeautifiedVirtualFile(): LightVirtualFile {
        val extractor = KlibMetadataExtractor(originalFile)
        val decompiler = KlibMetadataDecompiler(project)

        val text = decompiler.decompile(originalFile, extractor)

        // Workaround: Mask the .knm file as .kt to trigger the IDE's Kotlin code analysis and highlighting.
        val lightFileName = originalFile.nameWithoutExtension + ".kt"
        val lightFile = LightVirtualFile(lightFileName, KotlinFileType.INSTANCE, text)
        lightFile.originalFile = originalFile

        project.invokeLater {
            highlightFile()
            formatFile()
        }
                
        return lightFile
    }

    @OptIn(KaExperimentalApi::class)
    fun toggleBeautification() {
        isBeautified = !isBeautified
        val targetText = if (isBeautified) beautifiedText ?: "" else originalPsiFile.text
        
        WriteCommandAction.runWriteCommandAction(project, "Toggle Klib Beautification", null, {
            newFile.isWritable = true
            newDocument.setText(targetText)
            PsiDocumentManager.getInstance(project).commitDocument(newDocument)
            
            FileDocumentManager.getInstance().saveDocument(newDocument)
            newFile.isWritable = false
        })
    }

    private fun formatFile() {
        WriteCommandAction.runWriteCommandAction(project, "Reformat Klib Code", null, {
            // Reformat using IntelliJ Code Style
            CodeStyleManager.getInstance(project).reformat(newPsiFile)
            // Sync PSI -> Document
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(newDocument)
            
            // Cache the beautified text
            beautifiedText = newDocument.text
            
            // Sync Document -> VirtualFile (crucial for OpenFileDescriptor)
            FileDocumentManager.getInstance().saveDocument(newDocument)

            // Seal the file as read-only ONCE formatting is completely done
            newFile.isWritable = false
        })
    }

    @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
    private fun highlightFile() {
        // Workaround: Create a custom KaDanglingFileModule that delegates to KaDanglingFileModuleImpl
        // but overrides targetPlatform to avoid pure NativePlatform. This makes the session factory
        // selector (LLFirSessionCache.createPlatformAwareSessionFactory) choose LLFirCommonSessionFactory
        // instead of LLFirNativeSessionFactory, avoiding the buggy FirNativeOverrideChecker.
        //
        // This pattern is used by Kotlin itself in KaFirCompilerFacility.createJvmDanglingFileModule().
        try {
            val libraryModule = KotlinProjectStructureProvider.getModule(project, originalPsiFile, useSiteModule = null)
            val baseModule = KaDanglingFileModuleImpl(
                listOf(newPsiFile),
                libraryModule,
                KaDanglingFileResolutionMode.PREFER_SELF,
            )

            val isNativePlatform = libraryModule.targetPlatform.all { it is NativePlatform }
            newPsiFile.explicitModule = if (isNativePlatform) {
                // Wrap with overridden targetPlatform to avoid LLFirNativeSessionFactory
                NonNativeDanglingFileModule(baseModule)
            } else {
                baseModule
            }
        } catch (e: Exception) {
            LOG.warn("[KLIB-LENS] Failed to set up analysis context for $newFile", e)
        }
        
        // Set highlighting level to ESSENTIAL to keep semantic colors but skip heavy inspections
        HighlightLevelUtil.forceRootHighlighting(newPsiFile, FileHighlightingSetting.ESSENTIAL)
    }
}