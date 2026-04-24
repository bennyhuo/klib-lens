package com.bennyhuo.kotlin.kliblens.metadata

import com.bennyhuo.kotlin.kliblens.LOG
import com.bennyhuo.kotlin.kliblens.utils.uniqueName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrimaryConstructor

class KlibMetadataDecompiler(private val project: Project) {

    companion object {
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
        val originalPsi = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return ""
        val originalText = originalPsi.text

        // Step 1: Inject annotation values from metadata using original PSI offsets
        var text = injectAnnotationValues(originalText, originalPsi, extractor)

        // Step 2: Remove decompiler comments
        text = removeDecompilerComments(text)

        // Step 1b: Clean horizontal whitespace debris left by deleted comments
        text = text.replace(Regex("""\h+>"""), ">").replace(Regex("""\h{2,}"""), " ")

        // Step 3: Shorten FQNs and collect imports
        val (shortenedText, imports) = KlibFqnShortener.shortenFqNames(project, text, originalPsi)
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
        val declFqName = parentDeclaration?.uniqueName() ?: return null

        val annClassFqName = getAnnotationClassFqName(entry)
        if (annClassFqName != null) return extractor.getAnnotationArgs(declFqName, annClassFqName)
        val annSimpleName = entry.typeReference?.text?.replace("\\s+".toRegex(), "")
        if (annSimpleName != null) return extractor.getAnnotationArgsBySimpleName(declFqName, annSimpleName)
        return null
    }

    /**
     * Resolves the fully-qualified name of an annotation using only PSI-level information
     * (the annotation's type text and the containing file's import directives).
     *
     * This avoids calling the K2 Analysis API on temporary PSI files that have no proper
     * module context, which would trigger [IllegalStateException] in `FirNativeOverrideChecker`
     * or resolution failures in `LLFirNotUnderContentRootResolvableModuleSession`.
     */
    private fun getAnnotationClassFqName(entry: KtAnnotationEntry): String? {
        // Extract the annotation name from its type reference (e.g., "kotlin.annotation.Target" or "Target")
        val annotationName = entry.typeReference?.text?.replace("\\s+".toRegex(), "") ?: return null

        // If the annotation name already looks fully-qualified (contains a dot), return as-is
        if ('.' in annotationName) return annotationName

        // Otherwise, try to resolve via the file's import directives
        val ktFile = entry.containingKtFile
        for (importDirective in ktFile.importDirectives) {
            val importedFqName = importDirective.importedFqName?.asString() ?: continue
            if (importDirective.isAllUnder) {
                // Star import: e.g., "import kotlin.annotation.*"
                // We can't determine the exact FQN from a star import alone, skip it
                continue
            }
            val alias = importDirective.aliasName
            if (alias != null) {
                // Aliased import: e.g., "import kotlin.annotation.Target as Tgt"
                if (alias == annotationName) return importedFqName
            } else {
                // Regular import: e.g., "import kotlin.annotation.Target"
                if (importedFqName.endsWith(".$annotationName")) return importedFqName
            }
        }

        // Could not resolve to an FQN — return null so the caller falls back to simple-name matching
        return null
    }


}
