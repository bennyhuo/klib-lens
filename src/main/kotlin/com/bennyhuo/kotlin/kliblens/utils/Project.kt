package com.bennyhuo.kotlin.kliblens.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

inline fun Project.invokeLater(crossinline block: () -> Unit) {
    ApplicationManager.getApplication().invokeLater({
        if (isDisposed) return@invokeLater
        block()
    }, ModalityState.defaultModalityState())
}