package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity

/**
 * Turns provider group names into a stable, useful browse taxonomy:
 * country first, then a specific genre or sports league ("USA · NFL", "UK · Premier League").
 *
 * Provider groups are inconsistent ("Sports", "SPORT", "UK| Sports", "NFL", "PPV Events").
 * Classification is keyword-based and always falls back to the provider's own group name.
 */
object ChannelTaxonomy {
    private data class Rule(val label: String, val regex: Regex)

    private fun rule(label: String, pattern: String) = Rule(label, Regex(pattern, RegexOption.IGNORE_CASE))

    /** Most specific sports first; the generic "Sports" rule comes last. */
    private val SPORT_LEAGUES = listOf(
        rule("NFL", """\b(nfl|redzone|sunday ticket|nfl network)\b"""),
        rule("College Sports", """\b(college|ncaaf|ncaab|ncaa|sec network|acc network|big ten|btn|pac-12|pac12|march madness)\b"""),
        rule("Basketball", """\b(nba|nbatv|league pass|wnba|basketball)\b"""),
        rule("Hockey", """\b(nhl|hockey|center ice|ohl|whl|qmjhl|ahl|chl)\b"""),
        rule("Baseball", """\b(mlb|baseball|extra innings)\b"""),
        rule("NRL", """\bnrl\b"""),
        rule("AFL", """\b(afl|australian football)\b"""),
        rule("Rugby", """\b(rugby|super rugby)\b"""),
        rule("Motorsport", """\b(f1|formula 1|formula one|motorsport|nascar|indycar|motogp|supercars)\b"""),
        rule("Fight Sports", """\b(ufc|mma|fight pass|boxing|matchroom|top rank|wwe|wrestling|aew|pbr)\b"""),
        rule("Golf", """\b(golf|pga|lpga)\b"""),
        rule("Tennis", """\b(tennis|atp|wta)\b"""),
        rule("Cricket", """\b(cricket|t20|bbl|sheffield shield)\b"""),
        rule("Racing", """\b(racing|tvg|greyhound|harness|trackside)\b"""),
        rule("Soccer", """\b(soccer|football|premier league|epl|champions league|ucl|mls|efl|a-league|la liga|serie a|bundesliga)\b"""),
    )

