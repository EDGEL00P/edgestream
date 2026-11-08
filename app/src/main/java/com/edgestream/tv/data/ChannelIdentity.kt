package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.io.Reader

/**
 * What a provider channel really is: one row of the iptv-org channel database
 * (assets/channel_catalog.json, built by tools/channels/build_catalog.py). Logos, grouping,
 * numbering and name search all read this one identity instead of each guessing from the name.
 */
data class ChannelIdentity(
    /** iptv-org id, e.g. "FoxLeague.au": also the tvg-id iptv-org playlists and many guides use. */
    val id: String,
    val name: String,
    /** iptv-org country code: ISO, except "UK" for the United Kingdom. */
    val country: String,
    /** iptv-org categories: "sports", "news", "kids", "movies", "documentary", "music"… */
    val categories: List<String>,
    val logo: String?,
    val network: String?,
    /** iptv-org feed id for a regional or local version ("WTLV" of NBC.us), null for the main feed. */
    val feed: String? = null,
) {
    /** How a channel row stores it: "FoxLeague.au", or "NBC.us@WTLV" for a feed. */
    val ref: String get() = if (feed == null) id else "$id@$feed"
}

/**
 * Resolves any provider's channels (M3U or Xtream, any naming style) to [ChannelIdentity] from
 * what every provider has: the name, the tvg-id and the group. It never guesses: a name that
 * fits two catalogue channels resolves to nothing.
 */
/** A regional or local version of a catalogue channel, as iptv-org lists it. */
internal data class CatalogueFeed(val channel: String, val id: String, val name: String, val alts: List<String>)

class ChannelIdentities internal constructor(rows: List<Pair<ChannelIdentity, List<String>>>, feeds: List<CatalogueFeed> = emptyList()) {
    private val byId = HashMap<String, ChannelIdentity>(rows.size)
    /** country → name key → every identity carrying that name (or alt name). */
    private val byName = HashMap<String, HashMap<String, MutableSet<ChannelIdentity>>>()
    /** Name key → identities in every country, for playlists that never say which country. */
    private val anywhere = HashMap<String, MutableSet<ChannelIdentity>>()
    /** US/CA call sign ("WTLV") → every network carrying a feed from that station. */
    private val byCallSign = HashMap<String, MutableList<ChannelIdentity>>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    init {
        for ((identity, alts) in rows) {
            byId[identity.id.lowercase()] = identity
            val names = byName.getOrPut(identity.country) { HashMap() }
            for (n in listOf(identity.name) + alts) for (k in keys(n)) {
                names.getOrPut(k) { LinkedHashSet() } += identity
                anywhere.getOrPut(k) { LinkedHashSet() } += identity
            }
            STATION.matchEntire(identity.name.trim())?.let { m ->
                // "WVTM-DT" is the main channel; "WVTM-DT2" is a subchannel and never stands in for it.
                if (m.groupValues[3].isEmpty()) byCallSign.getOrPut(m.groupValues[1]) { ArrayList() } += identity
            }
        }
        for (f in feeds) {
            val identity = byId[f.channel.lowercase()]?.copy(feed = f.id) ?: continue
            val names = byName.getOrPut(identity.country) { HashMap() }
            for (n in listOf(f.name) + f.alts) {
                STATION.matchEntire(n.trim())?.let { m -> if (m.groupValues[3].isEmpty()) byCallSign.getOrPut(m.groupValues[1]) { ArrayList() } += identity }
                    ?: BARE_STATION.matchEntire(n.trim())?.let { m -> byCallSign.getOrPut(m.groupValues[1]) { ArrayList() } += identity }
                // "CTV" + "North Bay", "BBC One" + "North East & Cumbria".
                for (k in keys("${identity.name} $n")) {
                    names.getOrPut(k) { LinkedHashSet() } += identity
                    anywhere.getOrPut(k) { LinkedHashSet() } += identity
                }
            }
        }
    }

    val size get() = byId.size

    /** The identity a channel row stored ([ChannelIdentity.ref]). */
    fun get(ref: String?): ChannelIdentity? {
        if (ref == null) return null
        val main = byId[ref.substringBefore('@').lowercase()] ?: return null
        return if ('@' in ref) main.copy(feed = ref.substringAfter('@')) else main
    }

    /**
     * The identity for a provider channel, or null when the catalogue has no single answer.
     * [rawName] is the provider's own name ("US| NBC 13 (WVTM) BIRMINGHAM", "Fox League HD",
     * "Tiny Pop (1080p)"); [group] its group ("AM | USA SPORTS", "Australia").
     */
    fun resolve(rawName: String, tvgId: String?, group: String? = null): ChannelIdentity? {
        val hit = cache.getOrPut("$rawName\u0001${tvgId.orEmpty()}\u0001${group.orEmpty()}") { find(rawName, tvgId, group) ?: NONE }
        return hit as? ChannelIdentity
    }

