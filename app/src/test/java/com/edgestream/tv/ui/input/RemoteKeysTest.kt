package com.edgestream.tv.ui.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    private fun all(vararg codes: Int) = codes.map { RemoteKeys.actionFor(it) }.toSet()

    @Test fun okSynonyms() = assertEquals(
        setOf(RemoteAction.OK),
        all(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A),
    )

    @Test fun backSynonyms() = assertEquals(
        setOf(RemoteAction.BACK),
        all(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B),
    )

    @Test fun arrows() {
        assertEquals(RemoteAction.UP, RemoteKeys.actionFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteAction.DOWN, RemoteKeys.actionFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(RemoteAction.LEFT, RemoteKeys.actionFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteAction.RIGHT, RemoteKeys.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test fun pageKeys() {
        assertEquals(setOf(RemoteAction.PAGE_UP), all(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(setOf(RemoteAction.PAGE_DOWN), all(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test fun mediaKeys() = assertEquals(
        setOf(RemoteAction.PLAY_PAUSE),
        all(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE),
    )

    @Test fun shortcuts() {
        assertEquals(RemoteAction.MENU, RemoteKeys.actionFor(KeyEvent.KEYCODE_MENU))
        assertEquals(RemoteAction.INFO, RemoteKeys.actionFor(KeyEvent.KEYCODE_INFO))
        assertEquals(RemoteAction.GUIDE, RemoteKeys.actionFor(KeyEvent.KEYCODE_GUIDE))
    }

    @Test fun digits() {
        for (d in 0..9) {
            assertEquals(RemoteAction.DIGIT, RemoteKeys.actionFor(KeyEvent.KEYCODE_0 + d))
            assertEquals(RemoteAction.DIGIT, RemoteKeys.actionFor(KeyEvent.KEYCODE_NUMPAD_0 + d))
            assertEquals(d, RemoteKeys.digitFor(KeyEvent.KEYCODE_0 + d))
            assertEquals(d, RemoteKeys.digitFor(KeyEvent.KEYCODE_NUMPAD_0 + d))
        }
        assertNull(RemoteKeys.digitFor(KeyEvent.KEYCODE_ENTER))
    }

    @Test fun everyOkSynonymPressesACard() {
        val ok = listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A,
        )
        for (code in ok) assertEquals(RemoteAction.OK, RemoteKeys.actionFor(code))
        // Compose's clickable owns the first four; the gamepad key is the one our card handler owns.
        for (code in ok.dropLast(1)) assertFalse(needsCardOkSynonym(code))
        assertTrue(needsCardOkSynonym(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(needsCardOkSynonym(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun unknownIsNull() = assertNull(RemoteKeys.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
}
