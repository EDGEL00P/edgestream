package com.edgestream.tv.data

/**
 * Maps playlist channels onto the channels of one XMLTV guide.
 *
 * The provider's `tvg-id` is not trusted first: many playlists carry another channel's id
 * ("US| AHC FHD" is tagged `amc.us`), so a confident name match wins and the id is only a
 * fallback. Strategies, most to least certain:
 *  1. the channel's normalised name against the guide's display names / ids ("UK: BBC One HD" -> "bbcone")
 *  2. the initials of the guide's display name ("AHC" -> "American Heroes Channel HD")
 *  3. a looser name with region and "TV/Channel" words dropped ("Nine Network Sydney" -> "nine")
 *  4. the tvg-id exactly as written
 *  5. the tvg-id ignoring case and iptv-org "@feed" suffixes ("9Gem.au@Sydney" -> "9gem.au")
 *
 * Name keys that point at more than one guide channel are only used when the channel's country
 * picks exactly one of them; otherwise the channel stays unmatched rather than showing the wrong
 * programmes. The country check is strict: a channel tagged `us` never accepts a `.ca2` guide
 * channel just because it is the only candidate. Digits and "+1" are significant, so
 * "Sky Sports 1" never becomes "Sky Sports 2" and "E4 +1" never becomes "E4".
 */
class EpgMatcher(guide: Map<String, List<String>>) {

    private val ids = guide.keys
    private val byLowerId = HashMap<String, String>()
    private val byName = HashMap<String, MutableSet<String>>()
    private val byLoose = HashMap<String, MutableSet<String>>()
    private val byInitials = HashMap<String, MutableSet<String>>()

