package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Always watch this on…": a channel the viewer chose for one game, or for every game of a team.
 * A game pin beats a team pin; the home team's pin beats the away team's. Stored as
 * "game:<id>" / "team:<league>:<ABBR>" → channel key.
 */
object ChannelPins {
    private const val PREFS = "edgestream"
    private const val KEY = "channelPins"
    private var prefs: android.content.SharedPreferences? = null

    private val _state = MutableStateFlow<Map<String, String>>(emptyMap())
    val state: StateFlow<Map<String, String>> = _state

    private fun key() = ProfileCodec.scoped(KEY, Profiles.active.value)

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        reload()
    }

    /** Re-reads the active profile's pins (after a profile switch). */
    fun reload() {
        _state.value = prefs?.getStringSet(key(), emptySet()).orEmpty()
            .mapNotNull { e -> e.split('\u0001').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    fun gameKey(g: Sports.Game) = "game:${g.id}"
    fun teamKey(league: Sports.League, team: Sports.Team) = "team:${SportsFollows.key(league, team)}"

    /** The pinned channel key for [g], if any. */
    fun pinFor(g: Sports.Game, pins: Map<String, String>): String? =
        pins[gameKey(g)] ?: pins[teamKey(g.league, g.home)] ?: pins[teamKey(g.league, g.away)]

    fun set(key: String, channelKey: String?) {
        val next = if (channelKey == null) _state.value - key else _state.value + (key to channelKey)
        _state.value = next
        prefs?.edit()?.putStringSet(key(), next.map { (k, v) -> "$k\u0001$v" }.toSet())?.apply()
    }
}
