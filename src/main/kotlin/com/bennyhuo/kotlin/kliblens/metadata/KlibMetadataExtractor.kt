package com.bennyhuo.kotlin.kliblens.metadata

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.library.metadata.KlibMetadataSerializerProtocol
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.protobuf.GeneratedMessageLite

class KlibMetadataExtractor(val knmFile: VirtualFile) {
    private val LOG = Logger.getInstance(KlibMetadataExtractor::class.java)

    private val annotationMap = mutableMapOf<String, MutableMap<String, List<String>>>()

    init {
        try {
            val ext = KlibMetadataSerializerProtocol.extensionRegistry
            val packageFragment = ProtoBuf.PackageFragment.parseFrom(knmFile.contentsToByteArray(), ext)
            val nameResolver = NameResolverImpl(packageFragment.strings, packageFragment.qualifiedNames)

            processPackage(packageFragment, nameResolver)
            for (cls in packageFragment.class_List) {
                processClass(cls, nameResolver)
            }
        } catch (e: Exception) {
            LOG.info("Failed to parse KNM metadata for ${knmFile.path}", e)
        }
    }

    private fun processPackage(packageFragment: ProtoBuf.PackageFragment, nameResolver: NameResolverImpl) {
        if (!packageFragment.hasPackage()) return
        
        val pkgName = packageFragment.getExtension(KlibMetadataProtoBuf.fqName) ?: ""
        val pkg = packageFragment.`package`
        
        processDeclarations(
            pkg.propertyList,
            { nameResolver.getString(it.name) },
            nameResolver,
            pkgName,
            KlibMetadataProtoBuf.propertyAnnotation,
            KlibMetadataProtoBuf.propertyGetterAnnotation,
            KlibMetadataProtoBuf.propertySetterAnnotation
        )
        processDeclarations(
            pkg.functionList,
            { nameResolver.getString(it.name) },
            nameResolver,
            pkgName,
            KlibMetadataProtoBuf.functionAnnotation
        )
    }

    private fun processClass(cls: ProtoBuf.Class, nameResolver: NameResolverImpl) {
        val classFqName = getNormalizedFqName(cls.fqName, nameResolver)
        processDeclarations(
            cls.constructorList,
            { "init" },
            nameResolver,
            classFqName,
            KlibMetadataProtoBuf.constructorAnnotation
        )
        processDeclarations(
            cls.propertyList,
            { nameResolver.getString(it.name) },
            nameResolver,
            classFqName,
            KlibMetadataProtoBuf.propertyAnnotation,
            KlibMetadataProtoBuf.propertyGetterAnnotation,
            KlibMetadataProtoBuf.propertySetterAnnotation
        )
        processDeclarations(
            cls.functionList,
            { nameResolver.getString(it.name) },
            nameResolver,
            classFqName,
            KlibMetadataProtoBuf.functionAnnotation
        )

        val classAnns = cls.getExtension(KlibMetadataProtoBuf.classAnnotation)
        collectAnnotations(classAnns, classFqName, nameResolver)
    }
    
    private fun collectAnnotations(
        annotations: List<ProtoBuf.Annotation>,
        targetFqName: String,
        nameResolver: NameResolverImpl
    ) {
        if (annotations.isEmpty()) return
        val annArgsMap = annotationMap.getOrPut(targetFqName) { mutableMapOf() }
        for (ann in annotations) {
            val annClassFqName = getNormalizedFqName(ann.id, nameResolver)
            annArgsMap[annClassFqName] = formatAnnotationArgs(ann, nameResolver)
        }
    }

    private fun <T : GeneratedMessageLite.ExtendableMessage<T>> processDeclarations(
        declarations: List<T>,
        declarationName: (T) -> String,
        nameResolver: NameResolverImpl,
        ownerFqName: String,
        vararg annotationExtensions: GeneratedMessageLite.GeneratedExtension<T, List<ProtoBuf.Annotation>>
    ) {
        for (declaration in declarations) {
            val name = declarationName(declaration)
            val fqName = if (ownerFqName.isEmpty()) name else "$ownerFqName.$name"
            val annotations = annotationExtensions.flatMap { declaration.getExtension(it) as? List<Any> ?: emptyList() }
                .filterIsInstance<ProtoBuf.Annotation>()
           
            collectAnnotations(annotations, fqName, nameResolver)
        }
    }

