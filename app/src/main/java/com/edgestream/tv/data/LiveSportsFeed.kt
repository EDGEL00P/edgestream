package com.edgestream.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * One live sports + racing refresh for the whole app (Home, Betting, Timeline), instead of each
 * screen running its own 60 s loop. It only runs while someone is watching (per [sharing]); a
 * failed fetch keeps the previous result.
 */
class LiveSportsFeed<G, R>(
    scope: CoroutineScope,
    private val fetchGames: suspend () -> List<G>,
    private val fetchRaces: suspend () -> List<R>,
    /** Read when the feed starts (and for the first value), so a snapshot loaded later still paints at once. */
    private val initialGames: () -> List<G>,
    private val periodMs: Long = 60_000,
    private val initialDelayMs: Long = 700,
    sharing: SharingStarted = SharingStarted.WhileSubscribed(5_000),
) {
    private data class Snapshot<G, R>(val games: List<G>, val races: List<R>, val loaded: Boolean)

    private val snapshots = flow {
        var games = initialGames()
        var races = emptyList<R>()
        delay(initialDelayMs)
        while (true) {
            games = attempt(fetchGames) ?: games
            races = attempt(fetchRaces) ?: races
            emit(Snapshot(games, races, loaded = true))
            delay(periodMs)
        }
    }.shareIn(scope, sharing, replay = 1)

    val games: StateFlow<List<G>> = snapshots.map { it.games }.stateIn(scope, sharing, initialGames())
    val races: StateFlow<List<R>> = snapshots.map { it.races }.stateIn(scope, sharing, emptyList())
    val loaded: StateFlow<Boolean> = snapshots.map { it.loaded }.stateIn(scope, sharing, false)

    private suspend fun <T> attempt(fetch: suspend () -> List<T>): List<T>? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
