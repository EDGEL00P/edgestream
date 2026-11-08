package com.edgestream.tv.ui.input

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** What a button means, whichever remote, keyboard or controller sent it. */
enum class RemoteAction { OK, BACK, UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN, PLAY_PAUSE, MENU, INFO, GUIDE, DIGIT }

/**
 * The one table that maps raw key codes to actions. The SHIELD remote needs only arrows, OK and
 * Back; keyboards (Enter/Space/Escape), game controllers (A/B) and universal remotes (channel,
 * page, number keys) land on the same actions so nothing depends on one kind of remote.
 */
object RemoteKeys {
    fun actionFor(keyCode: Int): RemoteAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> RemoteAction.OK
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> RemoteAction.BACK
        KeyEvent.KEYCODE_DPAD_UP -> RemoteAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RemoteAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RemoteAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteAction.RIGHT
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> RemoteAction.PAGE_UP
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> RemoteAction.PAGE_DOWN
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MENU -> RemoteAction.MENU
        KeyEvent.KEYCODE_INFO -> RemoteAction.INFO
        KeyEvent.KEYCODE_GUIDE -> RemoteAction.GUIDE
        else -> if (digitFor(keyCode) != null) RemoteAction.DIGIT else null
    }

    fun digitFor(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

/**
 * Click on any OK-type key (Centre, Enter, NumPad Enter, Space, gamepad A) and on a pointer click.
 * Consumes both halves of an OK press so nothing else acts on it; fires on key-up.
 * Also makes the node focusable, in touch mode too.
 */
fun Modifier.remoteClickable(onClick: () -> Unit): Modifier = this
    .onPreviewKeyEvent { e ->
        if (RemoteKeys.actionFor(e.nativeKeyEvent.keyCode) != RemoteAction.OK) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyUp) onClick()
        true
    }
    // Not `clickable`: in touch mode (after any pointer tap) clickable refuses focus, which
    // would leave the remote dead. Always-focusable + a tap detector keeps both working.
    .focusable()
    .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }

/**
 * Air-mouse / pointer support: hovering a focusable moves focus to it, so a click (which the
 * focusable's own clickable handles) acts on what the highlight shows. Put before `focusable()`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.hoverFocus(requester: FocusRequester): Modifier = this
    .focusRequester(requester)
    .pointerInput(requester) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent().type == PointerEventType.Enter) requester.requestFocus()
            }
        }
    }
