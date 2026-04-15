package com.bennyhuo.kotlin.kliblens.utils

import com.intellij.openapi.vfs.VirtualFile

fun VirtualFile.isKnmFile(): Boolean {
    return extension == "knm" || extension == "kotlin_builtins"
}