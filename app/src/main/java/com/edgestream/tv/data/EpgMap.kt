package com.edgestream.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A prebuilt map of channel name -> (online guide, guide channel id, curated logo), generated
 * offline by tools/epg/build_map.py against the free English guides.
 *
 * A name can map differently per country ("Food Network" exists in the US and the UK), so each
 * key keeps one entry per country and [lookup] picks the one matching the playlist channel.
 * The curated logo (4th element of a map entry) always wins over provider logos.
 */
class EpgMap(val guides: List<String>, private val channels: Map<String, List<Entry>>) {

    data class Entry(val guide: Int, val id: String, val country: String?, val logo: String? = null)

    /**
     * Country of each guide URL, worked out once. [lookup] runs for every channel on every guide
     * refresh, so re-parsing 16 URLs thousands of times per pass is waste.
     */
    private val guideCountries: List<String?> by lazy { guides.map { countryOfGuide(it)?.uppercase() } }

    /** Guide URL and guide channel id for [name], in [country] when known. */
    fun lookup(name: String, country: String? = null): Pair<String, String>? =
        lookupEntry(name, country)?.let { it.first to it.second }

    /** Guide URL, guide channel id and curated logo for [name], in [country] when known. */
    fun lookupEntry(name: String, country: String? = null): Triple<String, String, String?>? {
        // The asset stores ISO-ish codes in upper case ("US", "UK"); the matcher returns lower.
        val want = country?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val entries = channels[key(name)] ?: return null
        val entry = entries.firstOrNull { want != null && it.country?.uppercase() == want }
            ?: entries.firstOrNull { it.country == null || want == null }
            ?: return null
        if (want != null && entry.country != null && entry.country.uppercase() != want) return null
        val url = guides.getOrNull(entry.guide) ?: return null
        // Belt and braces: the entry's own country and the guide's URL must both agree.
        val guideCountry = guideCountries.getOrNull(entry.guide)
        if (want != null && guideCountry != null && guideCountry != want) return null
        return Triple(url, entry.id, entry.logo)
    }

    /** Curated logo for [name], in [country] when known; null when the map has none. */
    fun logo(name: String, country: String? = null): String? = lookupEntry(name, country)?.third

    companion object {
        fun load(context: Context): EpgMap = runCatching {
            context.assets.open("epg_map.json").use { stream ->
                JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                    val guides = ArrayList<String>()
                    val channels = HashMap<String, List<Entry>>()
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "guides" -> { r.beginArray(); while (r.hasNext()) guides += r.nextString(); r.endArray() }
                        "channels" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val k = r.nextName()
                                val entries = ArrayList<Entry>()
                                r.beginArray()
                                while (r.hasNext()) {
                                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                        r.beginArray()
                                        val g = r.nextInt()
                                        val id = r.nextString()
                                        val cc = if (r.hasNext()) r.nextString() else null
                                        val logo = if (r.hasNext()) {
                                            if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                                        } else null
                                        r.endArray()
                                        entries += Entry(g, id, cc, logo)
                                    } else {
                                        // Older two-value form: [guide, id].
                                        val g = r.nextInt()
                                        val id = r.nextString()
                                        entries += Entry(g, id, null)
                                    }
                                }
                                r.endArray()
                                channels[k] = entries
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                    r.endObject()
                    EpgMap(guides, channels)
                }
            }
        }.getOrElse { EpgMap(emptyList(), emptyMap()) }

        /** "epg_ripper_UK1.xml.gz" -> uk, "epg_US.xml.gz" -> us, "i.mjh.nz/au/Sydney" -> au. */
        fun countryOfGuide(url: String): String? {
            val u = url.lowercase()
            fun code(tag: String) = Regex("""(?:^|[/_])${tag}\d*\.xml""").containsMatchIn(u)
            return when {
                Regex("""(?:^|[/_])us(?:_locals)?\d*\.xml""").containsMatchIn(u) -> "us"
                code("uk") || code("gb") -> "uk"
                code("ca") -> "ca"
                code("au") -> "au"
                code("nz") -> "nz"
                code("ie") -> "ie"
                "/au/" in u -> "au"
                "/nz/" in u -> "nz"
                else -> null
            }
        }

        private val PREFIX = Regex("""^\s*\|?\s*([A-Z0-9/]{2,5})\s*[|:]""", RegexOption.IGNORE_CASE)
        private val TAGS = Regex("""\[[^]]*]""")
        private val QUALITY = Regex(
            """\b(UHD|FHD|HD|SD|HEVC|H265|4K|1080P?|720P?|50 ?FPS|60 ?FPS|BACKUP|RAW|VIP|NEW|ᴴᴰ|ᴿᴬᵂ)\b""", RegexOption.IGNORE_CASE,
        )
        private val SPACES = Regex("""\s+""")

        /** Must stay identical to key() in tools/epg/build_map.py. */
        fun key(name: String): String {
            var n = PREFIX.replaceFirst(name, "")
            n = TAGS.replace(n, " ")
            n = QUALITY.replace(n, " ")
            return SPACES.replace(n, " ").trim().lowercase()
        }
    }
}

