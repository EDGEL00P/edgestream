package com.edgestream.tv.data

import java.util.Locale

/**
 * Ask: direct answers for a typed or spoken query, drawn from what the app already knows (live
 * scores, NFL picks and prop leans). "live", "locks", "value" and "props" list those; anything
 * else matches teams and leagues by name or abbreviation.
 */
object Ask {
    sealed interface Answer {
        val title: String
        val detail: String

        data class Live(val game: Sports.Game) : Answer {
            override val title get() = "${game.away.name} ${game.away.score ?: "0"} – ${game.home.score ?: "0"} ${game.home.name}"
            override val detail get() = "${game.league.name} · ${game.detail}"
        }

        data class Pick(val pick: NflPicks.Pick) : Answer {
            override val title get() = "${pick.game.away.name} at ${pick.game.home.name}"
            override val detail get() = "Pick ${pick.winner} ${pct(pick.winProb)}" +
                (pick.tier?.let { " · ${it.label}" } ?: "") + pick.values.joinToString("") { " · ${it.selection}" }
        }

        data class Prop(val lean: NflProps.Lean, val pick: NflPicks.Pick) : Answer {
            override val title get() = lean.selection
            override val detail get() = "${pct(lean.confidence)} · ${pick.game.away.abbr} @ ${pick.game.home.abbr}"
        }
    }

    fun answer(query: String, games: List<Sports.Game>, board: NflPicks.Board?, limit: Int = 12): List<Answer> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 2) return emptyList()
        val picks = board?.picks.orEmpty()
        fun pickFor(espnGame: String) = picks.firstOrNull { it.espnId == espnGame }
        val live = games.filter { it.state == Sports.State.LIVE }
        val out = when (q) {
            "live", "live now", "what's on", "whats on" -> live.map { Answer.Live(it) }
            "lock", "locks" -> picks.filter { it.tier != null }.sortedByDescending { it.winProb }.map { Answer.Pick(it) }
            "value", "value picks" -> picks.filter { it.values.isNotEmpty() }.map { Answer.Pick(it) }
            "props", "prop", "player props" -> board?.props.orEmpty().mapNotNull { l -> pickFor(l.espnGame)?.let { Answer.Prop(l, it) } }
            else -> {
                fun matches(t: Sports.Team) = t.abbr.lowercase(Locale.ROOT) == q ||
                    t.name.lowercase(Locale.ROOT).split(' ').any { it.startsWith(q) } || t.name.lowercase(Locale.ROOT).startsWith(q)
                fun hit(g: Sports.Game) = matches(g.home) || matches(g.away) || g.league.name.lowercase(Locale.ROOT).startsWith(q)
                live.filter(::hit).map { Answer.Live(it) } +
                    picks.filter { hit(it.game) }.map { Answer.Pick(it) } +
                    board?.props.orEmpty().filter { it.player.name.lowercase(Locale.ROOT).split(' ').any { w -> w.startsWith(q) } }
                        .mapNotNull { l -> pickFor(l.espnGame)?.let { Answer.Prop(l, it) } }
            }
        }
        return out.take(limit)
    }

    private fun pct(p: Double) = "${Math.round(p * 100)}%"
}
