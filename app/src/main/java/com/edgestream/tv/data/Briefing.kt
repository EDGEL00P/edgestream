package com.edgestream.tv.data

/**
 * The one-line briefing across the top of Home: what is live and what is next, written from the
 * app's own live data. Weather lives under the clock and warnings in their alert, so neither is
 * repeated here. [Part.key] marks the words to light up.
 */
object Briefing {
    data class Part(val text: String, val key: Boolean = false)

    /** League order for "the" live game to mention: the big North American leagues first. */
    private val PRIORITY = listOf("nfl", "nba", "nhl", "mlb", "ncaaf", "epl", "nrl", "afl")

    fun compose(
        games: List<Sports.Game>,
        picks: List<NflPicks.Pick>,
        timeOf: (Long) -> String,
        now: Long,
    ): List<Part> {
        val out = ArrayList<Part>()
        fun sentence(vararg parts: Part) {
            if (out.isNotEmpty()) out += Part("  ·  ")
            out += parts
        }
        val live = games.filter { it.state == Sports.State.LIVE }
        if (live.isNotEmpty()) {
            val lead = live.minBy { g -> PRIORITY.indexOf(g.league.id).let { if (it < 0) PRIORITY.size else it } }
            val a = lead.away.score?.toIntOrNull()
            val h = lead.home.score?.toIntOrNull()
            val state = when {
                a == null || h == null -> Part("${lead.away.name} v ${lead.home.name}")
                a == h -> Part("${lead.away.name} and ${lead.home.name} level at $a–$h")
                a > h -> Part("${lead.away.name} lead ${lead.home.name} $a–$h")
                else -> Part("${lead.home.name} lead ${lead.away.name} $h–$a")
            }
            sentence(Part("${live.size} live", key = true), Part(". "), state.copy(key = false))
        }
        val nextPick = picks.filter { it.game.state == Sports.State.UPCOMING && it.game.startMs > now }.minByOrNull { it.game.startMs }
        val nextGame = games.filter { it.state == Sports.State.UPCOMING && it.startMs > now }.minByOrNull { it.startMs }
        when {
            nextPick != null -> sentence(
                Part("Next: ${nextPick.game.away.name} at ${nextPick.game.home.name} ${timeOf(nextPick.game.startMs)}, "),
                Part("${nextPick.winner} ${Math.round(nextPick.winProb * 100)}%" + (nextPick.tier?.let { " ${it.label}" } ?: ""), key = true),
            )
            nextGame != null -> sentence(Part("Next: ${nextGame.away.name} at ${nextGame.home.name} ${timeOf(nextGame.startMs)}"))
        }
        val locks = picks.count { it.tier == NflModel.Tier.LOCK && it.game.state == Sports.State.UPCOMING }
        if (locks > 0) sentence(Part("$locks NFL ${if (locks == 1) "Lock" else "Locks"}", key = true), Part(" on the slate"))
        return out
    }
}