/**
 * Listings for channels no guide publishes, built from what the provider writes in the channel
 * name: "ESPN+ 004 [Soccer: Eldense vs. Granada (2026-11-01 07:50:00)]" is an event at that time,
 * "DAZN 08 [EVENT ONLY]" is an idle event slot, "24/7-EN - BATMAN 1966" loops one show.
 */
object NameListings {
    /** Section dividers some providers put in the list, e.g. "✦●✦ UK: SKY CHANNELS ✦●✦". */
    private val HEADER = Regex("""✦|●|^\s*[#=*-]{3,}""")
    fun isHeader(name: String) = HEADER.containsMatchIn(name)

    private val DATED = Regex("""\[(?:live:\s*)?(.+?)\s*\((\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\)\s*]""", RegexOption.IGNORE_CASE)
    private val AT_DATE = Regex("""\[(.+?)\s*@\s*([A-Z][a-z]{2}) (\d{1,2}) (\d{1,2}):(\d{2})\s*([AP]M)\s*]""", RegexOption.IGNORE_CASE)
    private val AT_TIME = Regex("""\[\s*(\d{1,2})(?::(\d{2}))?\s*([AP]M)\s+(.+?)\s*]""", RegexOption.IGNORE_CASE)
    private val EMPTY_SLOT = Regex("""\[\s*EVENT ONLY\s*]|\(2098-|\[\s*]|NO EVENT""", RegexOption.IGNORE_CASE)
    private val LOOP_GROUP = Regex("""CINEMANIA|PLAY KIDZ|24/7|ON-DEMAND|SEREIS|SERIES""", RegexOption.IGNORE_CASE)
    private val LOOP_PREFIX = Regex("""^\s*(24/7(?:-[A-Z]{2})?|[A-Z]{2})\s*[|:\-]\s*(?:24/7\s*[|:\-]?\s*)?""", RegexOption.IGNORE_CASE)
    private val QUALITY = Regex("""\b(UHD|FHD|HD|SD|HEVC|4K)\b""", RegexOption.IGNORE_CASE)

    /** Provider event times are US Eastern (their "2:55AM ET" labels match the bracketed times). */
    private val EASTERN: ZoneId = ZoneId.of("America/New_York")
    private const val HOUR = 3_600_000L

    /** An event channel with nothing booked: no programme and nothing to play. */
    fun isIdleSlot(name: String) = EMPTY_SLOT.containsMatchIn(name) && event(name) == null

    fun isLoop(ch: ChannelEntity) = "24/7" in ch.name || LOOP_GROUP.containsMatchIn(ch.groupName)

