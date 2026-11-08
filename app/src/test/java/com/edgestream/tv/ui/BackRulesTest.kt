package com.edgestream.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackRulesTest {
    @Test fun focusRescue_onlyWhenNothingFocusedOnBrowseUi() {
        assertEquals(true, shouldRescueFocus(rootHasFocus = false, fullscreen = false, overlayOpen = false))
        assertEquals(false, shouldRescueFocus(rootHasFocus = true, fullscreen = false, overlayOpen = false))
        assertEquals(false, shouldRescueFocus(rootHasFocus = false, fullscreen = true, overlayOpen = false))
        assertEquals(false, shouldRescueFocus(rootHasFocus = false, fullscreen = false, overlayOpen = true))
    }
}
