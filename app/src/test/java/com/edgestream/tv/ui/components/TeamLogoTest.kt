package com.edgestream.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamLogoTest {
    @Test fun espnLogosHaveADarkVersion() =
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500-dark/no.png", darkLogo("https://a.espncdn.com/i/teamlogos/nba/500/no.png"))

    @Test fun otherLogosAreLeftAlone() = assertNull(darkLogo("https://example.org/logo.png"))
}
