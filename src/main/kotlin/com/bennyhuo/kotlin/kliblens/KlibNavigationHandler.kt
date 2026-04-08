package com.bennyhuo.kotlin.kliblens

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtTypeAlias

class KlibNavigationHandler {

    private data class DeclarationPathElement(
        val type: Class<out KtNamedDeclaration>,
        val name: String?,
        val index: Int
    )

    fun findCounterpart(file: KtFile, originalDecl: KtNamedDeclaration): KtNamedDeclaration? {
        val path = buildPath(originalDecl)
        return findCounterpartByPath(file, path)
    }

    private fun getEffectiveParent(element: PsiElement): KtNamedDeclaration? {
        val parent = PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, true)
        return if (parent is KtScript) getEffectiveParent(parent) else parent
    }

    private fun buildPath(declaration: KtNamedDeclaration): List<DeclarationPathElement> {
        val path = mutableListOf<DeclarationPathElement>()
        var current: KtNamedDeclaration? = declaration
        while (current != null) {
            val parent = getEffectiveParent(current)
            val canonicalType = getCanonicalType(current)
            
            val candidates = findCandidates(current.containingFile, parent, canonicalType, current.name)
            val index = candidates.indexOf(current)
            
            path.add(0, DeclarationPathElement(canonicalType, current.name, index))
            current = parent
        }
        return path
    }

    private fun findCandidates(
        file: PsiFile,
        parent: KtNamedDeclaration?,
        type: Class<out KtNamedDeclaration>,
        name: String?
    ): List<KtNamedDeclaration> {
        val searchRoot = parent ?: file
        return PsiTreeUtil.findChildrenOfType(searchRoot, KtNamedDeclaration::class.java)
            .filter { 
                type.isInstance(it) &&
                it.name == name && 
                getEffectiveParent(it) == parent 
            }
    }

    private fun getCanonicalType(declaration: KtNamedDeclaration): Class<out KtNamedDeclaration> {
        return when (declaration) {
            is KtClass -> KtClass::class.java
            is KtObjectDeclaration -> KtObjectDeclaration::class.java
            is KtNamedFunction -> KtNamedFunction::class.java
            is KtProperty -> KtProperty::class.java
            is KtParameter -> KtParameter::class.java
            is KtTypeAlias -> KtTypeAlias::class.java
            is KtConstructor<*> -> KtConstructor::class.java
            is KtScript -> KtScript::class.java
            else -> KtNamedDeclaration::class.java
        }
    }

    private fun findCounterpartByPath(file: KtFile, path: List<DeclarationPathElement>): KtNamedDeclaration? {
        var lastResult: KtNamedDeclaration? = null
        for (step in path) {
            val candidates = findCandidates(file, lastResult, step.type, step.name)
            if (step.index in candidates.indices) {
                lastResult = candidates[step.index]
            } else {
                return null
            }
        }
        return lastResult
    }
}
