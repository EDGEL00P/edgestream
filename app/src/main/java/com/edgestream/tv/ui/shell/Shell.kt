package com.edgestream.tv.ui.shell

import com.edgestream.tv.ui.EXIT_WINDOW_MS
import com.edgestream.tv.ui.Screen

/** Everything the dock offers: four utility icons, then the five sections. */
enum class Tile(val label: String, val utility: Boolean) {
    SEARCH("Search", true), SETTINGS("Settings", true), PHONE("Remote", true), APPS("Apps", true),
    LIVE("Live TV", false), SPORTS("Sports", false), ODDS("Odds", false), MOVIES("On Demand", false), WEATHER("Weather", false), CALENDAR("Calendar", false),
}

val DEFAULT_TILE = Tile.SPORTS

/** The one surface: at rest (Home: dock + band preview) or a tile expanded into its section. */
sealed interface ShellState {
    data class Rest(val tile: Tile, val inBand: Boolean = false, val exitPromptAt: Long? = null) : ShellState
    data class Section(val tile: Tile) : ShellState
}

sealed interface ShellEvent {
    data class Focus(val tile: Tile) : ShellEvent
    data object EnterBand : ShellEvent
    data object LeaveBand : ShellEvent
    data class Open(val tile: Tile) : ShellEvent
    /** Back on the shell; [contentAtTop] comes from the hosted section's scroll-to-top registry. */
    data class Back(val contentAtTop: Boolean, val now: Long) : ShellEvent
    /** Something outside the shell changed the screen (phone remote, deep link, a screen's own link). */
    data class External(val screen: Screen) : ShellEvent
}

enum class ShellEffect { SCROLL_TO_TOP, SHOW_EXIT_PROMPT, EXIT, OPEN_PHONE }

data class Step(val state: ShellState, val effect: ShellEffect? = null)

/** Up-a-level navigation: section → top → its dock tile → "press again to exit". Never history. */
fun reduce(s: ShellState, e: ShellEvent): Step = when (e) {
    is ShellEvent.Focus -> Step(ShellState.Rest(e.tile))
    ShellEvent.EnterBand -> if (s is ShellState.Rest) Step(s.copy(inBand = true, exitPromptAt = null)) else Step(s)
    ShellEvent.LeaveBand -> if (s is ShellState.Rest) Step(s.copy(inBand = false)) else Step(s)
    is ShellEvent.Open ->
        if (screenFor(e.tile) == null) Step(ShellState.Rest(e.tile), ShellEffect.OPEN_PHONE)
        else Step(ShellState.Section(e.tile))
    is ShellEvent.Back -> when (s) {
        is ShellState.Section ->
            if (!e.contentAtTop) Step(s, ShellEffect.SCROLL_TO_TOP) else Step(ShellState.Rest(s.tile))
        is ShellState.Rest -> when {
            s.inBand -> Step(s.copy(inBand = false))
            s.exitPromptAt != null && e.now >= s.exitPromptAt && e.now - s.exitPromptAt <= EXIT_WINDOW_MS ->
                Step(s, ShellEffect.EXIT)
            else -> Step(s.copy(exitPromptAt = e.now), ShellEffect.SHOW_EXIT_PROMPT)
        }
    }
    is ShellEvent.External -> {
        val tile = tileFor(e.screen)
        when {
            tile != null -> Step(ShellState.Section(tile))
            s is ShellState.Section -> Step(ShellState.Rest(s.tile))
            else -> Step(s)
        }
    }
}

/** Which existing screen a tile hosts. Phone is a dialog, not a screen. */
fun screenFor(t: Tile): Screen? = when (t) {
    Tile.LIVE -> Screen.LIVE
    Tile.SPORTS -> Screen.TIMELINE
    Tile.ODDS -> Screen.BETTING
    Tile.MOVIES -> Screen.MOVIES
    Tile.WEATHER -> Screen.RADAR
    Tile.CALENDAR -> Screen.CALENDAR
    Tile.SEARCH -> Screen.SEARCH
    Tile.SETTINGS -> Screen.SETTINGS
    Tile.APPS -> Screen.APPS
    Tile.PHONE -> null
}

/** Which tile owns a screen; the retired destinations fold into their parent section. */
fun tileFor(s: Screen): Tile? = when (s) {
    Screen.HOME -> null
    Screen.LIVE, Screen.GUIDE -> Tile.LIVE
    Screen.TIMELINE, Screen.SPORTS -> Tile.SPORTS
    Screen.BETTING -> Tile.ODDS
    Screen.MOVIES, Screen.DEBRID -> Tile.MOVIES
    Screen.SERIES -> Tile.MOVIES
    Screen.RADAR -> Tile.WEATHER
    Screen.CALENDAR -> Tile.CALENDAR
    Screen.SEARCH -> Tile.SEARCH
    Screen.SETTINGS, Screen.SYSTEM -> Tile.SETTINGS
    Screen.APPS -> Tile.APPS
}
