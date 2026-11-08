package com.edgestream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Entries the calendar fills in by itself, all read-only and keyless:
 *  - public holidays for the viewer's country and state (Nager.Date),
 *  - every fixture of each team followed in Sports (ESPN team schedules), with its broadcaster.
 */
object AutoCalendar {

    // ---- public holidays ---------------------------------------------------------------------

    private fun holidaysUrl(year: Int, cc: String) = "https://date.nager.at/api/v3/PublicHolidays/$year/${cc.uppercase()}"

    /** National holidays plus those of [region] (ISO 3166-2 suffix, e.g. "QLD"); others' state days are dropped. */
    fun parseHolidays(json: String, countryCode: String, region: String?): List<CalendarEvent> = try {
        val a = JSONArray(json)
        val mine = region?.let { "${countryCode.uppercase()}-${it.uppercase()}" }
        (0 until a.length()).map { a.getJSONObject(it) }.filter { h ->
            h.optBoolean("global", true) || (mine != null && h.optJSONArray("counties")?.let { c -> (0 until c.length()).any { c.optString(it) == mine } } == true)
        }.map { h ->
            val date = LocalDate.parse(h.getString("date"))
            CalendarEvent("holiday:$date:${h.optString("name")}", h.optString("localName", h.optString("name")), date, null, kind = EntryKind.HOLIDAY, note = "Public holiday")
        }.distinctBy { it.date to it.title }
    } catch (_: JSONException) { emptyList() } catch (_: java.time.format.DateTimeParseException) { emptyList() }

    suspend fun holidays(countryCode: String, region: String?, today: LocalDate): List<CalendarEvent> = withContext(Dispatchers.IO) {
        listOf(today.year, today.year + 1).flatMap { year ->
            try { Http.open(holidaysUrl(year, countryCode)) { parseHolidays(it.reader().readText(), countryCode, region) } }
            catch (e: IOException) { android.util.Log.w("EdgeCalendar", "holidays $year unavailable", e); emptyList() }
        }
    }

    // ---- followed teams' fixtures ------------------------------------------------------------

    private fun base(league: Sports.League) = "https://site.api.espn.com/apis/site/v2/sports/${league.path}"

    /** ESPN team id for a league abbreviation; schedules are addressed by id (soccer requires it). */
    private val teamIds = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun parseTeamIds(json: String): Map<String, String> = try {
        val teams = JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
        (0 until teams.length()).map { teams.getJSONObject(it).getJSONObject("team") }
            .associate { it.optString("abbreviation").uppercase() to it.getString("id") }
    } catch (_: JSONException) { emptyMap() }

    /** Fixtures from today on, each at its local start time, titled "Away at Home" with the broadcaster. */
    fun parseSchedule(json: String, league: Sports.League, from: Long, zone: ZoneId): List<CalendarEvent> = try {
        val events = JSONObject(json).optJSONArray("events") ?: JSONArray()
        (0 until events.length()).map { events.getJSONObject(it) }.mapNotNull { e ->
            val start = Sports.parseDate(e.getString("date"))
            if (start < from) return@mapNotNull null
            val at = Instant.ofEpochMilli(start).atZone(zone)
            val tv = e.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("broadcasts")?.let { b ->
                (0 until b.length()).mapNotNull { b.optJSONObject(it)?.optJSONObject("media")?.optString("shortName")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            CalendarEvent(
                "game:${e.getString("id")}", e.optString("shortName").ifBlank { e.optString("name") }, at.toLocalDate(), at.toLocalTime().withSecond(0).withNano(0),
                kind = EntryKind.GAME, note = listOf(league.name).plus(tv.distinct()).joinToString("  ·  "),
            )
        }
    } catch (_: JSONException) { emptyList() } catch (_: java.time.format.DateTimeParseException) { emptyList() }

    /** Every followed team's fixtures (keys are SportsFollows keys: "leagueId:ABBR"). */
    suspend fun followedFixtures(follows: Set<String>, now: Long, zone: ZoneId): List<CalendarEvent> = withContext(Dispatchers.IO) {
        coroutineScope {
            follows.mapNotNull { key ->
                val league = Sports.LEAGUES.firstOrNull { it.id == key.substringBefore(':') } ?: return@mapNotNull null
                league to key.substringAfter(':')
            }.map { (league, abbr) ->
                async {
                    try {
                        val ids = teamIds.getOrPut(league.id) { Http.open("${base(league)}/teams") { parseTeamIds(it.reader().readText()) } }
                        val id = ids[abbr] ?: return@async emptyList()
                        val fixtures = if (league.path.startsWith("soccer/")) "?fixture=true" else ""
                        Http.open("${base(league)}/teams/$id/schedule$fixtures") { parseSchedule(it.reader().readText(), league, now, zone) }
                    } catch (e: IOException) {
                        android.util.Log.w("EdgeCalendar", "no schedule for $league $abbr", e); emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }
    }

    // ---- where the viewer is -------------------------------------------------------------------

    /** ISO 3166-2 suffix for a geocoder's first-level region name, for the markets with state holidays. */
    fun subdivisionCode(countryCode: String, admin1: String?): String? {
        val name = admin1?.trim()?.lowercase() ?: return null
        return when (countryCode.uppercase()) {
            "AU" -> AU[name]
            "CA" -> CA[name]
            "GB" -> GB[name]
            "US" -> US[name]
            else -> null
        }
    }

    private val AU = mapOf(
        "queensland" to "QLD", "new south wales" to "NSW", "victoria" to "VIC", "south australia" to "SA",
        "western australia" to "WA", "tasmania" to "TAS", "northern territory" to "NT", "australian capital territory" to "ACT",
    )
    private val CA = mapOf(
        "alberta" to "AB", "british columbia" to "BC", "manitoba" to "MB", "new brunswick" to "NB",
        "newfoundland and labrador" to "NL", "nova scotia" to "NS", "ontario" to "ON", "prince edward island" to "PE",
        "quebec" to "QC", "saskatchewan" to "SK", "northwest territories" to "NT", "nunavut" to "NU", "yukon" to "YT",
    )
    private val GB = mapOf("england" to "ENG", "scotland" to "SCT", "wales" to "WLS", "northern ireland" to "NIR")
    private val US = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR", "california" to "CA", "colorado" to "CO",
        "connecticut" to "CT", "delaware" to "DE", "district of columbia" to "DC", "florida" to "FL", "georgia" to "GA",
        "hawaii" to "HI", "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA", "kansas" to "KS",
        "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME", "maryland" to "MD", "massachusetts" to "MA",
        "michigan" to "MI", "minnesota" to "MN", "mississippi" to "MS", "missouri" to "MO", "montana" to "MT",
        "nebraska" to "NE", "nevada" to "NV", "new hampshire" to "NH", "new jersey" to "NJ", "new mexico" to "NM",
        "new york" to "NY", "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH", "oklahoma" to "OK",
        "oregon" to "OR", "pennsylvania" to "PA", "rhode island" to "RI", "south carolina" to "SC", "south dakota" to "SD",
        "tennessee" to "TN", "texas" to "TX", "utah" to "UT", "vermont" to "VT", "virginia" to "VA", "washington" to "WA",
        "west virginia" to "WV", "wisconsin" to "WI", "wyoming" to "WY",
    )
}
