package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * The number a viewer would actually press on their TV remote, where the country's own
 * numbering is known:
 *
 *  - UK Freeview (101 BBC One, 103 ITV1, 401 Sky Sports Main Event)
 *  - Australia Freeview (2 ABC, 3 SBS, 7/9/10) and Foxtel sport (508 ESPN, 526 Sky Racing 1)
 *  - New Zealand Freeview / Sky (1 TVNZ 1, 51 Sky Sport 1)
 *  - Ireland (1 RTÉ One, 3 Virgin Media One)
 *  - US cable (206 ESPN, 219 FS1, 202 CNN — the common DirecTV positions)
 *  - Canada (400 TSN1, 300 Sportsnet)
 *
 * Channels the table does not know keep their provider number, so nothing disappears.
 */
object ChannelNumbers {
    private data class Rule(val pattern: Regex, val number: Int)

    private fun r(pattern: String, number: Int) = Rule(Regex(pattern, RegexOption.IGNORE_CASE), number)

    private val PRE = Regex("""^\s*\|?\s*(?:US|UK|AU|NZ|CA|IE)\s*[|:\-]\s*""", RegexOption.IGNORE_CASE)
    private val NOISE = Regex(
        """\b(?:HD|FHD|UHD|4K|SD|HEVC|H\.?265|50FPS|60FPS|BACKUP|BK|RAW|VIP|FEED|ALT|EAST|WEST|PACIFIC|MOUNTAIN|CENTRAL)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PUNCT = Regex("""[^a-z0-9]+""")

    // "+" is a word here: "ESPN+ 005" is the ESPN+ service, never ESPN's own channel number.
    private fun norm(raw: String): String =
        PUNCT.replace(NOISE.replace(PRE.replace(raw, ""), " ").lowercase().replace("+", " plus "), " ").trim()

    private val UK = listOf(
        r("""\bbbc\s*(?:one|1)\b""", 101),
        r("""\bbbc\s*(?:two|2)\b""", 102),
        r("""\bitv\s*1\b""", 103),
        r("""\bchannel\s*4\b""", 104),
        r("""\bchannel\s*5\b|\bfive\b""", 105),
        r("""\bitv\s*2\b""", 106),
        r("""\bbbc\s*(?:four|4)\b""", 9),
        r("""\bitv\s*3\b""", 10),
        r("""\bitv\s*4\b""", 26),
        r("""\bsky\s*mix\b""", 11),
        r("""\btlc\b""", 12),
        r("""\be4\b""", 13),
        r("""\bfilm\s*4\b""", 14),
        r("""\bquest\b""", 17),
        r("""\bmore\s*4\b""", 18),
        r("""\b(?:u\s*&\s*)?dave\b""", 19),
        r("""\bu\s*&\s*drama\b|\bdrama\b""", 20),
        r("""\b5\s*usa\b""", 21),
        r("""\bbbc\s*(?:three|3)\b""", 23),
        r("""\bu\s*&\s*w\b""", 25),
        r("""\b(?:u\s*&\s*)?yesterday\b""", 27),
        r("""\be4\s*extra\b""", 31),
        r("""\b5\s*star\b""", 32),
        r("""\b5\s*drama\b|\b5\s*select\b""", 33),
        r("""\bgreat\s*tv\b""", 34),
        r("""\bsky\s*arts\b""", 36),
        r("""\bdmax\b""", 39),
        r("""\bquest\s*red\b""", 40),
        r("""\blegend\b""", 41),
        r("""\blifetime\b""", 114),
        r("""\bgreat\s*action\b""", 42),
        r("""\bfood\s*network\b""", 43),
        r("""\breally\b""", 44),
        r("""\b5\s*action\b""", 46),
        r("""\bchallenge\b""", 48),
        r("""\b4\s*seven\b""", 49),
        r("""\bgreat\s*mystery\b""", 50),
        r("""\bgreat\s*christmas\b""", 52),
        r("""\bthat'?s\s*tv\b""", 56),
        r("""\b(?:u\s*&\s*)?eden\b""", 57),
        r("""\bgreat\s*movies\b""", 62),
        r("""\bblaze\b""", 64),
        r("""\brewind\s*tv\b""", 81),
        r("""\btalking\s*pictures\b""", 82),
        r("""\btogether\s*tv\b""", 83),
        r("""\bpbs\s*america\b""", 84),
        r("""\bcbbc\b""", 201),
        r("""\bcbeebies\b""", 202),
        r("""\btiny\s*pop\b""", 206),
        r("""\bbbc\s*news\b""", 231),
        r("""\bbbc\s*parliament\b""", 232),
        r("""\bsky\s*news\b""", 233),
        r("""\bgb\s*news\b""", 236),
        // Sky / TNT sport EPG.
        r("""\bsky\s*sports?\s*main\s*event\b|\bsky\s*sports?\s*1\b""", 401),
        r("""\bsky\s*sports?\s*premier\s*league\b|\bsky\s*sports?\s*2\b""", 402),
        r("""\bsky\s*sports?\s*football\b|\bsky\s*sports?\s*3\b""", 403),
        r("""\bsky\s*sports?\s*(?:\+|plus)\b""", 404),
        r("""\bsky\s*sports?\s*cricket\b""", 405),
        r("""\bsky\s*sports?\s*golf\b""", 406),
        r("""\bsky\s*sports?\s*f1\b""", 407),
        r("""\bsky\s*sports?\s*action\b""", 408),
        r("""\bsky\s*sports?\s*news\b""", 409),
        r("""\bsky\s*sports?\s*mix\b""", 410),
        r("""\btnt\s*sports?\s*1\b""", 411),
        r("""\btnt\s*sports?\s*2\b""", 412),
        r("""\btnt\s*sports?\s*3\b""", 413),
        r("""\btnt\s*sports?\s*4\b""", 414),
        r("""\beurosport\s*1\b""", 415),
        r("""\beurosport\s*2\b""", 416),
        // Generic main-channel matches last so the multichannels above win.
        r("""\bitv\b""", 103),
        r("""\bbbc\s*one\b|\bbbc\s*1\b""", 101),
        r("""\bbbc\s*scotland\b""", 109),
        r("""\bbbc\s*alba\b""", 110),
        r("""\bitv\s*be\b""", 111),
        r("""\bstv\b|\butv\b|\bs4c\b""", 103),
    )