    init {
        guide.forEach { (id, names) ->
            byLowerId.putIfAbsent(id.lowercase(), id)
            val labels = names + id.substringBefore('.').substringBefore('@')
            labels.forEach { label ->
                normalise(label).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { LinkedHashSet() } += id }
                loose(label).takeIf { it.length >= 2 }?.let { byLoose.getOrPut(it) { LinkedHashSet() } += id }
            }
            // "American Heroes Channel HD" also answers to AHC. Built from display names only:
            // a guide id's first segment ("AMC.Canada.HD.ca2" -> "AMC") is not a channel name.
            names.forEach { label ->
                initials(label).forEach { key -> byInitials.getOrPut(key) { LinkedHashSet() } += id }
            }
        }
    }

    data class Match(val guideId: String, val method: String)

    fun match(tvgId: String?, name: String): Match? {
        // The name's own country prefix is the provider's most reliable label ("CA| AHC");
        // only when it has none fall back to the country in the tvg-id.
        val country = countryOf(name) ?: countryOf(tvgId)

        // Name first: a confident name/acronym match beats a tvg-id that points at another channel.
        val nameKey = normalise(name)
        if (nameKey.isNotEmpty()) {
            pick(byName[nameKey], country)?.let { return Match(it, "name") }
            pick(byInitials[nameKey], country)?.let { return Match(it, "initials") }
        }
        val looseKey = loose(name)
        if (looseKey.length >= 2) pick(byLoose[looseKey], country)?.let { return Match(it, "loose") }

        // No usable name match: the id is the last resort, exactly as written first.
        tvgId?.takeIf { it in ids }?.let { return Match(it, "id") }
        val base = tvgId?.substringBefore('@')
        base?.let { byLowerId[it.lowercase()] }?.let { return Match(it, "id-ci") }
        return null
    }

    /**
     * Narrows the guide channels that share a key.
     *
     * With a known country, only candidates carrying that country are acceptable. A single
     * candidate is still fine when it has no country suffix at all (feeds such as "mjh-nine-syd"),
     * but a lone foreign candidate is rejected: that is exactly how "US| AHC FHD" used to land on
     * AMC Canada.
     */
    private fun pick(candidates: Set<String>?, country: String?): String? {
        if (candidates.isNullOrEmpty()) return null
        if (country == null) return candidates.singleOrNull()
        val matching = candidates.filter { countryOf(it) == country }
        if (matching.size == 1) return matching.single()
        if (matching.isNotEmpty()) return null
        return candidates.singleOrNull()?.takeIf { countryOf(it) == null }
    }

    companion object {
        private val BRACKETS = Regex("""\[[^]]*]|\([^)]*\)""")
        private val PREFIX = Regex("""^[a-z]{2,3}\s*[|:\-]\s*""")
        private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|1080[pi]?|720p?|576p?|480p?|backup|vip|raw)\b""")
        private val PLUS = Regex("""\+\s*(\d)""")
        private val NON_ALNUM = Regex("""[^a-z0-9]+""")
        private val WORD_SPLIT = Regex("""[^A-Za-z0-9]+""")
        private val LOOSE_TAIL = Regex(
            """(channel|network|television|tv|au|uk|us|ca|nz|ie|australia|sydney|melbourne|brisbane|perth|adelaide|nsw|vic|qld|wa|sa|east|west|north|south|national|international|intl)$""",
        )
        /** `American.Heroes.Channel.HD.us2`, `AMC.Canada.HD.ca2`, `KAMC-DT.us_locals1`. */
        private val COUNTRY_SUFFIX = Regex("""\.([a-z]{2})(?:[0-9_][a-z0-9_]*)?(?:@.*)?$""", RegexOption.IGNORE_CASE)
        private val COUNTRY_PREFIX = Regex("""^\s*\|?\s*([A-Za-z]{2})\s*[|:\-]""")

        /** Two-letter tails that are quality tags, not country codes. */
        private val NOT_A_COUNTRY = setOf("hd", "sd", "tv", "fm")
        private val ENGLISH_COUNTRIES = setOf("us", "uk", "gb", "ca", "au", "nz", "ie")

        /** Words that never contribute to an acronym: quality tags are already stripped by [normalise]. */
        private val INITIAL_NOISE = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "tv", "television")
        /** Filler dropped from the second acronym attempt: "American Heroes Channel" -> AHC and AH. */
        private val INITIAL_FILLER = setOf("channel", "network", "the", "of", "and", "plus", "international", "national", "east", "west", "north", "south")

        fun normalise(name: String): String {
            var n = name.lowercase()
            n = BRACKETS.replace(n, " ")
            n = PREFIX.replace(n, "")
            n = PLUS.replace(n, " plus$1 ")
            n = QUALITY.replace(n, " ")
            return NON_ALNUM.replace(n, "")
        }

        fun loose(name: String): String {
            var n = normalise(name)
            while (true) {
                val m = LOOSE_TAIL.find(n) ?: break
                if (m.range.first < 2) break
                n = n.substring(0, m.range.first)
            }
            return n
        }

        /** "American Heroes Channel HD" -> ["ahc", "ah"]; single-word labels produce nothing. */
        fun initials(name: String): List<String> {
            var n = name.lowercase()
            n = BRACKETS.replace(n, " ")
            n = PREFIX.replace(n, "")
            n = PLUS.replace(n, " plus$1 ")
            val words = WORD_SPLIT.split(n).filter { it.isNotEmpty() && it.first().isLetter() }
            val significant = words.filter { it !in INITIAL_NOISE }
            val out = LinkedHashSet<String>()
            fun add(ws: List<String>) {
                if (ws.size < 2) return
                val key = ws.joinToString("") { it.first().toString() }
                if (key.length in 2..6) out += key
            }
            add(significant)
            add(significant.filter { it !in INITIAL_FILLER })
            return out.toList()
        }

        private val REGIONS = mapOf(
            "nsw" to "NSW", "sydney" to "NSW", "vic" to "VIC", "melbourne" to "VIC", "qld" to "QLD", "brisbane" to "QLD",
            "wa" to "WA", "perth" to "WA", "sa" to "SA", "adelaide" to "SA", "tas" to "TAS", "hobart" to "TAS",
            "nt" to "NT", "darwin" to "NT", "act" to "ACT", "canberra" to "ACT",
        )
        private val WORD = Regex("""[A-Za-z]+""")

        /** Australian state/city carried by a channel: "ABC TV WA" -> WA, feed "@Sydney" -> NSW. */
        fun regionOf(name: String, tvgId: String?): String? =
            (WORD.findAll(name).map { it.value.lowercase() } + listOfNotNull(tvgId?.substringAfter('@', "")?.lowercase()))
                .firstNotNullOfOrNull { REGIONS[it] }

        /** The name with state/city words removed: "ABC TV WA (720p)" -> "ABC TV  (720p)". */
        fun withoutRegion(name: String): String = WORD.replace(name) { m -> if (m.value.lowercase() in REGIONS) " " else m.value }

        /** "BBCOne.uk" / "9Gem.au@Sydney" -> "uk"/"au"; "American.Heroes.Channel.HD.us2" -> "us". */
        fun countryOf(s: String?): String? {
            if (s == null) return null
            COUNTRY_SUFFIX.find(s)?.groupValues?.get(1)?.lowercase()?.takeIf { it !in NOT_A_COUNTRY }?.let { return it }
            COUNTRY_PREFIX.find(s)?.groupValues?.get(1)?.lowercase()?.takeIf { it in ENGLISH_COUNTRIES }?.let { return it }
            return null
        }
    }
}
