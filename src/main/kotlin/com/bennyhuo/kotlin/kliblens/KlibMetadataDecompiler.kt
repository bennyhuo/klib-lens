package com.bennyhuo.kotlin.kliblens

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaAnnotatedSymbol
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.psi.*

private val LOG = Logger.getInstance(KlibMetadataDecompiler::class.java)

class KlibMetadataDecompiler(private val project: Project) {

    companion object {
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

        private val TYPE_ALIASES = mapOf(
            "CPointer<out CPointed>" to "COpaquePointer",
            "ByteVarOf<Byte>" to "ByteVar",
            "ShortVarOf<Short>" to "ShortVar",
            "IntVarOf<Int>" to "IntVar",
            "LongVarOf<Long>" to "LongVar",
            "FloatVarOf<Float>" to "FloatVar",
            "DoubleVarOf<Double>" to "DoubleVar",
            "BooleanVarOf<Boolean>" to "BooleanVar",
            "UByteVarOf<UByte>" to "UByteVar",
            "UShortVarOf<UShort>" to "UShortVar",
            "UIntVarOf<UInt>" to "UIntVar",
            "ULongVarOf<ULong>" to "ULongVar"
        )
    }

    fun decompile(file: VirtualFile, extractor: KlibMetadataExtractor): String {
        val originalPsi = PsiManager.getInstance(project).findFile(file)
        val originalText = originalPsi?.text ?: ""

        // Step 1: Inject annotation values from metadata using original PSI offsets
        var text = injectAnnotationValues(originalText, originalPsi as? KtFile, extractor)

        // Step 2: Remove decompiler comments
        text = removeDecompilerComments(text)

        // Step 1b: Clean horizontal whitespace debris left by deleted comments
        text = text.replace(Regex("""\h+>"""), ">").replace(Regex("""\h{2,}"""), " ")

        // Step 3: Shorten FQNs and collect imports
        val (shortenedText, imports) = shortenFQNs(text)
        val finalImports = imports.toMutableSet()
        text = shortenedText

        // Step 4: Apply CInterop type aliases
        var changed = false
        val cPointerVarRegex = Regex("""CPointerVarOf<CPointer<(.*?)>>""")
        if (cPointerVarRegex.containsMatchIn(text)) {
            text = text.replace(cPointerVarRegex, "CPointerVar<$1>")
            finalImports.add("kotlinx.cinterop.CPointerVar")
            changed = true
        }

        TYPE_ALIASES.forEach { (target, replacement) ->
            if (text.contains(target)) {
                text = text.replace(target, replacement)
                finalImports.add("kotlinx.cinterop.$replacement")
                changed = true
            }
        }

        // Step 5: Insert imports and clean empty accessors & blocks
        text = insertImports(text, if (changed) finalImports else imports)
        text = text.replace(Regex("""get\(\)\s*\{\s*\}"""), "get")
        text = text.replace(Regex("""set\((.*?)\)\s*\{\s*\}"""), "set")
        text = text.replace(Regex("""\{\s*\}"""), "") // Remove empty function or class bodies

        // Step 6: Remove redundant 'public' and 'final' modifiers (Kotlin defaults)
        text = removeRedundantModifiers(text)

        // Step 7: Remove redundant 'Unit' return types
        text = removeRedundantUnitReturnType(text)

        text = text.lines().joinToString("\n") { it.trimEnd() }
        text = text.replace(Regex("""\n{3,}"""), "\n\n")

        return text
    }

