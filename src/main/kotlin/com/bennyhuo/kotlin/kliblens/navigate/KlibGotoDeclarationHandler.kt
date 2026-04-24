package com.bennyhuo.kotlin.kliblens.navigate

import com.bennyhuo.kotlin.kliblens.utils.isKnmFile
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import java.util.WeakHashMap
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

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

        // 3. Check if the target is within a LightVirtualFile that represents a decompiled .knm.
        //    This happens when clicking a reference inside the beautified .kt view that resolves
        //    to a symbol declared within the same decompiled file (e.g., clicking MyClass in the
        //    same 0_objclib.knm view where MyClass is declared). Without this, the IDE would open
        //    a new tab for the LightVirtualFile (0_objclib.kt) instead of navigating in-place.
        if (targetFile is LightVirtualFile && targetFile.originalFile?.isKnmFile() == true) {
            val originalKnmFile = targetFile.originalFile
            val project = sourceElement.project
            val originalPsiFile = PsiManager.getInstance(project).findFile(originalKnmFile) as? KtFile
                ?: return null
            // Reverse-lookup: find the corresponding declaration in the original .knm PSI
            val targetDecl = target as? KtNamedDeclaration
                ?: PsiTreeUtil.getParentOfType(target, KtNamedDeclaration::class.java)
                ?: return null
            val originalDecl = KlibNavigationHandler().findCounterpart(originalPsiFile, targetDecl)
            if (originalDecl != null) {
                KlibLensNavigationCache.pendingNavigations[originalKnmFile] = originalDecl
                return arrayOf(originalDecl)
            }
            return null
        }

        // 4. Ensure the target is actually a Klib target
        if (targetFile.isKnmFile()) {
            // Check if there are attached sources (navigationElement points to the actual source code if attached)
            if (target.navigationElement?.containingFile?.virtualFile?.isKnmFile() != false) {
                KlibLensNavigationCache.pendingNavigations[targetFile] = target
            }
        }
        return null
    }
}