    private val AU = listOf(
        r("""\babc\s*news\b""", 24),
        r("""\babc\s*kids\b|\babc\s*family\b""", 22),
        r("""\babc\s*entertains\b|\babc\s*me\b|\babc\s*tv\s*plus\b""", 23),
        r("""\babc\s*tv\b|\babc\s*1\b""", 2),
        r("""\babc\b""", 2),
        r("""\bsbs\s*viceland\b|\bsbs\s*2\b""", 31),
        r("""\bsbs\s*world\s*movies\b""", 32),
        r("""\bsbs\s*food\b""", 33),
        r("""\bnitv\b""", 34),
        r("""\bsbs\s*world\s*watch\b""", 35),
        r("""\bsbs\b""", 3),
        r("""\b7\s*two\b|\bseven\s*two\b""", 72),
        r("""\b7\s*mate\b|\bseven\s*mate\b""", 74),
        r("""\b7\s*bravo\b|\bseven\s*bravo\b""", 75),
        r("""\b7\s*flix\b|\bseven\s*flix\b""", 76),
        r("""\bracing\s*\.?\s*com\b""", 78),
        r("""\bseven\b|\b7\b""", 7),
        r("""\b9\s*gem\b|\bnine\s*gem\b""", 92),
        r("""\b9\s*go\b|\bnine\s*go\b""", 93),
        r("""\b9\s*life\b|\bnine\s*life\b""", 94),
        r("""\b9\s*rush\b|\bnine\s*rush\b""", 96),
        r("""\bnine\b|\b9\b""", 9),
        r("""\b10\s*comedy\b|\b10\s*peach\b|\bten\s*comedy\b""", 11),
        r("""\b10\s*drama\b|\b10\s*bold\b|\bten\s*drama\b""", 12),
        r("""\bnickelodeon\b|\bnick\b""", 13),
        r("""\b10\b|\bten\b""", 10),
        // Foxtel sport.
        r("""\bfox\s*cricket\b""", 501),
        r("""\bfox\s*league\b""", 502),
        r("""\bfox\s*footy\b""", 504),
        r("""\bfox\s*sports\s*503\b""", 503),
        r("""\bfox\s*sports\s*505\b""", 505),
        r("""\bfox\s*sports\s*506\b""", 506),
        r("""\bfox\s*sports\s*507\b""", 507),
        r("""\bespn\s*2\b""", 509),
        r("""\bespn\b(?!\s*plus)""", 508),
        r("""\bbein\s*sports\s*1\b""", 510),
        r("""\bbein\s*sports\s*2\b""", 511),
        r("""\bbein\s*sports\s*3\b""", 512),
        r("""\bsky\s*racing\s*1\b|\bsky\s*thoroughbred\b""", 526),
        r("""\bsky\s*racing\s*2\b""", 527),
        r("""\bsky\s*racing\s*3\b""", 528),
    )

    private val NZ = listOf(
        r("""\btvnz\s*(?:1|one)\b""", 1),
        r("""\btvnz\s*(?:2|two)\b""", 2),
        r("""\bthree\b""", 3),
        r("""\bbravo\b""", 4),
        r("""\bprime\b""", 5),
        r("""\bvibe\b""", 6),
        r("""\btvnz\s*duke\b|\bduke\b""", 7),
        r("""\beden\b""", 8),
        r("""\brush\b""", 9),
        r("""\bsky\s*open\b""", 15),
        r("""\bsky\s*sport\s*select\b""", 50),
        r("""\bsky\s*sport\s*1\b""", 51),
        r("""\bsky\s*sport\s*2\b""", 52),
        r("""\bsky\s*sport\s*3\b""", 53),
        r("""\bsky\s*sport\s*4\b""", 54),
        r("""\bsky\s*sport\s*5\b""", 55),
        r("""\bsky\s*sport\s*6\b""", 56),
        r("""\bsky\s*sport\s*7\b""", 57),
        r("""\bsky\s*sport\s*8\b""", 58),
        r("""\bsky\s*sport\s*9\b""", 59),
    )

