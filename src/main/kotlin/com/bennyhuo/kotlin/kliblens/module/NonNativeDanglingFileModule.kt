package com.bennyhuo.kotlin.kliblens.module

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.platform.SimplePlatform
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtFile

/**
 * A wrapper around [KaDanglingFileModule] that overrides [targetPlatform] to avoid
 * being a "pure Native" platform. This prevents [LLFirSessionCache.createPlatformAwareSessionFactory]
 * from selecting [LLFirNativeSessionFactory], which would register [FirNativeOverrideChecker] —
 * a checker that crashes on deserialized klib annotations with missing arguments.
 *
 * By adding a synthetic non-Native element to the platform set, the factory selector
 * falls through to [LLFirCommonSessionFactory], which uses [FirStandardOverrideChecker] and
 * works correctly with deserialized library symbols.
 *
 * This technique mirrors Kotlin's own [KaFirCompilerFacility.createJvmDanglingFileModule()].
 */
@OptIn(KaPlatformInterface::class)
class NonNativeDanglingFileModule(
    private val delegate: KaDanglingFileModule
) : KaDanglingFileModule by delegate {
    override val targetPlatform: TargetPlatform
        get() = TargetPlatform(
            delegate.targetPlatform.componentPlatforms + SyntheticCommonPlatform
        )

    /**
     * A synthetic platform that is not JVM, JS, Wasm, or Native.
     * Adding this to a TargetPlatform ensures that `all { it is NativePlatform }` returns false,
     * forcing the session factory to use [LLFirCommonSessionFactory].
     */
    private object SyntheticCommonPlatform : SimplePlatform("SyntheticCommon") {
        override val oldFashionedDescription: String get() = "SyntheticCommon"
        override val targetName: String get() = "syntheticCommon"
    }

    @Deprecated(
        "Use 'files' instead.",
        replaceWith = ReplaceWith("files.single()", "kotlin.collections.single")
    )
    override val file: KtFile
        get() = delegate.files.single()
}