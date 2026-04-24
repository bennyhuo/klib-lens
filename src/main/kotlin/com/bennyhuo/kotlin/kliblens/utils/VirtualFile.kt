package com.bennyhuo.kotlin.kliblens.utils

import com.bennyhuo.kotlin.kliblens.file.KnmFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

fun VirtualFile.isKnmFile(): Boolean {
    return extension == "knm" || extension == "kotlin_builtins"
}

private val KNM_FILE_KEY = Key.create<KnmFile>("KlibLens.KnmFile")

var Editor.knmFile: KnmFile?
    get() = getUserData<KnmFile>(KNM_FILE_KEY)
    set(value) {
        putUserData(KNM_FILE_KEY, value)
    }