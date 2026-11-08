package com.edgestream.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEntryTest {

    @Test fun firstRun_digitsNotCaught() {
        assertFalse(digitsGoToPad(fullscreen = false, screen = Screen.HOME, hasSources = false, textFocused = false))
    }

    @Test fun textFieldFocused_digitsNotCaught() {
        assertFalse(digitsGoToPad(fullscreen = false, screen = Screen.LIVE, hasSources = true, textFocused = true))
    }

    @Test fun settingsAndSearch_stillExcluded() {
        assertFalse(digitsGoToPad(false, Screen.SETTINGS, hasSources = true, textFocused = false))
        assertFalse(digitsGoToPad(false, Screen.SEARCH, hasSources = true, textFocused = false))
    }

    @Test fun liveTv_digitsCaught() {
        assertTrue(digitsGoToPad(false, Screen.LIVE, hasSources = true, textFocused = false))
        assertFalse(digitsGoToPad(true, Screen.LIVE, hasSources = true, textFocused = false))
    }
}
