package com.edgestream.tv.remote

import com.edgestream.tv.ui.Screen

/**
 * What a spoken (or typed) phone command means. Speech-to-text arrives as plain words, so this
 * reads the few shapes people actually say: a channel number, "watch <channel>", "open <place>",
 * the questions "what's on <channel>", "today's games" and "when do <team> play", and treats
 * everything else as a search.
 */
sealed interface VoiceCommand {
    data class Tune(val number: Int) : VoiceCommand
    data class Watch(val channel: String) : VoiceCommand
    data class Open(val screen: Screen) : VoiceCommand
    data class Search(val query: String) : VoiceCommand
    /** "What's on Fox League?" — null channel means what's on in general (the Guide). */
    data class WhatsOn(val channel: String?) : VoiceCommand
    /** "Today's games", "scores". */
    data object Games : VoiceCommand
    /** "When do the Broncos play?", "Broncos score", "how are the Storm going?" */
    data class Team(val team: String) : VoiceCommand

    /** Questions answered in words (on the phone and the TV) rather than carried out. */
    val isQuestion: Boolean get() = this is WhatsOn || this is Games || this is Team

    companion object {
        private val FILLER = Regex("""^(hey|ok|okay|please|can you|could you|i want to|i'd like to)\s+""")
        private val NUMBER = Regex("""^(?:channel|ch|number)?\s*(\d{1,4})$""")
        private val WATCH = Regex("""^(?:watch|play|put on|tune to|switch to|change to|turn on|go to channel|go to)\s+(.+)$""")
        private val WHATS_ON = Regex("""^(?:what's|whats|what is)\s+(?:on|playing|showing)(?:\s+(?:right\s+)?(?:now|tonight))?(?:\s+(?:on\s+)?(?:channel\s+)?(.+?))?(?:\s+(?:right\s+)?(?:now|tonight))?$""")
        private val GAMES = Regex("""^(?:(?:what|which)\s+games\s+(?:are\s+)?on.*|today's games|todays games|games today|(?:the\s+)?scores|(?:the\s+)?sport scores|what's on in sports?)$""")
        private val TEAM = listOf(
            Regex("""^when\s+(?:do|does|is|are)\s+(?:the\s+)?(.+?)\s+(?:play(?:ing)?|on|game)(?:\s+next)?$"""),
            Regex("""^(?:what's|whats|what is)\s+the\s+(.+?)\s+score$"""),
            Regex("""^how\s+(?:are|is)\s+(?:the\s+)?(.+?)\s+(?:going|doing)$"""),
            Regex("""^(?:the\s+)?(.+?)\s+score$"""),
        )
        private val OPEN = Regex("""^(?:open|show me|show|go to|take me to)\s+(?:the\s+)?(.+)$""")

        /** Spoken names for each place; the guide lives inside Live TV. */
        private val PLACES: Map<String, Screen> = buildMap {
            listOf("home", "home screen").forEach { put(it, Screen.HOME) }
            listOf("live", "live tv", "tv", "guide", "tv guide", "channels").forEach { put(it, Screen.LIVE) }
            listOf("movies", "films").forEach { put(it, Screen.MOVIES) }
            listOf("series", "shows", "tv shows").forEach { put(it, Screen.SERIES) }
            listOf("sport", "sports").forEach { put(it, Screen.SPORTS) }
            listOf("weather", "radar", "weather radar").forEach { put(it, Screen.RADAR) }
            listOf("calendar", "planner", "my planner").forEach { put(it, Screen.CALENDAR) }
            listOf("betting", "odds").forEach { put(it, Screen.BETTING) }
            listOf("apps").forEach { put(it, Screen.APPS) }
            listOf("settings").forEach { put(it, Screen.SETTINGS) }
            listOf("search").forEach { put(it, Screen.SEARCH) }
        }

        fun parse(raw: String): VoiceCommand? {
            // Same length as the lowercased text below, so a match's tail maps back to the original.
            val original = raw.replace(",", "").replace(Regex("""\s+"""), " ").trim().trimEnd('.', '!', '?')
            if (original.isEmpty()) return null
            var t = original.lowercase()
            while (true) { val next = FILLER.replace(t, ""); if (next == t) break; t = next }

            NUMBER.matchEntire(t)?.let { return Tune(it.groupValues[1].toInt()) }
            if (GAMES.matches(t)) return Games
            // Fillers only ever come off the front, so a span of [t] is the same span of the
            // original shifted: the user's own spelling and capitals survive.
            fun spoken(span: IntRange): String {
                val shift = original.length - t.length
                return original.substring(span.first + shift, span.last + 1 + shift).trim()
            }
            WHATS_ON.matchEntire(t)?.let { m -> return WhatsOn(m.groups[1]?.range?.let(::spoken)?.ifEmpty { null }) }
            TEAM.firstNotNullOfOrNull { it.matchEntire(t) }?.let { m -> return Team(spoken(m.groups[1]!!.range)) }
            PLACES[t]?.let { return Open(it) }
            OPEN.matchEntire(t)?.let { m -> PLACES[m.groupValues[1]]?.let { return Open(it) } }
            WATCH.matchEntire(t)?.let { m ->
                val target = m.groupValues[1].removePrefix("channel ").trim()
                NUMBER.matchEntire(target)?.let { return Tune(it.groupValues[1].toInt()) }
                PLACES[target]?.let { return Open(it) }
                // Keep the user's own spelling and capitals for the channel lookup.
                return Watch(original.takeLast(target.length).trim())
            }
            val search = Regex("""^(?:search for|search|find|look for|look up)\s+(.+)$""").matchEntire(t)
            return Search(search?.let { original.takeLast(it.groupValues[1].length).trim() } ?: original)
        }
    }
}
