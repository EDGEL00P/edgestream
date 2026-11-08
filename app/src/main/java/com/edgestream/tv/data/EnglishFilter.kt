package com.edgestream.tv.data

/**
 * Decides whether a live channel is English-language, from the labels big providers put on
 * channels and groups: a name prefix ("US| ESPN", "FR| TF1") and a group naming the country
 * ("AM | USA NBC", "EU | UK SPORT", "AS | AUSTRALIA", "24/7 | ENGLISH").
 *
 * The name prefix wins when it names a language or country, so "FR| ..." inside a Canada group
 * is dropped and "US| ..." inside a generic PPV group is kept. Otherwise the group decides.
 * Public lists (iptv-org) group by genre and label nothing, so with neither labelled the country
 * in the tvg-id ("ABC.au@SD") decides. Channels with no country anywhere are dropped: in these
 * lists that is almost always a foreign-language feed.
 */
object EnglishFilter {
    private val ENGLISH_PREFIXES = setOf("US", "USA", "UK", "GB", "CA", "CAN", "AU", "AUS", "NZ", "IE", "EN", "ENG")

    /** Any other 2-4 letter prefix is a country/language tag (FR, DE, AR, EXYU…), i.e. not English. */
    private val NEUTRAL_PREFIXES = setOf("PPV", "VIP", "HD", "FHD", "UHD", "4K", "24/7", "247", "LIVE", "EVENT", "NEW")

    private val PREFIX = Regex("""^\s*\|?\s*([A-Z0-9/]{2,5})\s*[|:]""", RegexOption.IGNORE_CASE)

    // Word boundaries so "UK" doesn't match "UKRAINE".
    private val ENGLISH_GROUP = Regex(
        """\b(USA|US|UK|CANADA|AUSTRALIA|NEW ZEALAND|IRELAND|IRISH|ENGLISH|BRITISH)\b""", RegexOption.IGNORE_CASE,
    )

    /** Groups inside an English-speaking country that are not in English. */
    private val FOREIGN_GROUP = Regex(
        """FRAN[CÇ]AIS|FRENCH|QU[EÉ]BEC|LATINO|ESPA[NÑ]OL|SPANISH|VIX|TELEMUNDO|UNIVISION|INDIA|HINDI|PUNJABI|URDU|TAMIL|ARAB|PORTUGU|ITALIAN|GREEK|POLISH|CHINESE|KOREAN|FILIPINO|VIETNAM""",
        RegexOption.IGNORE_CASE,
    )

    /** A live category worth downloading at all ("AM | USA NBC", "EU | UK SPORT", "24/7 | ENGLISH"). */
    fun isEnglishGroup(group: String): Boolean = !FOREIGN_GROUP.containsMatchIn(group) && ENGLISH_GROUP.containsMatchIn(group)

    private val VOD_TAG = Regex("""^\s*\[([A-Z-]{2,12})]""", RegexOption.IGNORE_CASE)
    private val ADULT = Regex("""ADULT|XXX|\+18|PORN""", RegexOption.IGNORE_CASE)

    /** A film/series category in English: "[EN] COMEDY", "[UK] DRAMA"; not "[DE] FILME" or "[MULTI-LANG] …". */
    fun isEnglishVodCategory(category: String): Boolean {
        if (ADULT.containsMatchIn(category)) return false
        if (FOREIGN_GROUP.containsMatchIn(category)) return false
        val tag = VOD_TAG.find(category)?.groupValues?.get(1)?.uppercase()
        return if (tag != null) tag in ENGLISH_PREFIXES else isEnglishGroup(category)
    }

    fun isEnglish(name: String, group: String, tvgId: String? = null): Boolean {
        val groupForeign = FOREIGN_GROUP.containsMatchIn(group)
        val prefix = PREFIX.find(name)?.groupValues?.get(1)?.uppercase()
        if (prefix != null && prefix !in NEUTRAL_PREFIXES) {
            return prefix in ENGLISH_PREFIXES && !groupForeign
        }
        if (groupForeign) return false
        if (ENGLISH_GROUP.containsMatchIn(group)) return true
        return EpgMatcher.countryOf(tvgId)?.uppercase() in ENGLISH_PREFIXES
    }
}
