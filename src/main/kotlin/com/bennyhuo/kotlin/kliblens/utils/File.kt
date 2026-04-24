package com.bennyhuo.kotlin.kliblens.utils

import com.bennyhuo.kotlin.kliblens.LOG
import com.bennyhuo.kotlin.kliblens.file.KnmFile
import com.bennyhuo.kotlin.kliblens.module.NonNativeDanglingFileModule
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaDanglingFileModuleImpl
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.explicitModule
import org.jetbrains.kotlin.platform.konan.NativePlatform
import org.jetbrains.kotlin.psi.KtFile

fun VirtualFile.isKnmFile(): Boolean {
    return extension == "knm" || extension == "kotlin_builtins"
}

private val KNM_FILE_KEY = Key.create<KnmFile>("KlibLens.KnmFile")

var Editor.knmFile: KnmFile?
    get() = getUserData<KnmFile>(KNM_FILE_KEY)
    set(value) {
        putUserData(KNM_FILE_KEY, value)
    }

@OptIn(KaPlatformInterface::class, KaExperimentalApi::class)
fun KtFile.setAnalysisModuleFrom(sourceFile: KtFile) {
    // Workaround: Create a custom KaDanglingFileModule that delegates to KaDanglingFileModuleImpl
    // but overrides targetPlatform to avoid pure NativePlatform. This makes the session factory
    // selector (LLFirSessionCache.createPlatformAwareSessionFactory) choose LLFirCommonSessionFactory
    // instead of LLFirNativeSessionFactory, avoiding the buggy FirNativeOverrideChecker.
    //
    // This pattern is used by Kotlin itself in KaFirCompilerFacility.createJvmDanglingFileModule().
    try {
        val libraryModule = KotlinProjectStructureProvider.getModule(project, sourceFile, useSiteModule = null)
        val baseModule = KaDanglingFileModuleImpl(
            listOf(this),
            libraryModule,
            KaDanglingFileResolutionMode.PREFER_SELF,
        )

        val isNativePlatform = libraryModule.targetPlatform.all { it is NativePlatform }
        this.explicitModule = if (isNativePlatform) {
            // Wrap with overridden targetPlatform to avoid LLFirNativeSessionFactory
            NonNativeDanglingFileModule(baseModule)
        } else {
            baseModule
        }
    } catch (e: Exception) {
        LOG.warn("[KLIB-LENS] Failed to set up analysis context for ${this.virtualFile}", e)
    }
}