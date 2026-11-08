package com.edgestream.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.edgestream.tv.remote.PhoneLink
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/** Whether a text field currently has focus, so typed digits go to it and not to channel entry. */
class TextEntryState {
    var focused by mutableStateOf(false)
    /** Replaces the focused field's value; set while a field is offered to the phone. */
    internal var fill: ((String) -> Unit)? = null
}

/**
 * Call from every text field. While it is [focused], a paired phone sees its label (and value,
 * unless [secret]) and can replace the whole value; see PhoneLink.field.
 */
@Composable
fun PhoneFillable(focused: Boolean, label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    if (!focused) return
    val state = LocalTextEntry.current
    val latest by rememberUpdatedState(onChange)
    val owner = remember { Any() }
    LaunchedEffect(label, value, secret) { PhoneLink.offerField(owner, PhoneLink.Field(label, if (secret) "" else value, secret)) }
    DisposableEffect(owner) {
        val fill: (String) -> Unit = { latest(it) }
        state.fill = fill
        onDispose {
            if (state.fill === fill) state.fill = null
            PhoneLink.withdrawField(owner)
        }
    }
}

val LocalTextEntry = staticCompositionLocalOf { TextEntryState() }

/** Put on every text field: reports its focus to [state]. */
fun Modifier.reportsTextEntry(state: TextEntryState): Modifier =
    onFocusChanged { state.focused = it.isFocused }

/**
 * Whether the shell's key handler owns the number keys (channel entry, controlled key repeat).
 * Not on full-screen video, the first-run screen, Settings/Search, or while typing in a field.
 */
fun digitsGoToPad(fullscreen: Boolean, screen: Screen, hasSources: Boolean, textFocused: Boolean): Boolean =
    !fullscreen && hasSources && !textFocused && screen != Screen.SETTINGS && screen != Screen.SEARCH
