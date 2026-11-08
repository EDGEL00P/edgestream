package com.edgestream.tv.ui.shell

import com.edgestream.tv.ui.Screen
import com.edgestream.tv.ui.shell.ShellState.Rest
import com.edgestream.tv.ui.shell.ShellState.Section
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellTest {

    @Test fun focusMovesTile() =
        assertEquals(Step(Rest(Tile.SPORTS)), reduce(Rest(Tile.LIVE), ShellEvent.Focus(Tile.SPORTS)))

    @Test fun upEntersBand() =
        assertEquals(Step(Rest(Tile.SPORTS, inBand = true)), reduce(Rest(Tile.SPORTS), ShellEvent.EnterBand))

    @Test fun leaveBandReturnsToDock() =
        assertEquals(Step(Rest(Tile.SPORTS)), reduce(Rest(Tile.SPORTS, inBand = true), ShellEvent.LeaveBand))

    @Test fun openSectionTile() =
        assertEquals(Step(Section(Tile.MOVIES)), reduce(Rest(Tile.MOVIES), ShellEvent.Open(Tile.MOVIES)))

    @Test fun openPhoneIsDialog() =
        assertEquals(Step(Rest(Tile.PHONE), ShellEffect.OPEN_PHONE), reduce(Rest(Tile.PHONE), ShellEvent.Open(Tile.PHONE)))

    @Test fun backInSectionNotAtTopScrolls() = assertEquals(
        Step(Section(Tile.LIVE), ShellEffect.SCROLL_TO_TOP),
        reduce(Section(Tile.LIVE), ShellEvent.Back(contentAtTop = false, now = 10_000)),
    )

    @Test fun backInSectionAtTopRestsOnTile() =
        assertEquals(Step(Rest(Tile.LIVE)), reduce(Section(Tile.LIVE), ShellEvent.Back(contentAtTop = true, now = 10_000)))

    @Test fun backInBandReturnsToDock() =
        assertEquals(Step(Rest(Tile.SPORTS)), reduce(Rest(Tile.SPORTS, inBand = true), ShellEvent.Back(true, 10_000)))

    @Test fun backOnDockPrompts() = assertEquals(
        Step(Rest(Tile.SPORTS, exitPromptAt = 10_000), ShellEffect.SHOW_EXIT_PROMPT),
        reduce(Rest(Tile.SPORTS), ShellEvent.Back(true, 10_000)),
    )

    @Test fun secondBackWithin2sExits() =
        assertEquals(ShellEffect.EXIT, reduce(Rest(Tile.SPORTS, exitPromptAt = 9_000), ShellEvent.Back(true, 10_000)).effect)

    @Test fun backAfterWindowPromptsAgain() =
        assertEquals(ShellEffect.SHOW_EXIT_PROMPT, reduce(Rest(Tile.SPORTS, exitPromptAt = 7_000), ShellEvent.Back(true, 10_000)).effect)

    @Test fun focusClearsExitPrompt() =
        assertEquals(Step(Rest(Tile.LIVE)), reduce(Rest(Tile.SPORTS, exitPromptAt = 9_000), ShellEvent.Focus(Tile.LIVE)))

    @Test fun externalScreenOpensSection() {
        assertEquals(Step(Section(Tile.LIVE)), reduce(Rest(Tile.SPORTS), ShellEvent.External(Screen.GUIDE)))
        assertEquals(Step(Rest(Tile.LIVE)), reduce(Section(Tile.LIVE), ShellEvent.External(Screen.HOME)))
    }

    @Test fun externalHomeAtRestKeepsState() =
        assertEquals(Step(Rest(Tile.MOVIES, inBand = true)), reduce(Rest(Tile.MOVIES, inBand = true), ShellEvent.External(Screen.HOME)))

    @Test fun tileMappingsRoundTrip() {
        Tile.entries.filter { it != Tile.PHONE }.forEach { assertEquals(it, tileFor(screenFor(it)!!)) }
        assertEquals(null, screenFor(Tile.PHONE))
        assertEquals(Tile.SPORTS, tileFor(Screen.BETTING))
        assertEquals(Tile.SPORTS, tileFor(Screen.SPORTS))
        assertEquals(Tile.MOVIES, tileFor(Screen.DEBRID))
        assertEquals(Tile.SETTINGS, tileFor(Screen.SYSTEM))
        assertEquals(null, tileFor(Screen.HOME))
    }
}