    private val IE = listOf(
        r("""\brte\s*(?:one|1)\b""", 1),
        r("""\brte\s*2\b|\brte2\b""", 2),
        r("""\bvirgin\s*media\s*one\b""", 3),
        r("""\bvirgin\s*media\s*two\b""", 4),
        r("""\btg\s*4\b""", 5),
        r("""\bvirgin\s*media\s*three\b""", 6),
        r("""\brte\s*news\b""", 21),
        r("""\brte\s*jr\b|\brtejr\b""", 22),
        r("""\boireachtas\b""", 23),
    )

    private val US = listOf(
        r("""\bespn\s*2\b""", 209),
        r("""\bespnu\b""", 208),
        r("""\bespn\s*news\b|\bespnews\b""", 207),
        r("""\bespn\b(?!\s*plus)""", 206),
        r("""\bfs\s*1\b|\bfox\s*sports\s*1\b""", 219),
        r("""\bfs\s*2\b|\bfox\s*sports\s*2\b""", 220),
        r("""\bnba\s*tv\b""", 216),
        r("""\bmlb\s*network\b""", 213),
        r("""\bnhl\s*network\b""", 215),
        r("""\bnfl\s*network\b""", 212),
        r("""\bgolf\s*channel\b""", 218),
        r("""\btennis\s*channel\b""", 217),
        r("""\bcbs\s*sports\b""", 221),
        r("""\bsec\s*network\b""", 611),
        r("""\bacc\s*network\b""", 612),
        r("""\bbig\s*ten\s*network\b""", 610),
        r("""\bcnn\b""", 202),
        r("""\bhln\b|\bheadline\s*news\b""", 204),
        r("""\bmsnbc\b""", 356),
        r("""\bfox\s*news\b""", 360),
        r("""\bcnbc\b""", 355),
        r("""\busa\s*network\b""", 242),
        r("""\btnt\b""", 245),
        r("""\btbs\b""", 247),
        r("""\bfxx\b""", 259),
        r("""\bfx\b""", 248),
        r("""\bamc\b""", 254),
        r("""\bbravo\b""", 237),
        r("""\bsyfy\b""", 244),
        r("""\boxygen\b""", 251),
        r("""\bhbo\b""", 501),
        r("""\bshowtime\b""", 545),
        r("""\bstarz\b""", 525),
        r("""\bcinemax\b""", 515),
    )

    private val CA = listOf(
        r("""\btsn\s*1\b""", 400),
        r("""\btsn\s*2\b""", 401),
        r("""\btsn\s*3\b""", 402),
        r("""\btsn\s*4\b""", 403),
        r("""\btsn\s*5\b""", 404),
        r("""\bsportsnet\s*one\b""", 301),
        r("""\bsportsnet\s*360\b""", 302),
        r("""\bsportsnet\s*world\b""", 303),
        r("""\bsportsnet\b""", 300),
    )

    private val TABLES = mapOf(
        "UK" to UK, "Australia" to AU, "New Zealand" to NZ,
        "Ireland" to IE, "USA" to US, "Canada" to CA,
    )

    private val cache = ConcurrentHashMap<String, Int>()

    /** TV-remote number for [channel]; provider number when the country table has no match. */
    fun lcn(channel: ChannelEntity): Int =
        cache.getOrPut("${channel.key}|${channel.name}|${channel.identity}") { compute(channel) }

    private fun compute(channel: ChannelEntity): Int {
        val table = TABLES[ChannelTaxonomy.country(channel)] ?: return channel.number
        val text = norm(channel.name)
        table.firstOrNull { it.pattern.containsMatchIn(text) }?.let { return it.number }
        // An odd provider name ("FOX LEAGUE 502 HD") still gets its real number through the
        // catalogue's name for it. A local feed's network name would give the network's slot.
        val known = ChannelIdentities.current?.get(channel.identity)?.takeIf { it.feed == null }?.let { norm(it.name) }
        return known?.let { k -> table.firstOrNull { it.pattern.containsMatchIn(k) }?.number } ?: channel.number
    }

    /**
     * Tie-break for duplicate feeds of one channel: prefer the plain HD feed, then the plain
     * feed, over +1/backup/VIP copies sharing the same LCN.
     */
    fun rank(channel: ChannelEntity): Int {
        val n = channel.name.lowercase()
        var score = when {
            "+1" in n -> 100
            "backup" in n || " bk" in n || "[bk]" in n -> 50
            "raw" in n || "vip" in n || "feed" in n -> 20
            else -> 0
        }
        if ("4k" in n) score -= 3 else if ("fhd" in n || "1080" in n) score -= 2 else if ("hd" in n) score -= 1
        return score
    }
}