    private fun formatAnnotationArgValue(
        value: ProtoBuf.Annotation.Argument.Value,
        nameResolver: NameResolverImpl
    ): String {
        return when (value.type) {
            ProtoBuf.Annotation.Argument.Value.Type.BYTE -> value.intValue.toByte().toString()
            ProtoBuf.Annotation.Argument.Value.Type.CHAR -> "'${value.intValue.toInt().toChar()}'"
            ProtoBuf.Annotation.Argument.Value.Type.SHORT -> value.intValue.toShort().toString()
            ProtoBuf.Annotation.Argument.Value.Type.INT -> value.intValue.toInt().toString()
            ProtoBuf.Annotation.Argument.Value.Type.LONG -> "${value.intValue}L"
            ProtoBuf.Annotation.Argument.Value.Type.FLOAT -> "${value.floatValue}f"
            ProtoBuf.Annotation.Argument.Value.Type.DOUBLE -> value.doubleValue.toString()
            ProtoBuf.Annotation.Argument.Value.Type.BOOLEAN -> (value.intValue != 0L).toString()
            ProtoBuf.Annotation.Argument.Value.Type.STRING -> "\"${nameResolver.getString(value.stringValue)}\""
            ProtoBuf.Annotation.Argument.Value.Type.CLASS -> formatClassValue(value, nameResolver)
            ProtoBuf.Annotation.Argument.Value.Type.ENUM -> formatEnumValue(value, nameResolver)
            ProtoBuf.Annotation.Argument.Value.Type.ANNOTATION -> formatNestedAnnotationValue(value, nameResolver)
            ProtoBuf.Annotation.Argument.Value.Type.ARRAY -> formatArrayValue(value, nameResolver)
            else -> value.toString()
        }
    }

    private fun formatClassValue(value: ProtoBuf.Annotation.Argument.Value, nameResolver: NameResolverImpl): String {
        val classFqName = getNormalizedFqName(value.classId, nameResolver)
        val arrayPrefix = "Array<".repeat(value.arrayDimensionCount)
        val arraySuffix = ">".repeat(value.arrayDimensionCount)
        return "${arrayPrefix}${classFqName}${arraySuffix}::class"
    }

    private fun formatEnumValue(value: ProtoBuf.Annotation.Argument.Value, nameResolver: NameResolverImpl): String {
        val enumClassFqName = getNormalizedFqName(value.classId, nameResolver)
        val enumEntryName = nameResolver.getString(value.enumValueId)
        return "$enumClassFqName.$enumEntryName"
    }

    private fun formatNestedAnnotationValue(value: ProtoBuf.Annotation.Argument.Value, nameResolver: NameResolverImpl): String {
        val ann = value.annotation
        val annClassFqName = getNormalizedFqName(ann.id, nameResolver)
        val args = formatAnnotationArgs(ann, nameResolver)
        return if (args.isEmpty()) annClassFqName else "$annClassFqName(${args.joinToString(", ")})"
    }

    private fun formatArrayValue(value: ProtoBuf.Annotation.Argument.Value, nameResolver: NameResolverImpl): String {
        val elements = value.arrayElementList.map { formatAnnotationArgValue(it, nameResolver) }
        return "[${elements.joinToString(", ")}]"
    }

    private fun getNormalizedFqName(id: Int, nameResolver: NameResolverImpl): String {
        return nameResolver.getQualifiedClassName(id).replace("/", ".")
    }

    private fun formatAnnotationArgs(
        ann: ProtoBuf.Annotation,
        nameResolver: NameResolverImpl
    ): List<String> {
        return ann.argumentList.map { arg ->
            val name = nameResolver.getString(arg.nameId)
            val value = formatAnnotationArgValue(arg.value, nameResolver)
            "$name = $value"
        }
    }

    fun getAnnotationArgs(declarationFqName: String, annotationFqName: String): String? {
        val entry = annotationMap[declarationFqName] ?: return null
        return entry[annotationFqName]?.joinToString(", ")
    }

    /**
     * Fallback: match annotation by simple name suffix when K2 API can't resolve the full FQN.
     * e.g., annotationSimpleName = "CStruct" matches "kotlinx.cinterop.internal.CStruct"
     */
    fun getAnnotationArgsBySimpleName(declarationFqName: String, annotationSimpleName: String): String? {
        val entry = annotationMap[declarationFqName] ?: return null
        val matchingKey = entry.keys.find { fqn ->
            fqn == annotationSimpleName || fqn.endsWith(".$annotationSimpleName")
        } ?: return null
        return entry[matchingKey]?.joinToString(", ")
    }

    /**
     * Returns all annotations stored for a given declaration.
     */
    fun getAnnotationsForDeclaration(declarationFqName: String): Map<String, List<String>>? {
        return annotationMap[declarationFqName]
    }
}