    /**
     * Remove redundant `public` and `final` modifiers from decompiled Kotlin code.
     * In Kotlin, `public` is the default visibility and `final` is the default inheritance modifier,
     * so they are redundant. Also removes `public constructor` when it's the only modifier on a
     * primary constructor, collapsing it to just `(params)`.
     */
    private fun removeRedundantModifiers(text: String): String {
        val ktFile = PsiFileFactory.getInstance(project)
            .createFileFromText("temp.kt", KotlinLanguage.INSTANCE, text) as? KtFile ?: return text

        val replacements = mutableListOf<Triple<Int, Int, String>>()

        // Process primary constructors: remove "public constructor" leaving just "(params)"
        PsiTreeUtil.collectElementsOfType(ktFile, KtPrimaryConstructor::class.java).forEach { ctor ->
            val modifierList = ctor.modifierList
            // Only remove if the constructor has no modifiers other than "public",
            // and no annotations attached to it
            val hasOnlyPublic = modifierList != null
                && modifierList.annotationEntries.isEmpty()
                && modifierList.node.getChildren(null).mapNotNull { it.text.trim().takeIf { t -> t.isNotEmpty() } } == listOf("public")
            if (hasOnlyPublic) {
                // Remove from the space before "public" through "constructor"
                val ctorKeyword = ctor.getConstructorKeyword() ?: return@forEach
                // The range to remove: everything from constructor start (which includes modifiers) to just after "constructor" keyword
                val removeStart = modifierList.textRange.startOffset
                val removeEnd = ctorKeyword.textRange.endOffset
                // Check if there's a space before the modifier list (part of class declaration)
                val spaceStart = if (removeStart > 0 && text[removeStart - 1] == ' ') removeStart - 1 else removeStart
                replacements += Triple(spaceStart, removeEnd, "")
            }
        }

        // Process all modifier lists: remove "public" and "final" keywords and canonicalize order
        PsiTreeUtil.collectElementsOfType(ktFile, KtModifierList::class.java).forEach { modList ->
            // Skip modifier lists inside primary constructors already handled above
            if (PsiTreeUtil.getParentOfType(modList, KtPrimaryConstructor::class.java) != null) return@forEach

            val annotations = modList.annotationEntries.map { it.text }
            val modifiers = modList.node.getChildren(null).mapNotNull { node ->
                val txt = node.text.trim()
                if (txt.isEmpty() || node.psi is KtAnnotationEntry) null
                else if (txt == "public" || txt == "final") null
                else txt
            }

            val canonical = (annotations + modifiers).joinToString(" ")
            if (canonical != modList.text.trim()) {
                val start = modList.textRange.startOffset
                var end = modList.textRange.endOffset
                
                // If the canonical text is empty, we might want to consume one trailing space to avoid double spacing
                val replacement = if (canonical.isEmpty()) "" else "$canonical "
                if (canonical.isEmpty() && end < text.length && text[end] == ' ') {
                    end++
                }
                
                replacements += Triple(start, end, replacement)
            }
        }

        return applyReplacements(text, replacements)
    }

    /**
     * Remove redundant explicit `Unit` return types from functions.
     * `fun setX(x: Int): Unit` becomes `fun setX(x: Int)`.
     */
    private fun removeRedundantUnitReturnType(text: String): String {
        val ktFile = PsiFileFactory.getInstance(project)
            .createFileFromText("temp.kt", KotlinLanguage.INSTANCE, text) as? KtFile ?: return text

        val replacements = mutableListOf<Triple<Int, Int, String>>()

        PsiTreeUtil.collectElementsOfType(ktFile, KtNamedFunction::class.java).forEach { func ->
            val typeRef = func.typeReference
            if (typeRef != null && (typeRef.text == "Unit" || typeRef.text == "kotlin.Unit")) {
                val colon = func.colon
                if (colon != null) {
                    var start = colon.textRange.startOffset
                    val end = typeRef.textRange.endOffset
                    // Remove preceding spaces before colon
                    while (start > 0 && text[start - 1] == ' ') {
                        start--
                    }
                    replacements += Triple(start, end, "")
                }
            }
        }
        return applyReplacements(text, replacements)
    }

    private fun removeDecompilerComments(text: String): String {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText("temp.kt", KotlinLanguage.INSTANCE, text)
        val replacements = PsiTreeUtil.collectElementsOfType(psiFile, PsiComment::class.java).filter { comment ->
            val raw = comment.text
            if (!raw.startsWith("/*")) return@filter false
            val body = raw.removePrefix("/*").removeSuffix("*/").trim()
            body.startsWith("from:") || body == "compiled code"
        }.map { Triple(it.textRange.startOffset, it.textRange.endOffset, "") }

        return applyReplacements(text, replacements)
    }

    private fun shortenFQNs(text: String): Pair<String, Set<String>> {
        val ktFile = PsiFileFactory.getInstance(project).createFileFromText("temp.kt", KotlinLanguage.INSTANCE, text) as? KtFile
            ?: return text to emptySet()

        val imports = mutableSetOf<String>()
        val replacements = mutableListOf<Triple<Int, Int, String>>()

        PsiTreeUtil.collectElementsOfType(ktFile, KtUserType::class.java)
            .filter { it.qualifier != null && it.parent !is KtUserType }
            .forEach { outerType ->
                val fqn = buildFQN(outerType).takeIf { '.' in it } ?: return@forEach
                val info = resolveFqNameInfo(fqn)

                if (!isDefaultImport(info.importPath)) imports += info.importPath
                replacements += Triple(
                    outerType.textRange.startOffset,
                    outerType.referenceExpression?.textRange?.endOffset ?: outerType.textRange.endOffset,
                    info.simpleName
                )
            }

        PsiTreeUtil.collectElementsOfType(ktFile, KtDotQualifiedExpression::class.java)
            .filter { it.parent !is KtDotQualifiedExpression }
            .forEach { dotExpr ->
                val fqn = dotExpr.text.replace(Regex("\\s+"), "").substringBefore('(')
                if ('.' !in fqn || !fqn.matches(Regex("""[A-Za-z_][A-Za-z0-9_.]*"""))) return@forEach

                val info = resolveFqNameInfo(fqn)
                if (!isDefaultImport(info.importPath)) imports += info.importPath
                val qualifierEnd = dotExpr.text.indexOf(info.simpleName)
                if (qualifierEnd > 0) {
                    replacements += Triple(dotExpr.textRange.startOffset, dotExpr.textRange.startOffset + qualifierEnd, "")
                }
            }

        return applyReplacements(text, replacements) to imports
    }

