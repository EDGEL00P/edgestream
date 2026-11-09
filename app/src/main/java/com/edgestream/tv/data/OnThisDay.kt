package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Real "On This Day" history from Wikimedia's keyless public feed: events, births, deaths and
 * annual observances for a calendar date. One small file per month-day is cached on disk, so a
 * date the viewer has already opened stays available offline and is never fetched twice.
 */
object OnThisDay {

    enum class Kind(val label: String) { EVENT("EVENT"), BIRTH("BORN"), DEATH("DIED"), OBSERVANCE("OBSERVANCE") }

    /** [year] is null for recurring observances and negative for BCE dates. */
    data class Fact(val year: Int?, val text: String, val kind: Kind) {
        val annual: Boolean get() = year == null
    }

    private const val FEED = "https://api.wikimedia.org/feed/v1/wikipedia/en/onthisday/all"
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    /** Wikimedia's robot policy returns 403 for a generic User-Agent: it needs a contact URL. */
    private const val USER_AGENT = "EdgeStreamTV/1.0 (https://github.com/EDGEL00P/edgestream; Android TV)"
    private val client by lazy { Http.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    fun endpoint(month: Int, day: Int): String = "$FEED/%02d/%02d".format(month, day)

    private fun cacheFile(dir: File, month: Int, day: Int) = File(dir, "history-%02d-%02d.json".format(month, day))

    /** A cached day is authoritative for a month; after that, one refresh keeps new years coming in. */
    internal fun shouldRefresh(lastModifiedMs: Long, nowMs: Long, ttlMs: Long = TTL_MS): Boolean =
        nowMs < lastModifiedMs || nowMs - lastModifiedMs >= ttlMs

    /** Pure: one balanced, deduplicated timeline, oldest first, annual observances last. */
    internal fun parse(json: JSONObject, limit: Int = 12): List<Fact> {
        val picked = LinkedHashMap<String, Fact>()
        fun take(key: String, kind: Kind, cap: Int) {
            val array = json.optJSONArray(key) ?: return
            var taken = 0
            for (i in 0 until array.length()) {
                if (taken >= cap) break
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("text").trim()
                if (text.length < 8) continue
                val rawYear = item.optInt("year", Int.MIN_VALUE)
                // Wikimedia's holidays are recurring and carry no year; only dated facts get one.
                if (rawYear == Int.MIN_VALUE && kind != Kind.OBSERVANCE) continue
                val year = rawYear.takeIf { it != Int.MIN_VALUE }
                val dedupeKey = text.lowercase()
                if (picked.containsKey(dedupeKey)) continue
                picked[dedupeKey] = Fact(year, text, kind)
                taken++
            }
        }
        // Wikimedia's own highlights first, then a spread of the other categories so no single
        // kind (births alone are ~300 a day) buries the history.
        take("selected", Kind.EVENT, 4)
        take("events", Kind.EVENT, 6)
        take("holidays", Kind.OBSERVANCE, 2)
        take("births", Kind.BIRTH, 3)
        take("deaths", Kind.DEATH, 2)
        return picked.values.sortedBy { it.year ?: Int.MAX_VALUE }.take(limit)
    }

    private fun parseOrNull(raw: String): List<Fact>? = runCatching { parse(JSONObject(raw)) }.getOrNull()

    fun formatYear(year: Int?): String = when {
        year == null -> "ANNUAL"
        year < 0 -> "${-year} BC"
        else -> year.toString()
    }

    /** Null for an annual observance: it has no age, so the UI shows no "years ago". */
    fun yearsAgo(year: Int?, today: LocalDate): Int? = year?.let { today.year - it }

    /** The heading a fact belongs under: "18th century", "Annual observances". */
    fun era(year: Int?): String = when {
        year == null -> "Annual observances"
        year < 0 -> "Before the common era"
        else -> "${ordinal(((year.coerceAtLeast(1) - 1) / 100) + 1)} century"
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    suspend fun load(day: LocalDate, cacheDir: File): List<Fact> = withContext(Dispatchers.IO) {
        val file = cacheFile(cacheDir, day.monthValue, day.dayOfMonth)
        val cachedRaw = runCatching { file.readText() }.getOrNull()
        val cached = cachedRaw?.let(::parseOrNull).orEmpty()
        if (cached.isNotEmpty() && !shouldRefresh(file.lastModified(), System.currentTimeMillis())) return@withContext cached

        val raw = runCatching {
            client.newCall(Request.Builder().url(endpoint(day.monthValue, day.dayOfMonth))
                .header("Accept", "application/json").header("User-Agent", USER_AGENT).build())
                .execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
                    r.body.string()
                }
        }.onFailure { android.util.Log.w("EdgeHistory", "fetch failed for $day: $it") }.getOrNull()
        val fresh = raw?.let(::parseOrNull).orEmpty()
        if (raw != null && fresh.isNotEmpty() && raw != cachedRaw) {
            // Unique temp: two days can be opened in quick succession.
            val tmp = File.createTempFile("history", ".tmp", cacheDir)
            runCatching {
                tmp.writeText(raw)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { tmp.delete() }
        }
        fresh.ifEmpty { cached }
    }
}
