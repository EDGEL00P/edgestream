package com.edgestream.tv.ui.components

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/** Waits [gapMs] before each of up to [tries] attempts; stops at the first that succeeds. */
suspend fun retryUntil(tries: Int, gapMs: Long, attempt: () -> Boolean): Boolean {
    repeat(tries) {
        delay(gapMs)
        if (attempt()) return true
    }
    return false
}

/**
 * Requests focus until it actually lands. `requestFocus()` returns false (it doesn't throw) while
 * the target isn't attached yet, e.g. while a screen's data is still loading.
 */
suspend fun FocusRequester.requestFocusWithRetry(tries: Int = 12, gapMs: Long = 80): Boolean =
    retryUntil(tries, gapMs) { runCatching { requestFocus() }.getOrDefault(false) }
