package com.bennyhuo.kotlin.kliblens

import com.bennyhuo.kotlin.kliblens.file.KnmFile
import com.bennyhuo.kotlin.kliblens.utils.isKnmFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

val LOG = Logger.getInstance(KlibViewerEditorProvider::class.java)

class KlibViewerEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.isKnmFile()
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val knmFile = KnmFile(project, file)
        val editor = TextEditorProvider.getInstance().createEditor(project, knmFile.newFile) as TextEditor
        return KlibViewerTextEditorWrapper(editor, knmFile)
    }

    override fun getEditorTypeId(): String = "klib-lens-editor"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}