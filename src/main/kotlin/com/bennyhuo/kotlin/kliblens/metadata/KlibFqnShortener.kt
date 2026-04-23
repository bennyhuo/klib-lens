package com.bennyhuo.kotlin.kliblens.metadata

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtUserType

internal object KlibFqnShortener {

    /** Kotlin default-imported packages that never need explicit import statements. */
    private val KOTLIN_DEFAULT_PACKAGES = setOf(
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text"
    )

    @OptIn(KaAllowAnalysisOnEdt::class)
    fun shortenFqNames(project: Project, text: String, originalPsi: KtFile?): Pair<String, Set<String>> {
        val ktFile = if (originalPsi != null) {
            KtPsiFactory.contextual(originalPsi).createFile(text)
        } else {
            PsiFileFactory.getInstance(project).createFileFromText("temp.kt", KotlinLanguage.INSTANCE, text) as? KtFile
        } ?: return text to emptySet()

        val imports = mutableSetOf<String>()
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        val fqNameCache = mutableMapOf<String, FqNameInfo>()

        allowAnalysisOnEdt {
            analyze(ktFile) {
                PsiTreeUtil.collectElementsOfType(ktFile, KtUserType::class.java)
                    .filter {
                        it.qualifier != null && it.parent !is KtUserType && isInPackageDirective(it)
                    }
                    .forEach { outerType ->
                        val fqn = buildFqName(outerType).takeIf { '.' in it } ?: return@forEach
                        val info = fqNameCache.getOrPut(fqn) { resolveFqNameInfo(fqn, outerType) }
                        if (!isDefaultImport(info.importPath)) imports += info.importPath
                        replacements += Triple(
                            outerType.textRange.startOffset,
                            outerType.referenceExpression?.textRange?.endOffset ?: outerType.textRange.endOffset,
                            info.simpleName
                        )
                    }

                PsiTreeUtil.collectElementsOfType(ktFile, KtDotQualifiedExpression::class.java)
                    .filter {
                        it.parent !is KtDotQualifiedExpression && isInPackageDirective(it)
                    }
                    .forEach { dotExpr ->
                        val fqn = dotExpr.text.replace(Regex("\\s+"), "").substringBefore('(')
                        if ('.' !in fqn || !fqn.matches(Regex("""[A-Za-z_][A-Za-z0-9_.]*"""))) return@forEach
                        val info = fqNameCache.getOrPut(fqn) { resolveFqNameInfo(fqn, dotExpr) }
                        if (!isDefaultImport(info.importPath)) imports += info.importPath
                        val qualifierEnd = dotExpr.text.indexOf(info.simpleName)
                        if (qualifierEnd > 0) {
                            replacements += Triple(dotExpr.textRange.startOffset, dotExpr.textRange.startOffset + qualifierEnd, "")
                        }
                    }
            }
        }

        return applyReplacements(text, replacements) to imports
    }

    private fun isInPackageDirective(element: PsiElement?): Boolean =
        PsiTreeUtil.getParentOfType(element, KtPackageDirective::class.java, KtImportDirective::class.java) == null

    private fun KaSession.resolveFqNameInfo(fqn: String, element: KtElement? = null): FqNameInfo {
        if (element != null) {
            try {
                val refExpr = (element as? KtDotQualifiedExpression)?.let { it.selectorExpression ?: it }
                    ?: (element as? KtUserType)?.referenceExpression
                    ?: element
                val symbol = refExpr.mainReference?.resolveToSymbol()
                if (symbol is KaClassLikeSymbol) {
                    val classId = symbol.classId
                    if (classId != null) {
                        return FqNameInfo(classId.shortClassName.asString(), classId.asSingleFqName().asString())
                    }
                } else if (symbol is KaCallableSymbol) {
                    val classId = symbol.callableId?.classId
                    if (classId != null) {
                        val shortName = "${classId.shortClassName.asString()}.${symbol.callableId?.callableName?.asString()}"
                        return FqNameInfo(shortName, classId.asSingleFqName().asString())
                    } else {
                        val callableId = symbol.callableId
                        if (callableId != null) {
                            return FqNameInfo(callableId.callableName.asString(), callableId.asSingleFqName().asString())
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val parts = fqn.split('.')
        val firstUpper = parts.indexOfFirst { it.first().isUpperCase() }
        return if (firstUpper >= 0) {
            FqNameInfo(parts.drop(firstUpper).joinToString("."), parts.take(firstUpper + 1).joinToString("."))
        } else {
            FqNameInfo(parts.last(), fqn)
        }
    }

    private fun buildFqName(userType: KtUserType): String {
        val parts = ArrayDeque<String>()
        var cur: KtUserType? = userType
        while (cur != null) {
            parts.addFirst(cur.referencedName ?: return "")
            cur = cur.qualifier
        }
        return parts.joinToString(".")
    }

    private fun isDefaultImport(importPath: String): Boolean {
        return importPath.substringBeforeLast('.', "") in KOTLIN_DEFAULT_PACKAGES
    }
}

internal data class FqNameInfo(val simpleName: String, val importPath: String)

internal fun applyReplacements(text: String, replacements: List<Triple<Int, Int, String>>): String {
    val builder = StringBuilder(text)
    replacements.sortedByDescending { it.first }.forEach { (s, e, rep) ->
        if (s <= builder.length && e <= builder.length) {
            builder.replace(s, e, rep)
        }
    }
    return builder.toString()
}
