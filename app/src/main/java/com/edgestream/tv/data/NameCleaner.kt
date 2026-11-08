package com.edgestream.tv.data

/** A cleaned display name plus the bits of information that were pulled out of it. */
data class CleanName(val title: String, val quality: String? = null, val tags: List<String> = emptyList())

/**
 * Tidies provider naming conventions for display:
 *   "UK: BBC One FHD"                         -> "BBC One"          quality FHD
 *   "|US| CNN HD ◉"                          -> "CNN"              quality HD
 *   "3Cat Exclusiu 1 (1080p) [Geo-blocked]"   -> "3Cat Exclusiu 1"  quality 1080p, tag Geo-blocked
 *   "EN - The Matrix (1999) [4K]"             -> "The Matrix (1999)" quality 4K
 * The stored name is never changed, so favourites, guide matching and search keep working.
 */
class NameCleaner(customPattern: String = "") {

    /** The user's own pattern, or null if blank or invalid. */
    val custom: Regex? = customPattern.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

    fun clean(raw: String): CleanName {
        var s = raw.trim()
        var quality: String? = null
        val tags = mutableListOf<String>()

        // [Geo-blocked], [Not 24/7], (Backup) ... become tags, unless they're just a year.
        s = BRACKETED.replace(s) { m ->
            val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim()
            when {
                inner.matches(YEAR) -> m.value
                QUALITY_WORD.matches(inner) -> { quality = better(quality, normaliseQuality(inner)); "" }
                inner.matches(COUNTRY_CODE) -> ""
                // Long bracketed text is an event description (shown in the guide), not a tag.
                inner.length > 20 -> ""
                else -> { tags += inner.replaceFirstChar { it.uppercase() }; "" }
            }
        }

        // Country / language prefixes: "UK:", "UK |", "|UK|", "UK-", "EN - "
        s = PREFIX.replace(s, "")

        // IPTV feed labels after a slash: "Sky Sports NFL/Action" -> "Sky Sports NFL".
        // The slash-suffix is provider metadata (what the feed carries), not the channel's name.
        s = FEED_LABEL.replace(s) { m -> m.groupValues[1] }

        // Trailing quality markers and decoration, repeatedly: "BBC One FHD ◉" -> "BBC One"
        while (true) {
            val m = SUFFIX.find(s) ?: break
            val word = m.groupValues[1]
            if (word.isNotBlank() && QUALITY_WORD.matches(word)) quality = better(quality, normaliseQuality(word))
            s = s.substring(0, m.range.first)
        }

        custom?.let { s = it.replace(s, "") }
        s = titleCase(tidy(s))
        // Old names in the playlist read as today's lineup: "BT Sport 1" -> "TNT Sports 1".
        s = Rebrands.current(s)
        return CleanName(s.ifEmpty { raw.trim() }, quality, tags.distinct())
    }

    /** A resolution (4K/FHD/HD/SD) always wins over codec or frame-rate markers. */
    private fun better(current: String?, candidate: String): String =
        if (current == null || (current !in RESOLUTIONS && candidate in RESOLUTIONS)) candidate else current

    private fun normaliseQuality(q: String): String = when (val u = q.uppercase()) {
        "2160P", "UHD", "4K" -> "4K"
        "1080P", "1080I", "FHD", "FULLHD", "FULL HD" -> "FHD"
        "720P", "HD" -> "HD"
        "576P", "480P", "SD" -> "SD"
        else -> u
    }

