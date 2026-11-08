package com.edgestream.tv.data

/**
 * Turns a provider's category names into clean, consistent labels: "Action/WAR" becomes
 * "Action & War", "COMEDY MOVIES" becomes "Comedy", "Amazon Prime+" becomes the collection
 * "Prime Video". Categories that end up with the same label merge into one tile.
 */
object CategoryLabels {

    enum class Kind { GENRE, COLLECTION }

    data class Label(val name: String, val kind: Kind)

    /** One tile: a label and every provider category behind it. */
    data class Merged(val label: Label, val raws: List<String>, val count: Int)

    /** Streaming services, checked in order (so "HBO Max" is Max, not HBO). */
    private val PLATFORMS = listOf(
        Regex("""netflix""", RegexOption.IGNORE_CASE) to "Netflix",
        Regex("""amazon|prime\s*video|\bprime\b""", RegexOption.IGNORE_CASE) to "Prime Video",
        Regex("""disney""", RegexOption.IGNORE_CASE) to "Disney+",
        Regex("""apple\s*tv""", RegexOption.IGNORE_CASE) to "Apple TV+",
        Regex("""hbo|\bmax\b""", RegexOption.IGNORE_CASE) to "Max",
        Regex("""hulu""", RegexOption.IGNORE_CASE) to "Hulu",
        Regex("""paramount""", RegexOption.IGNORE_CASE) to "Paramount+",
        Regex("""peacock""", RegexOption.IGNORE_CASE) to "Peacock",
        Regex("""showtime""", RegexOption.IGNORE_CASE) to "Showtime",
        Regex("""starz""", RegexOption.IGNORE_CASE) to "Starz",
        Regex("""britbox""", RegexOption.IGNORE_CASE) to "BritBox",
        Regex("""crunchyroll""", RegexOption.IGNORE_CASE) to "Crunchyroll",
        Regex("""\bstan\b""", RegexOption.IGNORE_CASE) to "Stan",
        Regex("""\bbinge\b""", RegexOption.IGNORE_CASE) to "Binge",
        Regex("""\bmgm\b""", RegexOption.IGNORE_CASE) to "MGM+",
        Regex("""\bamc\b""", RegexOption.IGNORE_CASE) to "AMC+",
    )

    /** Whole-label renames, matched on the cleaned, lower-case text. */
    private val RENAMES = mapOf(
        "science fiction" to "Sci-Fi", "sci fi" to "Sci-Fi", "scifi" to "Sci-Fi", "sci-fi" to "Sci-Fi",
        "documentaries" to "Documentary", "docs" to "Documentary", "documentary" to "Documentary",
        "kids" to "Kids & Family", "children" to "Kids & Family", "family" to "Kids & Family", "kids & family" to "Kids & Family",
        "4k" to "4K Ultra HD", "4k uhd" to "4K Ultra HD", "uhd" to "4K Ultra HD", "4k ultra hd" to "4K Ultra HD",
        "3d" to "3D", "romance" to "Romance", "romantic" to "Romance", "animation" to "Animation", "animated" to "Animation",
        "tv shows" to "TV Shows", "stand up" to "Stand-Up Comedy", "standup" to "Stand-Up Comedy",
    )

    /** Words that only say "this is a list of films/shows" and add nothing to a label. */
    private val FILLER = Regex("""\s+(movies?|films?|series|tv shows?|shows?|collection|vod)$""", RegexOption.IGNORE_CASE)

    fun label(raw: String): Label {
        val tidy = NameCleaner.tidyGroup(raw).trim()
        PLATFORMS.firstOrNull { (re, _) -> re.containsMatchIn(tidy) }?.let { return Label(it.second, Kind.COLLECTION) }
        var s = tidy
        while (true) { val next = FILLER.replace(s, "").trim(); if (next == s || next.isEmpty()) break; s = next }
        s = s.replace(Regex("""\s*[/|+]\s*|\s+-\s+"""), " & ").replace(Regex("""\s*&\s*"""), " & ")
        RENAMES[s.lowercase()]?.let { return Label(it, Kind.GENRE) }
        return Label(titleCase(s), Kind.GENRE)
    }

    /** Labels every category, merges duplicates, and sorts A–Z within each kind. */
    fun merge(categories: List<Pair<String, Int>>): List<Merged> =
        categories.groupBy { label(it.first) }
            .map { (label, list) -> Merged(label, list.map { it.first }, list.sumOf { it.second }) }
            .filter { it.label.name.isNotBlank() && !com.edgestream.tv.ui.AppViewModel.isAdult(it.label.name) }
            .sortedWith(compareBy({ it.label.kind }, { it.label.name.lowercase() }))

    private val KEEP_UPPER = setOf("3D", "4K", "UHD", "HD", "TV", "UK", "US", "USA", "WWE", "UFC", "NFL", "NBA", "MLB", "NHL", "F1", "DC", "MCU")

    private fun titleCase(s: String) = s.split(' ').joinToString(" ") { w ->
        when {
            w.isEmpty() -> w
            w.uppercase() in KEEP_UPPER -> w.uppercase()
            w == "&" -> w
            else -> w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