    /** Programmes for [ch] when its name carries a schedule, or null when it doesn't. */
    fun programmes(ch: ChannelEntity, sourceId: Long, epgId: String, now: Long): List<ProgrammeEntity>? {
        fun p(start: Long, stop: Long, title: String, desc: String?) =
            ProgrammeEntity(sourceId = sourceId, epgId = epgId, start = start, stop = stop, title = title, description = desc, category = null)

        event(ch.name)?.let { (title, start) ->
            val stop = start + 3 * HOUR
            val dayStart = now - 12 * HOUR
            return buildList {
                if (start > dayStart) add(p(dayStart, start, "Up next: $title", "Starts ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(start)}."))
                add(p(start, stop, title, "Live event."))
                add(p(stop, maxOf(stop, now) + 24 * HOUR, "No event scheduled", "This is an event channel; it only carries the event above."))
            }
        }
        if (EMPTY_SLOT.containsMatchIn(ch.name)) {
            return listOf(p(now - 12 * HOUR, now + 7 * 24 * HOUR, "No event scheduled", "An event channel. Programmes appear here when an event is booked."))
        }
        if (isLoop(ch)) {
            // Two-hour slots on the clock, like a real listing, rather than one week-long block.
            val show = showName(ch.name)
            val first = (now - 12 * HOUR) / (2 * HOUR) * (2 * HOUR)
            return (0 until 7 * 12 + 6).map { i ->
                val start = first + i * 2 * HOUR
                p(start, start + 2 * HOUR, show, "Non-stop $show, around the clock.")
            }
        }
        return null
    }

    /** "24/7-EN - BATMAN 1966" -> "Batman 1966"; "US| PLAY KIDZ SPIDEY HD" -> "Spidey". */
    fun showName(name: String): String {
        var n = LOOP_PREFIX.replaceFirst(name, "")
        n = n.replace(Regex("""\[[^]]*]"""), " ").replace(QUALITY, " ")
            .replace(Regex("""^(PLAY KIDZ|CINEMANIA)\s+""", RegexOption.IGNORE_CASE), "")
        n = n.trim(' ', '-', '|', ':').replace(Regex("""\s+"""), " ")
        // Provider names are often all caps; title-case those, leave mixed case alone.
        return if (n == n.uppercase()) n.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } } else n
    }

    /** When an event channel's named event starts ("… (2026-11-08 21:30:00)]"), or null. */
    fun eventStart(name: String): Long? = event(name)?.second

    private fun event(name: String): Pair<String, Long>? {
        DATED.find(name)?.let { m ->
            val t = LocalDateTime.parse(m.groupValues[2], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            if (t.year > 2090) return null // "(2098-12-31 …)" marks an empty slot
            return clean(m.groupValues[1]) to t.atZone(EASTERN).toInstant().toEpochMilli()
        }
        AT_DATE.find(name)?.let { m ->
            val month = runCatching { java.time.Month.from(DateTimeFormatter.ofPattern("MMM", Locale.US).parse(m.groupValues[2].lowercase().replaceFirstChar { it.uppercase() })) }.getOrNull() ?: return null
            val date = LocalDate.of(Year.now(EASTERN).value, month, m.groupValues[3].toInt())
            return clean(m.groupValues[1]) to date.atTime(time(m.groupValues[4], m.groupValues[5], m.groupValues[6])).atZone(EASTERN).toInstant().toEpochMilli()
        }
        AT_TIME.find(name)?.let { m ->
            val today = LocalDate.now(EASTERN)
            return clean(m.groupValues[4]) to today.atTime(time(m.groupValues[1], m.groupValues[2].ifEmpty { "0" }, m.groupValues[3])).atZone(EASTERN).toInstant().toEpochMilli()
        }
        return null
    }

    private fun time(h: String, m: String, ampm: String): LocalTime {
        val hour = h.toInt() % 12 + if (ampm.equals("PM", true)) 12 else 0
        return LocalTime.of(hour, m.toInt())
    }

    /** "Soccer: Getafe vs. Sevilla (Getafe vs. Sevilla (ESP))" -> "Soccer: Getafe vs. Sevilla"; "_" -> "·". */
    private fun clean(s: String) = s.replace(Regex("""\s*\(.*$"""), "").replace(" _ ", " · ").replace('_', ' ').trim()
}
