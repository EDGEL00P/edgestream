package com.edgestream.tv.data.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class XmltvProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val subtitle: String? = null,
    val year: Int? = null,
    val rating: String? = null,
)

/**
 * Single-pass streaming XMLTV reader.
 *
 * XMLTV files list every <channel> before the first <programme>, so [onChannels] fires once
 * with each channel's display names before programmes start arriving. That lets the caller
 * resolve playlist channels that have no tvg-id by name, then decide which programmes to keep.
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        onChannels: (Map<String, List<String>>) -> Unit,
        accept: (channelId: String, start: Long, stop: Long) -> Boolean,
        onProgramme: (XmltvProgramme) -> Unit,
        onChannelIcons: (Map<String, String>) -> Unit = {},
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        parseWith(parser, onChannels, accept, onProgramme, onChannelIcons)
    }

    /** Core pull loop, split out so unit tests can drive it with a fake parser (android.util.Xml is a stub on the JVM). */
    internal fun parseWith(
        parser: XmlPullParser,
        onChannels: (Map<String, List<String>>) -> Unit,
        accept: (channelId: String, start: Long, stop: Long) -> Boolean,
        onProgramme: (XmltvProgramme) -> Unit,
        onChannelIcons: (Map<String, String>) -> Unit = {},
    ) {
        val channels = LinkedHashMap<String, MutableList<String>>()
        val icons = LinkedHashMap<String, String>()
        var channelsReported = false
        fun reportChannels() {
            if (!channelsReported) { onChannels(channels); onChannelIcons(icons); channelsReported = true }
        }
        var currentChannel: String? = null

        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var keep = false
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var subtitle: String? = null
        var year: Int? = null
        var rating: String? = null
        var inRating = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> currentChannel = parser.getAttributeValue(null, "id")
                    "display-name" -> currentChannel?.let { id ->
                        val n = parser.nextText().trim()
                        if (n.isNotEmpty()) channels.getOrPut(id) { mutableListOf() } += n
                    }
                    "icon" -> currentChannel?.let { id ->
                        val src = parser.getAttributeValue(null, "src")?.trim().orEmpty()
                        if (src.startsWith("https://") && id !in icons) icons[id] = src
                    }
                    "programme" -> {
                        reportChannels()
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = parseTime(parser.getAttributeValue(null, "start"))
                        progStop = parseTime(parser.getAttributeValue(null, "stop"))
                        if (progStop <= progStart) progStop = progStart + 30 * 60_000L
                        keep = progChannel != null && progStart > 0 && accept(progChannel, progStart, progStop)
                        title = null; desc = null; category = null
                        subtitle = null; year = null; rating = null; inRating = false
                    }
                    "title" -> if (keep && title == null) title = parser.nextText().trim()
                    "sub-title" -> if (keep && subtitle == null) subtitle = parser.nextText().trim().ifEmpty { null }
                    "desc" -> if (keep && desc == null) desc = parser.nextText().trim()
                    "category" -> if (keep && category == null) category = parser.nextText().trim()
                    "date" -> if (keep && year == null) year = parser.nextText().trim().take(4).toIntOrNull()
                    "rating" -> if (keep) inRating = true
                    "value" -> if (keep && inRating && rating == null) rating = parser.nextText().trim().ifEmpty { null }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> currentChannel = null
                    "rating" -> inRating = false
                    "programme" -> {
                        if (keep) progChannel?.let { channel ->
                            onProgramme(
                                XmltvProgramme(
                                    channel, progStart, progStop, title ?: "No title",
                                    desc?.ifEmpty { null }, category, subtitle, year, rating,
                                ),
                            )
                        }
                        keep = false
                    }
                }
            }
            event = parser.next()
        }
        reportChannels()
    }

    private val formats = object : ThreadLocal<List<SimpleDateFormat>>() {
        override fun initialValue() =
            listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ", "yyyyMMddHHmm Z", "yyyyMMddHHmmss", "yyyyMMddHHmm")
                .map { SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false } }
    }

    fun parseTime(value: String?): Long {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return 0
        val list = formats.get() ?: return 0
        for (f in list) {
            val pos = ParsePosition(0)
            val d = f.parse(v, pos)
            if (d != null && pos.index > 0) return d.time
        }
        return 0
    }

    /** Lower-case, strip punctuation, quality tags and country prefixes for fuzzy name matching. */
    fun normaliseName(name: String): String =
        name.lowercase()
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""^[a-z]{2,3}\s*[|:\-]\s*"""), "")
            .replace(Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p?|720p?|backup|vip)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), "")
}
