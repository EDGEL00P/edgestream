package com.edgestream.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Second Back on Home's dock within this window exits the app. */
const val EXIT_WINDOW_MS = 2_000L

/** Whether anything in the main window has focus; screens read it before claiming focus late. */
class RootFocus {
    var hasFocus by androidx.compose.runtime.mutableStateOf(true)
}

val LocalRootFocus = staticCompositionLocalOf { RootFocus() }

/** Ask the shell to put focus on the dock if nothing has it (e.g. Home's hero just disappeared). */
val LocalFocusRescue = staticCompositionLocalOf<() -> Unit> { {} }

/** Nothing focused on the browse UI (no video, no overlay window): the remote would be dead. */
fun shouldRescueFocus(rootHasFocus: Boolean, fullscreen: Boolean, overlayOpen: Boolean): Boolean =
    !rootHasFocus && !fullscreen && !overlayOpen

/**
 * The active root screen registers how to get back to its top. A screen that never registers
 * counts as already at the top, so Back goes straight to the dock.
 */
class ScrollToTopRegistry {
    private var handler: (suspend () -> Unit)? = null
    private var atTopProbe: (() -> Boolean)? = null

    val atTop: Boolean get() = atTopProbe?.invoke() ?: true

    fun register(atTop: () -> Boolean, scrollToTop: suspend () -> Unit) {
        atTopProbe = atTop
        handler = scrollToTop
    }

    fun unregister(scrollToTop: suspend () -> Unit) {
        if (handler === scrollToTop) { handler = null; atTopProbe = null }
    }

    suspend fun run() { handler?.invoke() }
}

val LocalScrollToTop = staticCompositionLocalOf { ScrollToTopRegistry() }
