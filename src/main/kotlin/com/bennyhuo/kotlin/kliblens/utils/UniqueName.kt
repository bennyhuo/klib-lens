package com.bennyhuo.kotlin.kliblens.utils

import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody

fun KtDeclaration.uniqueName(): String? {
    val fqName = if (this is KtConstructor<*>) {
        "${this.getContainingClassOrObject().kotlinFqName?.asString()}.init"
    } else {
        kotlinFqName?.asString() ?: return ""
    }

    return if (this is KtDeclarationWithBody) {
        valueParameters.joinToString(",", "$fqName(", ")") { it.name ?: "" }
    } else fqName
}

fun ProtoBuf.Constructor.uniqueName(nameResolver: NameResolver): String {
    return valueParameterList.joinToString(",", "init(", ")") { nameResolver.getString(it.name) }
}

fun ProtoBuf.Property.uniqueName(nameResolver: NameResolver): String {
    return nameResolver.getString(name)
}

fun ProtoBuf.Function.uniqueName(nameResolver: NameResolver): String {
    val name = nameResolver.getString(this.name)
    return valueParameterList.joinToString(",", "$name(", ")") { nameResolver.getString(it.name) }
}