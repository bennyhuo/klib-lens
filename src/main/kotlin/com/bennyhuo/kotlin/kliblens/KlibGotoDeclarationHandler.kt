package com.bennyhuo.kotlin.kliblens

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import java.util.WeakHashMap
import org.jetbrains.kotlin.idea.references.KtReference

object KlibLensNavigationCache {
    val pendingNavigations = WeakHashMap<VirtualFile, PsiElement>()
}

class KlibGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        // 1. Attempt to find the source reference
        val reference = sourceElement.parent?.references?.firstOrNull { it is KtReference } ?: return null
        
        // 2. Call the native resolve to get the real target (without offset interference)
        val target = reference.resolve() ?: return null
        val targetFile = target.containingFile?.virtualFile ?: return null

        // 3. Ensure the target is actually a Klib target
        
        if (targetFile.isKnmFile()) {
            // Check if there are attached sources (navigationElement points to the actual source code if attached)
            if (target.navigationElement?.containingFile?.virtualFile?.isKnmFile() != false) {
                KlibLensNavigationCache.pendingNavigations[targetFile] = target
            }
        }
        return null
    }
}