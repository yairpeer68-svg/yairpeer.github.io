package com.sherlock.app.ui.nav

import androidx.compose.runtime.mutableStateListOf

sealed interface Screen {
    data object Home : Screen
    data class CaseDetail(val caseId: Long) : Screen
}

/** Minimal dependency-free navigation back stack. */
class Navigator {
    private val stack = mutableStateListOf<Screen>(Screen.Home)

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) { stack.add(screen) }

    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