    private fun find(rawName: String, tvgId: String?, group: String?): ChannelIdentity? {
        // iptv-org playlists (and some providers) carry the catalogue id itself.
        tvgId?.lowercase()?.substringBefore('@')?.let { byId[it] }?.let { return it }
        val country = (EpgMatcher.countryOf(rawName) ?: EpgMatcher.countryOf(tvgId))?.let(::iso)
            // A name only names its country in a tag ("Tiny Pop (United Kingdom)"); loose words
            // like "us" or "can" in a title are not countries. Groups ("AM | USA SPORTS") are.
            ?: COUNTRY_TAG.find(rawName)?.let { countryNamed(it.value) } ?: group?.let(::countryNamed)
        if (country == null || country == "US" || country == "CA") {
            val sign = CALL_SIGN.find(rawName)?.groupValues?.get(1) ?: STATION_IN_NAME.find(rawName)?.groupValues?.get(1)
            sign?.let { byCallSign[it.uppercase()] }?.let { stations -> station(rawName, stations)?.let { return it } }
            // "ABC 16 (WNEP) WILKES BARRE": the provider names the network before the call sign,
            // and that beats catalogue feed lists, which file some stations under the wrong one.
            if (sign != null) NETWORK_BEFORE_SIGN.find(EpgMap.key(rawName))?.groupValues?.get(1)?.let { net ->
                keys(net).firstNotNullOfOrNull { k -> byName[country ?: "US"]?.get(k)?.singleOrNull() }?.let { return it.copy(feed = sign.uppercase()) }
            }
        }
        val names = if (country != null) byName[country] ?: return null else anywhere
        for (k in keys(rawName)) {
            val found = names[k] ?: continue
            // Two channels share the name ("Fox Sports" the network and a regional feed, or
            // "Quest" in the UK and the US when no country is given): no answer beats a wrong one.
            val channels = found.groupBy { it.id }
            return channels.values.singleOrNull()?.let { same -> same.firstOrNull { it.feed == null } ?: same.singleOrNull() }
        }
        return null
    }

    /**
     * One station carries several networks on its subchannels (WTLV: NBC, Antenna TV, Roar), so
     * the provider's own network word picks the one ("NBC 12 (WTLV) JACKSONVILLE" → NBC).
     */
    private fun station(rawName: String, stations: List<ChannelIdentity>): ChannelIdentity? {
        val distinct = stations.distinctBy { it.id }
        // A station listed as its own channel ("WVTM-DT") is that channel.
        distinct.singleOrNull { it.feed == null }?.let { return it }
        // A feed is one network on the station: only the network the provider itself names counts,
        // so "7NEWS BOSTON (WHDH)" never lands on whatever subchannel the catalogue files there.
        val text = rawName.lowercase().filter { it.isLetterOrDigit() }
        return distinct.filter { s -> keys(s.name).any { it.length >= 2 && text.contains(it) } }
            .maxByOrNull { s -> keys(s.name).maxOf { it.length } }
    }