    private fun resolveFqNameInfo(fqn: String): FqNameInfo {
        val parts = fqn.split('.')
        val firstUpper = parts.indexOfFirst { it.first().isUpperCase() }
        return if (firstUpper >= 0) {
            FqNameInfo(parts.drop(firstUpper).joinToString("."), parts.take(firstUpper + 1).joinToString("."))
        } else {
            FqNameInfo(parts.last(), fqn)
        }
    }

    private fun applyReplacements(text: String, replacements: List<Triple<Int, Int, String>>): String {
        var result = text
        replacements.sortedByDescending { it.first }.forEach { (s, e, rep) ->
            if (s <= result.length && e <= result.length) {
                result = result.substring(0, s) + rep + result.substring(e)
            }
        }
        return result
    }

    private fun buildFQN(userType: KtUserType): String {
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

    private fun insertImports(text: String, imports: Set<String>): String {
        if (imports.isEmpty()) return text
        val header = imports.sorted().joinToString("\n") { "import $it" }
        val pkg = Regex("""^package\s+.*$""", RegexOption.MULTILINE).find(text)
        return if (pkg != null) {
            val i = pkg.range.last + 1
            text.substring(0, i) + "\n\n" + header + text.substring(i)
        } else "$header\n\n$text"
    }

    private fun injectAnnotationValues(text: String, originalPsi: KtFile?, extractor: KlibMetadataExtractor): String {
        if (originalPsi == null) return text
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        PsiTreeUtil.collectElementsOfType(originalPsi, KtAnnotationEntry::class.java).forEach { entry ->
            try {
                val argumentsText = getArguments(entry, extractor)
                if (!argumentsText.isNullOrBlank() && entry.valueArgumentList == null) {
                    val endOffset = entry.textRange.endOffset
                    replacements += Triple(endOffset, endOffset, "($argumentsText)")
                }
            } catch (e: Exception) {
                LOG.error("[KLIB-LENS] Error processing entry ${entry.text}", e)
            }
        }

        return applyReplacements(text, replacements)
    }

    private fun getArguments(entry: KtAnnotationEntry, extractor: KlibMetadataExtractor): String? {
        val parentDeclaration = PsiTreeUtil.getParentOfType(entry, KtDeclaration::class.java)
        val declFqName = if (parentDeclaration is KtConstructor<*>) {
            "${parentDeclaration.getContainingClassOrObject().fqName}.init"
        } else {
            parentDeclaration?.kotlinFqName?.asString() ?: return null
        }
        val annClassFqName = getAnnotationClassFqName(entry)
        if (annClassFqName != null) return extractor.getAnnotationArgs(declFqName, annClassFqName)
        val annSimpleName = entry.typeReference?.text?.replace("\\s+".toRegex(), "")
        if (annSimpleName != null) return extractor.getAnnotationArgsBySimpleName(declFqName, annSimpleName)
        return null
    }

    @OptIn(KaAllowAnalysisOnEdt::class)
    private fun getAnnotationClassFqName(entry: KtAnnotationEntry): String? {
        val parentDeclaration = PsiTreeUtil.getParentOfType(entry, KtDeclaration::class.java) ?: return null
        return try {
            allowAnalysisOnEdt {
                analyze(parentDeclaration) {
                    val symbol = parentDeclaration.symbol as? KaAnnotatedSymbol ?: return@analyze null
                    val annotation = symbol.annotations.find { it.psi == entry }
                    annotation?.classId?.asSingleFqName()?.asString()
                }
            }
        } catch (e: Exception) {
            LOG.warn("[KLIB-LENS] K2 API failed to resolve annotation: ${entry.text}", e)
            null
        }
    }

    private data class FqNameInfo(val simpleName: String, val importPath: String)

}
