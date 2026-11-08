package com.edgestream.tv.remote

import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.Format

/**
 * The words for answering a spoken question ([VoiceCommand.isQuestion]), shown on the phone and
 * on the TV. Scores are left out whenever the viewer has Hide scores on.
 */
object VoiceAnswers {

    fun channel(name: String, now: ProgrammeEntity?, next: ProgrammeEntity?, use24h: Boolean): String = when {
        now == null -> "$name has no guide listing right now."
        else -> "$name: ${now.title}, until ${Format.time(now.stop, use24h)}." +
            (next?.let { " Then ${it.title} at ${Format.time(it.start, use24h)}." } ?: "")
    }

    fun games(games: List<Sports.Game>, hideScores: Boolean, use24h: Boolean, now: Long): String {
        val live = games.filter { it.state == Sports.State.LIVE }
        val next = games.filter { it.state == Sports.State.UPCOMING && it.startMs > now }.minByOrNull { it.startMs }
        return buildString {
            if (live.isEmpty()) append("No games are live right now.")
            else {
                append("${live.size} live: ")
                append(live.take(3).joinToString("; ") { line(it, hideScores) })
                if (live.size > 3) append("; and ${live.size - 3} more")
                append('.')
            }
            next?.let { append(" Next: ${it.away.name} v ${it.home.name}, ${Format.day(it.startMs)} ${Format.time(it.startMs, use24h)}.") }
        }
    }

    /** A team's game: live score, the next fixture with its channel, or the last result. */
    fun team(team: String, game: Sports.Game?, channel: String?, hideScores: Boolean, use24h: Boolean): String {
        val g = game ?: return "I couldn't find a game for $team this week."
        val on = channel?.let { " on $it" } ?: ""
        return when (g.state) {
            Sports.State.LIVE -> "Live now$on: ${line(g, hideScores)}."
            Sports.State.UPCOMING -> "${g.away.name} v ${g.home.name}, ${Format.day(g.startMs)} at ${Format.time(g.startMs, use24h)}$on."
            Sports.State.FINAL -> if (hideScores) "${g.away.name} v ${g.home.name} has finished." else "Final: ${line(g, false)}."
        }
    }

    /** The game [team] plays in: live first, then the next one, then the latest result. */
    fun findTeam(games: List<Sports.Game>, team: String): Sports.Game? {
        val want = norm(team).removePrefix("the")
        if (want.length < 2) return null
        fun matches(t: Sports.Team): Boolean {
            val name = norm(t.name)
            return name == want || norm(t.abbr) == want || name.endsWith(want) || name.startsWith(want)
        }
        val mine = games.filter { matches(it.home) || matches(it.away) }
        return mine.firstOrNull { it.state == Sports.State.LIVE }
            ?: mine.filter { it.state == Sports.State.UPCOMING }.minByOrNull { it.startMs }
            ?: mine.filter { it.state == Sports.State.FINAL }.maxByOrNull { it.startMs }
    }

    private fun line(g: Sports.Game, hideScores: Boolean): String =
        if (hideScores || g.away.score == null || g.home.score == null) "${g.away.name} v ${g.home.name}" + (g.detail.takeIf { it.isNotBlank() && !hideScores }?.let { " ($it)" } ?: "")
        else "${g.away.name} ${g.away.score}, ${g.home.name} ${g.home.score}" + (g.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