    companion object {
        private val NONE = Any()
        private val BARE_STATION = Regex("""([KWC][A-Z]{2,3})""")
        private val STATION = Regex("""([KWC][A-Z]{2,3})-(DT|TV|LD|CD|CA|LP)(\d*)""")
        /** "(WTLV)" or "(WCIV-DT)" in a provider name. */
        private val CALL_SIGN = Regex("""\(([KW][A-Z]{2,3})(?:-(?:DT|TV|LD|CD))?\)""")
        /** The network words ahead of "(WNEP)": "ABC 16 (WNEP)" → "ABC", "PBS (WABE)" → "PBS". */
        private val NETWORK_BEFORE_SIGN = Regex("""^([a-z][a-z ]*?)\s*\d*\s*\(""", RegexOption.IGNORE_CASE)
        private val STATION_IN_NAME =Regex("""\b([KWC][A-Z]{2,3})-(?:DT|TV)\b""")
        private val VARIANT = Regex("""\((?:east|west|pacific|backup|alt)\)|\b(?:east|west|pacific)\s*$""", RegexOption.IGNORE_CASE)
        private val GENERIC_TAIL = Regex("""(?:tv|channel|network)$""")
        private val LEADING_ZERO = Regex("""\b0+(\d)""")
        /** Superscript quality marks ("ᴴᴰ", "ᵁᴴᴰ") that word-boundary patterns can't see. */
        private val SUPERSCRIPT = Regex("""[ᴬ-ᵪ⁰-₟]+""")
        private val RESOLUTION = Regex("""\(\s*\d{3,4}[pi]\s*\)""", RegexOption.IGNORE_CASE)
        /** "+1" timeshift: the same channel an hour later. "Sky Sport+ 24" (space after +) stays. */
        private val TIMESHIFT = Regex("""(?<=\s)\+\d\b""")
        private val COUNTRY_TAG = Regex("""\((?:united states|united kingdom|canada|australia|new zealand|ireland)\)""", RegexOption.IGNORE_CASE)
        private val COUNTRY_WORDS = listOf(
            "US" to Regex("""\b(?:usa|us|united states|america)\b""", RegexOption.IGNORE_CASE),
            "UK" to Regex("""\b(?:uk|gb|united kingdom|britain|england|scotland|wales)\b""", RegexOption.IGNORE_CASE),
            "CA" to Regex("""\b(?:canada|can)\b""", RegexOption.IGNORE_CASE),
            "AU" to Regex("""\b(?:australia|aus|au)\b""", RegexOption.IGNORE_CASE),
            "NZ" to Regex("""\b(?:new zealand|nz)\b""", RegexOption.IGNORE_CASE),
            "IE" to Regex("""\b(?:ireland|irl|ie)\b""", RegexOption.IGNORE_CASE),
        )

        /** iptv-org's codes: "UK" for the United Kingdom, ISO for the rest. */
        fun iso(cc: String) = cc.uppercase().let { if (it == "GB") "UK" else it }

        /** The one country a group or name spells out ("AM | USA SPORTS", "Tiny Pop (United Kingdom)"). */
        private fun countryNamed(text: String): String? =
            COUNTRY_WORDS.filter { it.second.containsMatchIn(text) }.singleOrNull()?.first

        /**
         * Name keys, most specific first: "AU| FOX LEAGUE HD" -> "foxleague"; "Comedy Central
         * (West)" -> "comedycentral"; "Sky Sports 02" -> "skysports2"; "Alibi +1" -> "alibi". A
         * trailing TV/Channel/Network is optional on both sides ("COZI TV" == "Cozi").
         */
        internal fun keys(name: String): List<String> {
            var n = SUPERSCRIPT.replace(name, " ")
            n = RESOLUTION.replace(n, " ")
            n = COUNTRY_TAG.replace(n, " ")
            n = TIMESHIFT.replace(n, " ")
            n = EpgMap.key(n)
            n = CALL_SIGN.replace(n, " ")
            n = VARIANT.replace(n, " ")
            n = LEADING_ZERO.replace(n, "$1").replace("&", "and").replace("+", "plus")
            val full = n.lowercase().filter { it.isLetterOrDigit() }
            if (full.isEmpty()) return emptyList()
            val bare = GENERIC_TAIL.replace(full, "")
            return if (bare.length >= 3 && bare != full) listOf(full, bare) else listOf(full)
        }

        private val shared = kotlinx.coroutines.CompletableDeferred<ChannelIdentities>()

        /**
         * The app-wide catalogue once loaded (a 1 MB asset, parsed off the main thread at start).
         * Null only in the first moments after launch, before the lineup itself has loaded.
         */
        @Volatile var current: ChannelIdentities? = null
            private set

        fun init(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
            if (shared.isCompleted) return
            // Parse the 1 MB catalogue off the shared CPU pool: it runs once at startup and the lineup
            // awaits it, so keeping it off Default avoids starving channel cleaning.
            scope.launch(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)) {
                val loaded = runCatching {
                    context.assets.open("channel_catalog.json").use { parse(InputStreamReader(it, Charsets.UTF_8)) }
                }.getOrElse { e ->
                    android.util.Log.w("EdgeStream", "channel catalogue unavailable", e)
                    ChannelIdentities(emptyList())
                }
                current = loaded
                shared.complete(loaded)
            }
        }

        suspend fun await(): ChannelIdentities = shared.await()

        internal fun parse(reader: Reader): ChannelIdentities {
            val root = org.json.JSONObject(reader.readText())
            val rows = root.getJSONArray("channels")
            val feeds = root.optJSONArray("feeds") ?: org.json.JSONArray()
            fun list(a: org.json.JSONArray) = List(a.length()) { a.getString(it) }
            fun opt(a: org.json.JSONArray, i: Int) = if (a.isNull(i)) null else a.getString(i)
            return ChannelIdentities(List(rows.length()) { i ->
                val r = rows.getJSONArray(i)
                ChannelIdentity(r.getString(0), r.getString(1), r.getString(3), list(r.getJSONArray(4)), opt(r, 5), opt(r, 6)) to list(r.getJSONArray(2))
            }, List(feeds.length()) { i ->
                val f = feeds.getJSONArray(i)
                CatalogueFeed(f.getString(0), f.getString(1), f.getString(2), list(f.getJSONArray(3)))
            })
        }
    }
}
