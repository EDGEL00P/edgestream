package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Team follows for the Sports Timeline: persisted, keyed by league + abbreviation. */
object SportsFollows {
    private const val PREFS = "edgestream"
    private const val KEY = "sportsFollows"
    private var prefs: android.content.SharedPreferences? = null

    private val _state = MutableStateFlow<Set<String>>(emptySet())
    val state: StateFlow<Set<String>> = _state

    private fun key() = ProfileCodec.scoped(KEY, Profiles.active.value)

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        reload()
    }

    /** Re-reads the active profile's follows (after a profile switch). */
    fun reload() { _state.value = prefs?.getStringSet(key(), emptySet()).orEmpty() }

    fun key(league: Sports.League, team: Sports.Team): String =
        "${league.id}:${team.abbr.ifBlank { team.name }.uppercase()}"

    fun isFollowed(league: Sports.League, team: Sports.Team): Boolean =
        key(league, team) in _state.value

    fun gameFollowed(game: Sports.Game): Boolean =
        isFollowed(game.league, game.home) || isFollowed(game.league, game.away)

    fun toggle(league: Sports.League, team: Sports.Team) {
        val k = key(league, team)
        val next = if (k in _state.value) _state.value - k else _state.value + k
        _state.value = next
        prefs?.edit()?.putStringSet(key(), next)?.apply()
    }
}
