package com.edgestream.tv.ui.sports

import com.edgestream.tv.data.Sports

const val ALL_SPORTS = "All sports"
const val RACING = "Racing"

private fun rank(s: Sports.State) = when (s) { Sports.State.LIVE -> 0; Sports.State.UPCOMING -> 1; Sports.State.FINAL -> 2 }

/** Games for a chip: live first, then soonest to start, results last. */
fun gamesFor(games: List<Sports.Game>, chip: String): List<Sports.Game> =
    games.filter { chip == ALL_SPORTS || it.league.name == chip }.sortedWith(compareBy({ rank(it.state) }, { it.startMs }))

/** All sports, then leagues with live games first (busiest first), then Racing when races are on. */
fun sportChips(games: List<Sports.Game>, hasRaces: Boolean): List<String> {
    val leagues = games.groupBy { it.league.name }.entries
        .sortedWith(compareBy({ e -> if (e.value.any { it.state == Sports.State.LIVE }) 0 else 1 }, { e -> -e.value.size }, { it.key }))
        .map { it.key }
    return listOf(ALL_SPORTS) + leagues + if (hasRaces) listOf(RACING) else emptyList()
}

/** The scoreboard's default: the live game, else the next to start. */
fun featuredGame(games: List<Sports.Game>): Sports.Game? = gamesFor(games, ALL_SPORTS).firstOrNull { it.state != Sports.State.FINAL }