    companion object {
        /** Anything that isn't a letter, digit, space or ordinary punctuation: ✦ ● ★ emoji, box glyphs… */
        private val SYMBOLS = Regex("""[^\p{L}\p{N}\s&+'’.,:!?()/#-]""")
        private val SEPARATORS = Regex("""\s*\|\s*""")
        /** "AM | USA NBC" -> "USA NBC"; "[EN] COMEDY" -> "COMEDY"; "24/7-EN - BATMAN" -> "BATMAN". */
        private val GROUP_PREFIX = Regex("""^\s*(?:\[[A-Z-]{2,12}]\s*|(?:AM|EU|AS|AF|AR|OC|VIP|XX)\s*\|\s*|24/7-[A-Z]{2}\s*[-|:]\s*)""", RegexOption.IGNORE_CASE)

        /** Strips decorative symbols and stray separators from any display text. */
        fun tidy(text: String): String =
            SYMBOLS.replace(text, " ")
                .replace(SEPARATORS, " ")
                .replace(DOTS, " ")
                .replace(SPACES, " ").trim().trim('-', ':', '·', '/').trim()

        /** A film/episode title without its "EN| " / "[EN] " prefix or decoration. */
        /**
         * An episode's own title from a provider's label: "EN| Fire Country S05E01 - Hot Hot Heat"
         * -> "Hot Hot Heat". Null when nothing but the series and numbering is left, so the caller
         * shows "Episode N" instead of repeating the series name on every row.
         */
        fun episodeTitle(raw: String, series: String): String? {
            val t = EPISODE_NUMBERING.replace(PREFIX.replace(raw, ""), "").trim().trim('-', '–', ':', '|', '.').trim()
            return t.takeUnless { it.isEmpty() || it.equals(tidyTitle(series), true) || BARE_EPISODE.matches(it) }
        }

        /** Everything up to and including "S05E01" / "S5 E1" / "5x01": the series name and numbering. */
        private val EPISODE_NUMBERING = Regex("""^.*?\b(?:S\d{1,2}\s*E\d{1,3}|\d{1,2}x\d{2,3})\b""", RegexOption.IGNORE_CASE)
        private val BARE_EPISODE = Regex("""(?:episode|ep\.?)\s*\d+""", RegexOption.IGNORE_CASE)

        fun tidyTitle(text: String): String {
            val t = tidy(GROUP_PREFIX.replace(PREFIX.replace(text, ""), "")).ifEmpty { text.trim() }
            return TRAILING_YEAR.replace(t, "").trim().ifEmpty { t }
        }

        /** "Title - 2026" / "Title (2026)": the year is shown separately. "Blade Runner 2049" is left alone. */
        private val TRAILING_YEAR = Regex("""\s*(?:[-–|]\s*\(?|\()(19|20)\d{2}\)?\s*$""")

        /** Every channel is English now, so a country at the front of a group says nothing. */
        private val GROUP_COUNTRY = Regex("""^(?:USA|US|UK|CANADA|CA|AUSTRALIA|AU|NEW ZEALAND|NZ|IRELAND|IE|ENGLISH|EN)\b[\s:-]*""", RegexOption.IGNORE_CASE)
        private val TRAILING_COUNTRY = Regex("""[\s-]+(?:ENGLISH|EN)$""", RegexOption.IGNORE_CASE)

        /** Display form of a group or film category: "AM | USA NBC" -> "NBC", "[EN] COMEDY" -> "Comedy". */
        fun tidyGroup(text: String): String {
            val base = tidy(GROUP_PREFIX.replace(text, ""))
            val noCountry = TRAILING_COUNTRY.replace(GROUP_COUNTRY.replace(base, ""), "").trim()
            return titleCase(noCountry.ifEmpty { base }.ifEmpty { tidy(text) })
        }

        /** Broadcaster/league abbreviations that stay in capitals when a name is title-cased. */
        private val ACRONYMS = setOf(
            "ABC", "ACC", "ACCN", "A&E", "AHC", "AMC", "BBC", "BET", "BTN", "CBC", "CBS", "CMT", "CNBC", "CNN", "CP24", "CPAC", "CTV", "CW",
            "DAZN", "EPL", "ESPN", "ESPN2", "ESPNU", "EFL", "F1", "FS1", "FS2", "GAA", "HBO", "HGTV", "HSN", "ID", "IFC", "ITV",
            "ITVX", "LFC", "LMN", "MLB", "MLS", "MSNBC", "NESN", "MTV", "MTV2", "NBA", "NBC", "NCAA", "NFL", "NHL", "OWN", "PBS", "PGA",
            "PPV", "QVC", "RTE", "SBS", "SEC", "SNY", "STV", "TBS", "TCM", "TLC", "TNT", "TSN", "TV", "TVA", "UFC", "UK", "US", "USA",
            "VH1", "WNBA", "WWE", "YES", "4K", "TSN1", "TSN2", "TSN3", "TSN4", "TSN5", "TV1", "TV2", "TVO", "AXS", "NHK", "RT",
        )
        private val WORD = Regex("""[\p{L}\p{N}'’+&]+""")

        /**
         * Shouting all-caps provider names become title case ("HULU ORIGINALS 5" -> "Hulu Originals 5")
         * while real abbreviations and call signs stay ("ESPN", "WABC"). Mixed-case names are left alone.
         */
        fun titleCase(text: String): String {
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty() || letters != letters.uppercase()) return text
            return WORD.replace(text) { m ->
                val w = m.value
                val bare = w.trimEnd('+')
                when {
                    bare.uppercase() in ACRONYMS -> w
                    // Call signs (WABC, KTLA) and vowel-less abbreviations (HD, NHL, FX) stay capitalised.
                    bare.length in 3..4 && (bare[0] == 'W' || bare[0] == 'K') && bare.count { it in "AEIOU" } <= 1 -> w
                    bare.none { it in "AEIOUY" } -> w
                    else -> w.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
        }

        private val BRACKETED = Regex("""\[([^\]]*)]|\(([^)]*)\)""")
        private val YEAR = Regex("""(19|20)\d{2}""")
        private val COUNTRY_CODE = Regex("""[A-Z]{2}""")
        private val QUALITY_WORD = Regex("""(?i)(2160p|1080[pi]|720p|576p|480p|uhd|fhd|full ?hd|hd|sd|4k|hevc|h\.?265|50fps|60fps)""")
        private val PREFIX = Regex("""^\s*(\|[A-Za-z]{2,4}\|\s*|24/7-[A-Z]{2}\s*[-|:]\s*|[A-Z]{2,4}\s*[:|]\s*|[A-Z]{2,3}\s+-\s+)""")
        private val SUFFIX = Regex("""(?:[\s\-|:•·]+(2160p|1080[pi]|720p|576p|480p|UHD|FHD|FULL ?HD|HD|SD|4K|HEVC|H\.?265|50fps|60fps)|\s*[◉●★☆✦]+)\s*$""", RegexOption.IGNORE_CASE)
        /**
         * IPTV providers append a slash descriptor for what the feed carries: "Sky Sports
         * NFL/Action", "beIN/1", "TSN/2". Real broadcast channels don't have these — the
         * channel IS "Sky Sports NFL", not "Sky Sports NFL/Action".
         */
        private val FEED_LABEL = Regex("""^(.+?)/(?:action|main ?event|mix|news|premier ?league|extra|prime|f1|cricket|golf|tennis|nfl|nba|nhl|mlb|soccer|football|sports ?1|sports ?2|sports ?3|sports ?4|sports ?5|red ?zone|league ?pass|sunday ?ticket|center ?ice|extra ?innings|willow|ppv|events?|24/7|live|backup|alt|feed|raw|vip)\s*$""", RegexOption.IGNORE_CASE)
        private val RESOLUTIONS = setOf("4K", "FHD", "HD", "SD")
        private val SPACES = Regex("""\s+""")
        /** "Sky..Sports" or "Sky.Sports" -> "Sky Sports": IPTV providers use dots as separators. */
        private val DOTS = Regex("""\.{2,}""")
    }
}