    private val SPORT = Regex(
        """\b(sport|sports|espn|fs1|fs2|sky sports|bt sport|tnt sports|sportsnet|tsn|rds|eurosport|""" +
            """nfl|nba|nhl|mlb|wnba|ncaa|college|ufc|mma|boxing|dazn|bein|golf|tennis|f1|formula|motorsport|""" +
            """racing|premier|epl|champions league|ucl|mls|afl|a-league|soccer|football|cricket|rugby|nrl|""" +
            """kayo|optus sport|stan sport|redzone|league pass|sunday ticket|ppv|pay per view|wwe|wrestling|""" +
            """matchroom|top rank|darts|snooker|hockey|baseball|ohl|whl|qmjhl|ahl|chl|pbr|flo)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val WEATHER = Regex("""\b(weather|accuweather|met office|weather channel)\b""", RegexOption.IGNORE_CASE)
    private val NEWS = Regex(
        """\b(news|cnn|msnbc|cnbc|bloomberg|al jazeera|france 24|dw|sky news|bbc news|fox news|""" +
            """abc news|nbc news|cbs news|newsmax|oann|gb news|trt|rt|euronews|newsnation)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val KIDS = Regex(
        """\b(kids|cartoon|nickelodeon|nick|disney|junior|baby|boomerang|pbs kids|cbeebies|""" +
            """cartoon network|kids tv)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MOVIES = Regex(
        """\b(movie|movies|cinema|film|hbo|starz|showtime|cinemax|tcm|sky cinema|""" +
            """paramount network|mgm|amc|film4)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DOCUMENTARY = Regex(
        """\b(documentary|discovery|history|nat geo|national geographic|animal planet|""" +
            """science|investigation|crime|planet|smithsonian)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MUSIC = Regex(
        """\b(music|mtv|vh1|vevo|kiss|hits|country music|now 80s|now 90s|clubland)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LIFESTYLE = Regex(
        """\b(food|travel|home|cooking|garden|hgtv|tlc|reality|diy|fashion|beauty|lifestyle)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHOPPING = Regex("""\b(shop|shopping|qvc|hsn)\b""", RegexOption.IGNORE_CASE)
    private val RELIGION = Regex("""\b(god|christian|faith|islam|jewish|religious|church|gospel)\b""", RegexOption.IGNORE_CASE)
    private val EDUCATION = Regex("""\b(education|university|learn|school|academy)\b""", RegexOption.IGNORE_CASE)
    private val ENTERTAINMENT = Regex(
        """\b(series|drama|entertainment|abc|nbc|cbs|fox|itv|channel 4|channel 5|""" +
            """bbc one|bbc two|usa network|tnt|tbs|fx|fxx|syfy|bravo|e!|oxygen|comedy)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Network affiliate groups ("AM | USA NBC", "UK LOCAL CHANNEL"): the local channels shelf. */
    private val LOCAL_GROUP = Regex("""\b(abc|nbc|cbs|fox|cw|pbs|my|metv|ion|telemundo|univision|local)\b""", RegexOption.IGNORE_CASE)
    /** Groups that only hold per-event slots (pay-per-view, ESPN+ and the like). */
    private val NATIONAL_NEWS = Regex("""\b(fox news|fox business|abc news|cbs news|nbc news|msnbc|cnn|cnbc|newsmax|newsnation)\b""", RegexOption.IGNORE_CASE)
    private val EVENT_GROUP = Regex("""\b(ppv|live events?|events?|espn plus|espn\+)\b""", RegexOption.IGNORE_CASE)
    private val MOVIE_LOOPS = Regex("""\bcinemania\b""", RegexOption.IGNORE_CASE)
    private val KIDS_LOOPS = Regex("""\bplay kidz\b""", RegexOption.IGNORE_CASE)

    private val ADULT = Regex("""\b(xxx|adult|porn|18\+|playboy|hustler)\b""", RegexOption.IGNORE_CASE)

    private val COUNTRY_NAMES = mapOf(
        "US" to "USA", "GB" to "UK", "UK" to "UK", "CA" to "Canada", "AU" to "Australia",
        "NZ" to "New Zealand", "IE" to "Ireland", "ZA" to "South Africa", "IN" to "India",
        "DE" to "Germany", "FR" to "France", "ES" to "Spain", "IT" to "Italy", "PT" to "Portugal",
        "NL" to "Netherlands", "BR" to "Brazil", "MX" to "Mexico", "AR" to "Argentina",
        "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland", "PL" to "Poland",
        "RU" to "Russia", "TR" to "Turkey", "AE" to "UAE", "SA" to "Saudi Arabia", "QA" to "Qatar",
        "JP" to "Japan", "KR" to "South Korea", "CN" to "China", "PH" to "Philippines",
        "ID" to "Indonesia", "MY" to "Malaysia", "SG" to "Singapore", "TH" to "Thailand",
        "VN" to "Vietnam", "PK" to "Pakistan", "BD" to "Bangladesh", "NG" to "Nigeria",
        "KE" to "Kenya", "EG" to "Egypt", "IL" to "Israel", "GR" to "Greece", "CZ" to "Czechia",
        "AT" to "Austria", "CH" to "Switzerland", "BE" to "Belgium", "HU" to "Hungary",
        "RO" to "Romania", "UA" to "Ukraine",
    )

    /** Display country for a channel ("UK", "Australia", …), or null when nothing tags it. */
    fun country(channel: ChannelEntity): String? = countryName(channel)

    fun groupKey(channel: ChannelEntity): String {
        val text = "${channel.name} ${channel.groupName} ${channel.tvgId.orEmpty()} ${channel.epgId.orEmpty()}"
        // The catalogue's category is curated; the name patterns below are the fallback guess.
        val known = ChannelIdentities.current?.get(channel.identity)?.categories?.firstNotNullOfOrNull { CATEGORY_LABELS[it] }
        val sporty = known == "Sports" || SPORT.containsMatchIn(text)
        val raw = when {
            // A slot that only carries whichever event is booked is not a channel to browse by genre.
            NameListings.eventStart(channel.name) != null || NameListings.isIdleSlot(channel.name) ||
                EVENT_GROUP.containsMatchIn(channel.groupName) -> "Live Events"
            MOVIE_LOOPS.containsMatchIn(text) -> "Movies"
            KIDS_LOOPS.containsMatchIn(text) -> "Kids"
            !sporty && NameListings.isLoop(channel) -> "24/7"
            known == "Sports" -> SPORT_LEAGUES.firstOrNull { it.regex.containsMatchIn(text) }?.label ?: "Sports"
            known != null -> known
            SPORT.containsMatchIn(text) ->
                SPORT_LEAGUES.firstOrNull { it.regex.containsMatchIn(text) }?.label ?: "Sports"
            // An affiliate group's stations ("NBC Chicago News") are local; the national networks are News.
            LOCAL_GROUP.containsMatchIn(channel.groupName) && !NATIONAL_NEWS.containsMatchIn(channel.name) -> "Local Channels"
            NEWS.containsMatchIn(channel.name) -> "News"
            WEATHER.containsMatchIn(text) -> "Other"
            NEWS.containsMatchIn(text) -> "News"
            KIDS.containsMatchIn(text) -> "Kids"
            MOVIES.containsMatchIn(text) -> "Movies"
            DOCUMENTARY.containsMatchIn(text) -> "Documentary"
            MUSIC.containsMatchIn(text) -> "Music"
            LIFESTYLE.containsMatchIn(text) -> "Lifestyle"
            SHOPPING.containsMatchIn(text) || RELIGION.containsMatchIn(text) || EDUCATION.containsMatchIn(text) -> "Other"
            ENTERTAINMENT.containsMatchIn(text) -> "Entertainment"
            ADULT.containsMatchIn(text) -> "Adult"
            else -> NameCleaner.tidyGroup(channel.groupName).ifBlank { "Other" }
        }
        val country = countryName(channel)
        // A provider's own label that names no category (a brand, "Streamed Channels") is not a shelf;
        // a group named just for the country ("AS | AUSTRALIA") holds its everyday local channels.
        val label = canonicalLabel(raw).let { l ->
            PROVIDER_LABELS.firstOrNull { it.first.containsMatchIn(l) }?.second
                ?: when {
                    l in KNOWN_LABELS || l in SPORT_LABELS || l in SHELVES -> l
                    country != null && l.equals(country, ignoreCase = true) -> "Local Channels"
                    else -> "Other"
                }
        }
        return when {
            country != null && !label.contains(country, ignoreCase = true) -> "$country · $label"
            // A channel with no country code anywhere is not a second "UK · General"; say so.
            country == null && !label.contains(" · ") -> "$INTERNATIONAL · $label"
            else -> label
        }
    }

    private const val INTERNATIONAL = "International"
    private val SHELVES = setOf("Live Events", "Local Channels", "24/7")
    private val PROVIDER_LABELS = listOf(
        Regex("""\b(hulu|peacock|paramount|max|netflix|prime|apple)\b""", RegexOption.IGNORE_CASE) to "Entertainment",
        Regex("""\bspectrum\b""", RegexOption.IGNORE_CASE) to "Local Channels",
        Regex("""^general$""", RegexOption.IGNORE_CASE) to "Entertainment",
    )
    private val SPORT_LABELS = setOf("NFL", "College Sports", "Basketball", "Hockey", "Baseball", "NRL", "AFL", "Rugby", "Motorsport", "Fight Sports", "Golf", "Tennis", "Cricket", "Racing", "Soccer", "Sports")
    /** A country with fewer channels than this is one group; it is too small to split. */
    private const val SMALL_COUNTRY = 40
    /** A category with fewer channels than this joins its country's Sports or Other group. */
    private const val SMALL_GROUP = 10

    /**
     * The lineup as groups a person can scan, like a TV's own guide: one country's big categories,
     * small categories folded into "Sports" or "Other", and small countries as a single group.
     * Group order follows the lineup (first channel seen).
     */
    fun groupAll(channels: List<ChannelEntity>): Map<String, List<ChannelEntity>> {
        val fine = channels.map { it to groupKey(it) }
        val sizes = fine.groupingBy { it.second }.eachCount()
        val countries = sizes.entries.groupingBy { it.key.substringBefore(" · ") }.fold(0) { acc, e -> acc + e.value }
        val folded = sizes.keys.associateWith { key -> fold(key, sizes.getValue(key), countries[key.substringBefore(" · ")] ?: 0) }
        return LinkedHashMap<String, MutableList<ChannelEntity>>().also { out ->
            fine.forEach { (c, key) -> out.getOrPut(folded.getValue(key)) { mutableListOf() } += c }
        }
    }

    internal fun fold(key: String, size: Int, countrySize: Int): String {
        val country = key.substringBefore(" · ")
        val label = key.substringAfter(" · ", "")
        return when {
            label.isEmpty() -> key
            countrySize < SMALL_COUNTRY -> country
            size >= SMALL_GROUP -> key
            label in SPORT_LABELS -> "$country · Sports"
            else -> "$country · Other"
        }
    }

    /** iptv-org categories that name a shelf; "general", "series" and the like fall through to the name. */
    private val CATEGORY_LABELS = mapOf(
        "sports" to "Sports", "news" to "News", "business" to "News", "weather" to "Other",
        "kids" to "Kids", "animation" to "Kids", "movies" to "Movies", "documentary" to "Documentary",
        "science" to "Documentary", "music" to "Music", "lifestyle" to "Lifestyle", "cooking" to "Lifestyle",
        "travel" to "Lifestyle", "shop" to "Other", "religious" to "Other", "education" to "Other",
        "comedy" to "Entertainment", "entertainment" to "Entertainment",
    )

    private fun countryName(channel: ChannelEntity): String? {
        val code = EpgMatcher.countryOf(channel.name)
            // Provider groups often carry the country after their region prefix: "AM | USA GENERAL".
            ?: countryWord(channel.groupName)
            ?: EpgMatcher.countryOf(channel.tvgId)
            ?: EpgMatcher.countryOf(channel.epgId)
            ?: EpgMatcher.countryOf(channel.groupName)
            ?: ChannelIdentities.current?.get(channel.identity)?.country
            ?: return null
        val upper = code.uppercase()
        return COUNTRY_NAMES[upper] ?: upper
    }

    /** Two-letter codes and full names that appear inside a provider group, not just at its start. */
    private val COUNTRY_TOKEN = Regex("""(?:^|[\s|])(US|UK|GB|CA|AU|NZ|IE)(?=[\s|:\-])""", RegexOption.IGNORE_CASE)
    private val COUNTRY_WORDS = listOf(
        Regex("""\bUSA\b""", RegexOption.IGNORE_CASE) to "us",
        Regex("""\bUNITED STATES\b""", RegexOption.IGNORE_CASE) to "us",
        Regex("""\bUNITED KINGDOM\b""", RegexOption.IGNORE_CASE) to "gb",
        Regex("""\bGREAT BRITAIN\b""", RegexOption.IGNORE_CASE) to "gb",
        Regex("""\bCANADA\b""", RegexOption.IGNORE_CASE) to "ca",
        Regex("""\bAUSTRALIA\b""", RegexOption.IGNORE_CASE) to "au",
        Regex("""\bNEW ZEALAND\b""", RegexOption.IGNORE_CASE) to "nz",
        Regex("""\bIRELAND\b""", RegexOption.IGNORE_CASE) to "ie",
    )

    /** Common category labels; a provider's one-letter typo should not create a second group. */
    private val KNOWN_LABELS = listOf(
        "General", "Entertainment", "Kids", "News", "Religion", "Lifestyle", "Documentary",
        "Music", "Movies", "Sports", "Shopping", "Education", "Weather", "Drama", "Comedy",
        "Reality", "Cooking", "Travel", "Science", "History", "Adult", "Other",
    )

    private fun canonicalLabel(label: String): String {
        KNOWN_LABELS.firstOrNull { it.equals(label, ignoreCase = true) }?.let { return it }
        if (label.length < 6) return label
        val lower = label.lowercase()
        val best = KNOWN_LABELS.minByOrNull { distance(it.lowercase(), lower) } ?: return label
        val d = distance(best.lowercase(), lower)
        return if (d <= if (best.length >= 10) 2 else 1) best else label
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    cur[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }

    private fun countryWord(group: String): String? =
        COUNTRY_WORDS.firstOrNull { it.first.containsMatchIn(group) }?.second
            ?: COUNTRY_TOKEN.find(group)?.groupValues?.get(1)?.lowercase()
}
