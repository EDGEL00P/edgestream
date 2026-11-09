package com.edgestream.tv.data

import android.util.JsonReader
import android.util.JsonToken
import com.edgestream.tv.data.parse.XmltvParser
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Free public services that make the app better without needing a provider's cooperation.
 * Everything is optional and fails soft: a missing answer just means less decoration.
 *
 *  - iptv-org (https://github.com/iptv-org): channel logo database and free-to-air playlists. No key.
 *  - TVMaze (https://www.tvmaze.com/api): series artwork, summaries, ratings. No key.
 *  - TMDB (https://developer.themoviedb.org): film/series backdrops, cast, ratings. Free personal key.
 *  - Open-Meteo (https://open-meteo.com): current weather. No key.
 *  - ipapi.co / ip-api.com: approximate location (city) for weather and country playlists. No key.
 */
object PublicApis {

    // ---- iptv-org ------------------------------------------------------------------------

    const val IPTV_ORG_ALL = "https://iptv-org.github.io/iptv/index.m3u"
    fun iptvOrgCountry(countryCode: String) = "https://iptv-org.github.io/iptv/countries/${countryCode.lowercase()}.m3u"
    private const val IPTV_ORG_LOGOS = "https://iptv-org.github.io/api/logos.json"
    private const val IPTV_ORG_CHANNELS = "https://iptv-org.github.io/api/channels.json"

    /** Looks up logos by channel id ("BBCOne.uk") or by normalised channel name. */
    class LogoIndex(private val byId: Map<String, String>, private val byName: Map<String, String>) {
        fun find(epgId: String?, name: String): String? =
            epgId?.let { byId[it.lowercase().substringBefore('@')] }
                ?: byName[XmltvParser.normaliseName(name)]
    }

    /**
     * Builds the logo index from iptv-org's public database, cached on disk for a week
     * (the two files are several MB, so they're streamed rather than loaded whole).
     */
    fun logoIndex(cacheDir: File): LogoIndex {
        val logos = cached(cacheDir, "iptvorg-logos.json", IPTV_ORG_LOGOS)
        val channels = cached(cacheDir, "iptvorg-channels.json", IPTV_ORG_CHANNELS)

        // channel id -> best logo (prefer the widest image)
        val logoById = HashMap<String, Pair<String, Int>>()
        streamArray(logos) { o ->
            val id = o["channel"] ?: return@streamArray
            val url = o["url"] ?: return@streamArray
            val w = o["width"]?.toIntOrNull() ?: 0
            if (o["feed"] != null && logoById.containsKey(id.lowercase())) return@streamArray
            val prev = logoById[id.lowercase()]
            if (prev == null || w > prev.second) logoById[id.lowercase()] = url to w
        }
        val byName = HashMap<String, String>()
        streamArray(channels) { o ->
            val id = o["id"]?.lowercase() ?: return@streamArray
            val url = logoById[id]?.first ?: return@streamArray
            o["name"]?.let { byName.putIfAbsent(XmltvParser.normaliseName(it), url) }
        }
        return LogoIndex(logoById.mapValues { it.value.first }, byName)
    }

    private fun cached(dir: File, name: String, url: String): File {
        val f = File(dir, name)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TimeUnit.DAYS.toMillis(7)) return f
        val tmp = File(dir, "$name.tmp")
        Http.open(url) { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(f)
        return f
    }

    /** Streams a JSON array of objects, handing each one over as a flat string map. */
    private fun streamArray(file: File, onItem: (Map<String, String>) -> Unit) {
        JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                val m = HashMap<String, String>()
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                onItem(m)
            }
            r.endArray()
        }
    }

    /** A guide file and, for city-specific guides, the state/territory its times belong to. */
    data class Guide(val url: String, val region: String? = null)

    // ---- TVMaze / TMDB ----------------------------------------------------------------------

    /** Merged details for a film or show; any field may be missing. */
    data class Meta(
        val plot: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val durationMin: Int? = null,
        val rating: String? = null,
        val year: String? = null,
        val trailer: String? = null,
        val credit: String? = null,
        /** TMDB id, when the metadata came from TMDB; used for external skip-data lookups. */
        val tmdbId: Int? = null,
    ) {
        /** Fills this record's blanks from [other], keeping existing values. */
        fun orElse(other: Meta?): Meta = if (other == null) this else Meta(
            plot ?: other.plot, poster ?: other.poster, backdrop ?: other.backdrop, cast ?: other.cast,
            director ?: other.director, genre ?: other.genre, durationMin ?: other.durationMin,
            rating ?: other.rating, year ?: other.year, trailer ?: other.trailer,
            listOfNotNull(credit, other.credit).distinct().joinToString(" · ").ifEmpty { null },
            tmdbId ?: other.tmdbId,
        )
    }

    /**
     * A show's IMDb id from TVMaze (keyless), for skip-segment lookups when no TMDB key is set.
     * A known [year] must match the premiere, so a remake under the same name isn't taken.
     */
    fun tvMazeImdb(title: String, year: String?): String? = runCatching {
        val json = getJson("https://api.tvmaze.com/singlesearch/shows?q=${enc(cleanTitle(title))}") as? JSONObject ?: return null
        if (!year.isNullOrBlank() && json.optString("premiered").take(4) != year.take(4)) return null
        json.optJSONObject("externals")?.optString("imdb")?.takeIf { it.startsWith("tt") }
    }.getOrNull()

    /**
     * Metadata from a Stremio metadata addon (e.g. AIOMetadata) when the viewer configured its
     * manifest URL. Fills gaps left by the catalogue/TMDB; the URL is runtime-only, never bundled.
     */
    fun stremioMeta(manifest: String, imdbId: String?, isSeries: Boolean): Meta? = runCatching {
        val id = imdbId?.takeIf { it.startsWith("tt") } ?: return null
        val base = manifest.trim().removeSuffix("/manifest.json").removeSuffix("/")
        if (base.isBlank()) return null
        val j = getJson("$base/meta/${if (isSeries) "series" else "movie"}/$id.json") as? JSONObject ?: return null
        val m = j.optJSONObject("meta") ?: return null
        Meta(
            plot = m.optString("description").ifEmpty { null },
            poster = m.optString("poster").ifEmpty { null },
            backdrop = m.optString("background").ifEmpty { null },
            genre = m.optJSONArray("genres")?.let { a ->
                (0 until a.length()).joinToString(", ") { a.optString(it) }
            }?.ifEmpty { null },
            rating = m.optString("imdbRating").ifEmpty { null },
            year = m.optString("year").ifEmpty { null },
            credit = "Metadata addon",
        )
    }.getOrNull()

    fun tvMazeShow(title: String): Meta? = runCatching {
        val json = getJson("https://api.tvmaze.com/singlesearch/shows?q=${enc(cleanTitle(title))}") as? JSONObject ?: return null
        Meta(
            plot = json.optString("summary").replace(Regex("<[^>]+>"), "").trim().ifEmpty { null },
            poster = json.optJSONObject("image")?.optString("original")?.ifEmpty { null },
            genre = json.optJSONArray("genres")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }?.ifEmpty { null },
            rating = json.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() }?.toString(),
            year = json.optString("premiered").take(4).ifEmpty { null },
            durationMin = json.optInt("averageRuntime").takeIf { it > 0 },
            credit = "TVMaze",
        )
    }.getOrNull()

    fun tmdb(apiKey: String, title: String, isSeries: Boolean, year: String?): Meta? = runCatching {
        if (apiKey.isBlank()) return null
        val kind = if (isSeries) "tv" else "movie"
        val yearParam = year?.takeIf { it.length == 4 }?.let { if (isSeries) "&first_air_date_year=$it" else "&year=$it" }.orEmpty()
        val search = getJson("https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=${enc(cleanTitle(title))}$yearParam") as? JSONObject
        val hit = search?.optJSONArray("results")?.optJSONObject(0) ?: return null
        val id = hit.optLong("id")
        val details = getJson("https://api.themoviedb.org/3/$kind/$id?api_key=$apiKey&append_to_response=credits,videos") as? JSONObject ?: hit
        val credits = details.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")?.let { a -> (0 until minOf(6, a.length())).joinToString(", ") { a.getJSONObject(it).optString("name") } }
        val director = credits?.optJSONArray("crew")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("job") == "Director" }?.optString("name")
        }
        val trailer = details.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
                ?.optString("key")?.let { "https://www.youtube.com/watch?v=$it" }
        }
        val img = "https://image.tmdb.org/t/p/"
        Meta(
            plot = details.optString("overview").ifEmpty { null },
            poster = details.optString("poster_path").ifEmpty { null }?.let { "${img}w500$it" },
            backdrop = details.optString("backdrop_path").ifEmpty { null }?.let { "${img}w1280$it" },
            cast = cast?.ifEmpty { null },
            director = director?.ifEmpty { null },
            genre = details.optJSONArray("genres")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }?.ifEmpty { null },
            durationMin = details.optInt("runtime").takeIf { it > 0 },
            rating = details.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }?.let { "%.1f".format(it) },
            year = (details.optString("release_date").ifEmpty { details.optString("first_air_date") }).take(4).ifEmpty { null },
            trailer = trailer,
            credit = "TMDB",
            tmdbId = id.takeIf { it > 0 }?.toInt(),
        )
    }.getOrNull()

    /** Strips provider decorations: "EN - The Matrix (1999) [4K]" -> "The Matrix". */
    fun cleanTitle(t: String): String = t
        .replace(Regex("""^[A-Z]{2,3}\s*[|:\-]\s*"""), "")
        .replace(Regex("""\[[^]]*]|\(\d{4}\)|\b(4K|UHD|FHD|HD|HEVC|MULTI|SUB|VOSTFR)\b"""), "")
        .replace(Regex("""\s+"""), " ").trim()

    // ---- weather ----------------------------------------------------------------------------

    /** [region] is the ISO 3166-2 subdivision suffix ("QLD") when known; used for state holidays. */
    data class Location(val city: String, val countryCode: String, val lat: Double, val lon: Double, val region: String? = null)
    /** [isDay] is day or night at the place itself (Open-Meteo is_day), not on the TV's clock. */
    data class Weather(val city: String, val tempC: Double, val code: Int, val isDay: Boolean = true) {
        val icon: String get() = when (code) {
            0 -> "☀"; 1, 2 -> "⛅"; 3 -> "☁"; 45, 48 -> "🌫"; in 51..67, in 80..82 -> "🌧"
            in 71..77, 85, 86 -> "❄"; in 95..99 -> "⛈"; else -> "☁"
        }
        val description: String get() = when (code) {
            0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
            in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"
            85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> ""
        }
    }

    data class HourPoint(
        val time: String, val tempC: Double, val precipChance: Int, val precipMm: Double,
        val code: Int, val windKph: Double, val windDir: Int,
    )

    /** Everything a proper weather panel needs, from Open-Meteo's free current/hourly/daily blocks. */
    data class WeatherDetail(
        val city: String, val lat: Double, val lon: Double, val elevation: Double?,
        val tempC: Double, val feelsC: Double, val dewC: Double,
        val humidity: Int, val pressureHpa: Double, val visibilityKm: Double,
        val uv: Double, val cloudPct: Int, val precipMm: Double, val rainMm: Double,
        val code: Int, val isDay: Boolean,
        val windKph: Double, val windDir: Int, val gustKph: Double,
        val highC: Double, val lowC: Double, val rainChance: Int, val rainSumMm: Double,
        val sunrise: String?, val sunset: String?,
        val hourly: List<HourPoint>, val observedAt: String,
        val surfacePressureHpa: Double = Double.NaN,
        val capeJkg: Double = Double.NaN,
        val liftedIndex: Double = Double.NaN,
        val freezingLevelM: Double = Double.NaN,
        val boundaryLayerM: Double = Double.NaN,
        val wetBulbC: Double = Double.NaN,
        val vpdKpa: Double = Double.NaN,
        val sunshineMin: Double = Double.NaN,
        /** Today first, then the next days (Open-Meteo `daily`, up to five). */
        val daily: List<DayPoint> = emptyList(),
    ) {
        val description: String get() = Weather(city, tempC, code).description
        /** Open-Meteo's CAPE/LI pair expressed as a plain-language storm potential. */
        val stormRisk: String
            get() = when {
                capeJkg >= 2500 -> "Severe"
                capeJkg >= 1000 -> "High"
                capeJkg >= 300 || liftedIndex <= -2 -> "Moderate"
                else -> "Low"
            }
        val rainRateMmH: Double get() = if (precipMm.isNaN()) Double.NaN else precipMm * 4
    }

    @Volatile private var cachedDetail: Pair<String, Pair<Long, WeatherDetail>>? = null

    @Volatile private var cachedLocation: Location? = null

    /** Looked up once per session: the public IP's city rarely changes while the app runs. */
    fun location(): Location? = cachedLocation ?: lookupLocation()?.also { cachedLocation = it }

    /** A geocoder match the user picked for the weather; saved whole so it is never re-guessed. */
    data class Place(val name: String, val admin1: String, val country: String, val countryCode: String, val lat: Double, val lon: Double) {
        /** "Queensland, Australia": what tells two Springfields apart. */
        val detail: String get() = listOf(admin1.takeIf { it != name }.orEmpty(), country.ifBlank { countryCode.uppercase() }).filter(String::isNotBlank).joinToString(", ")
        fun toLocation() = Location(name, countryCode, lat, lon, AutoCalendar.subdivisionCode(countryCode, admin1.takeIf(String::isNotBlank)))
        fun toJson(): String = JSONObject().put("name", name).put("admin1", admin1).put("country", country)
            .put("cc", countryCode).put("lat", lat).put("lon", lon).toString()

        companion object {
            fun fromJson(s: String): Place? = runCatching {
                val j = JSONObject(s)
                Place(j.getString("name"), j.optString("admin1"), j.optString("country"), j.optString("cc"), j.getDouble("lat"), j.getDouble("lon"))
            }.getOrNull()
        }
    }

    /**
     * Places matching what the user has typed so far, best first, via Open-Meteo's free geocoder.
     * "Paris, FR" keeps only that country. Empty on no match or no network.
     */
    fun searchPlaces(query: String): List<Place> = runCatching {
        val name = query.substringBefore(',').trim()
        if (name.length < 2) return@runCatching emptyList()
        val country = query.substringAfter(',', "").trim().takeIf { it.length == 2 }
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" +
            java.net.URLEncoder.encode(name, "UTF-8")
        parsePlaces(getJson(url) as? JSONObject ?: return@runCatching emptyList())
            .filter { country == null || it.countryCode.equals(country, true) }
    }.getOrDefault(emptyList())

    internal fun parsePlaces(json: JSONObject): List<Place> {
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }.map { r ->
            Place(
                r.getString("name"), r.optString("admin1"), r.optString("country"), r.optString("country_code"),
                r.getDouble("latitude"), r.getDouble("longitude"),
            )
        }.distinctBy { listOf(it.name, it.admin1, it.countryCode) }
    }

    /** A place typed before the picker existed ("Brisbane", "Paris, FR"): its best match. */
    fun geocode(place: String): Location? = searchPlaces(place).firstOrNull()?.toLocation()

    private fun lookupLocation(): Location? =
        runCatching {
            val j = getJson("https://ipapi.co/json/") as? JSONObject ?: return@runCatching null
            Location(j.getString("city"), j.getString("country_code"), j.getDouble("latitude"), j.getDouble("longitude"), j.optString("region_code").takeIf(String::isNotBlank))
        }.getOrNull() ?: runCatching {
            val j = getJson("http://ip-api.com/json/?fields=status,city,countryCode,region,lat,lon") as? JSONObject ?: return@runCatching null
            if (j.optString("status") != "success") null
            else Location(j.getString("city"), j.getString("countryCode"), j.getDouble("lat"), j.getDouble("lon"), j.optString("region").takeIf(String::isNotBlank))
        }.getOrNull()

    fun weather(loc: Location): Weather? = runCatching {
        val j = getJson("https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}&current=temperature_2m,weather_code,is_day&timezone=auto") as? JSONObject ?: return@runCatching null
        val cur = j.getJSONObject("current")
        Weather(loc.city, cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
    }.getOrNull()

    /**
     * Full observation + short forecast for the radar/weather panel. Cached for ten minutes;
     * Open-Meteo updates station data roughly every 15 minutes, so this is already fresher than
     * the source.
     */
    /** One day of the short forecast. */
    data class DayPoint(val date: String, val code: Int, val highC: Double, val lowC: Double)

    /** Open-Meteo `daily` block → days; stops at the shortest array so a partial block never misaligns. */
    fun parseDaily(d: org.json.JSONObject?): List<DayPoint> {
        if (d == null) return emptyList()
        val time = d.optJSONArray("time") ?: return emptyList()
        val code = d.optJSONArray("weather_code") ?: return emptyList()
        val hi = d.optJSONArray("temperature_2m_max") ?: return emptyList()
        val lo = d.optJSONArray("temperature_2m_min") ?: return emptyList()
        val n = minOf(time.length(), code.length(), hi.length(), lo.length())
        return (0 until n).map { DayPoint(time.optString(it), code.optInt(it), hi.optDouble(it), lo.optDouble(it)) }
    }

    fun weatherDetail(loc: Location): WeatherDetail? {
        val key = "%.3f,%.3f".format(loc.lat, loc.lon)
        cachedDetail?.let { (k, cached) ->
            if (k == key && System.currentTimeMillis() - cached.first < 10 * 60_000L) return cached.second
        }
        val detail = runCatching {
            val current = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,rain," +
                "weather_code,cloud_cover,pressure_msl,surface_pressure,wind_speed_10m,wind_direction_10m," +
                "wind_gusts_10m,visibility,dew_point_2m,uv_index,cape,lifted_index,freezing_level_height," +
                "boundary_layer_height,wet_bulb_temperature_2m,vapour_pressure_deficit,sunshine_duration"
            val hourly = "temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_direction_10m"
            val daily = "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max," +
                "precipitation_probability_max,precipitation_sum,wind_speed_10m_max"
            val j = getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}" +
                    "&current=$current&hourly=$hourly&daily=$daily&forecast_days=5&timezone=auto",
            ) as? JSONObject ?: return@runCatching null
            val cur = j.optJSONObject("current") ?: return@runCatching null
            val d = j.optJSONObject("daily")
            val h = j.optJSONObject("hourly")
            fun num(o: JSONObject?, k: String): Double = o?.optDouble(k, Double.NaN)?.takeIf { !it.isNaN() } ?: Double.NaN
            val times = h?.optJSONArray("time")
            val nowKey = cur.optString("time").take(13)
            val start = if (times == null) 0 else (0 until times.length()).firstOrNull { times.optString(it) >= nowKey } ?: 0
            val points = if (h == null || times == null) emptyList() else (start until minOf(start + 8, times.length())).map { i ->
                HourPoint(
                    time = times.optString(i),
                    tempC = h.optJSONArray("temperature_2m")?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() } ?: Double.NaN,
                    precipChance = h.optJSONArray("precipitation_probability")?.optInt(i, 0) ?: 0,
                    precipMm = h.optJSONArray("precipitation")?.optDouble(i, 0.0) ?: 0.0,
                    code = h.optJSONArray("weather_code")?.optInt(i, 0) ?: 0,
                    windKph = h.optJSONArray("wind_speed_10m")?.optDouble(i, 0.0) ?: 0.0,
                    windDir = h.optJSONArray("wind_direction_10m")?.optInt(i, 0) ?: 0,
                )
            }
            WeatherDetail(
                city = loc.city, lat = loc.lat, lon = loc.lon,
                elevation = j.optDouble("elevation", Double.NaN).takeIf { !it.isNaN() },
                tempC = num(cur, "temperature_2m"), feelsC = num(cur, "apparent_temperature"),
                dewC = num(cur, "dew_point_2m"), humidity = cur.optInt("relative_humidity_2m"),
                pressureHpa = num(cur, "pressure_msl"), visibilityKm = num(cur, "visibility") / 1000.0,
                uv = num(cur, "uv_index"), cloudPct = cur.optInt("cloud_cover"),
                precipMm = num(cur, "precipitation"), rainMm = num(cur, "rain"),
                code = cur.optInt("weather_code"), isDay = cur.optInt("is_day", 1) == 1,
                windKph = num(cur, "wind_speed_10m"), windDir = cur.optInt("wind_direction_10m"),
                gustKph = num(cur, "wind_gusts_10m"),
                highC = d?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() } ?: Double.NaN,
                lowC = d?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() } ?: Double.NaN,
                rainChance = d?.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0,
                rainSumMm = d?.optJSONArray("precipitation_sum")?.optDouble(0, 0.0) ?: 0.0,
                sunrise = d?.optJSONArray("sunrise")?.optString(0)?.ifBlank { null },
                sunset = d?.optJSONArray("sunset")?.optString(0)?.ifBlank { null },
                hourly = points,
                daily = parseDaily(d),
                observedAt = cur.optString("time"),
                surfacePressureHpa = num(cur, "surface_pressure"),
                capeJkg = num(cur, "cape"),
                liftedIndex = num(cur, "lifted_index"),
                freezingLevelM = num(cur, "freezing_level_height"),
                boundaryLayerM = num(cur, "boundary_layer_height"),
                wetBulbC = num(cur, "wet_bulb_temperature_2m"),
                vpdKpa = num(cur, "vapour_pressure_deficit"),
                sunshineMin = num(cur, "sunshine_duration") / 60.0,
            )
        }.onFailure { android.util.Log.w("EdgeWeather", "weatherDetail failed", it) }.getOrNull()
        if (detail != null) cachedDetail = key to (System.currentTimeMillis() to detail)
        return detail
    }

    data class NearbyPlace(val name: String, val km: Int, val bearing: String)

    @Volatile private var cachedNearby: Pair<String, Pair<Long, List<NearbyPlace>>>? = null

    /**
     * Towns and major centres around the selected weather place. Esri supplies local town names;
     * a small built-in list adds the bigger centres that its reverse geocoder ranks below them.
     */
    fun nearby(lat: Double, lon: Double): List<NearbyPlace> {
        val key = "%.3f,%.3f".format(lat, lon)
        cachedNearby?.let { (k, cached) ->
            if (k == key && System.currentTimeMillis() - cached.first < 10 * 60_000L) return cached.second
        }
        val names = LinkedHashMap<String, NearbyPlace>()
        fun add(name: String, plat: Double, plon: Double) {
            val n = name.trim()
            if (n.isBlank()) return
            val km = haversineKm(lat, lon, plat, plon)
            if (km < 1 || km > 500) return
            names.putIfAbsent(n.lowercase(), NearbyPlace(n, km.roundToInt(), compassOf(plat, plon, lat, lon)))
        }
        // Local towns (up to 50 km).
        runCatching {
            val url = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates" +
                "?f=json&location=$lon,$lat&distance=50000&category=City&maxLocations=25&outFields=PlaceName&outSR=4326"
            val j = getJson(url) as? JSONObject ?: return@runCatching
            val c = j.optJSONArray("candidates") ?: return@runCatching
            for (i in 0 until c.length()) {
                val o = c.optJSONObject(i) ?: continue
                val name = o.optJSONObject("attributes")?.optString("PlaceName").orEmpty()
                val loc = o.optJSONObject("location") ?: continue
                add(name, loc.optDouble("y"), loc.optDouble("x"))
            }
        }
        // Bigger centres from the built-in list.
        WORLD_CITIES.forEach { (name, plat, plon) -> add(name, plat, plon) }
        val list = names.values.sortedBy { it.km }.take(10)
        cachedNearby = key to (System.currentTimeMillis() to list)
        return list
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun compassOf(lat: Double, lon: Double, fromLat: Double, fromLon: Double): String {
        val dLat = lat - fromLat
        val dLon = lon - fromLon
        val deg = Math.toDegrees(kotlin.math.atan2(dLon, dLat))
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360 + 360) % 360) / 45).toInt().coerceIn(0, 7)]
    }

    /** Major centres used to enrich the radar's nearby list alongside Esri's local towns. */
    private val WORLD_CITIES = listOf(
        Triple("Brisbane", -27.4698, 153.0251),
        Triple("Gold Coast", -28.0167, 153.4000),
        Triple("Sunshine Coast", -26.6500, 153.0667),
        Triple("Toowoomba", -27.5598, 151.9507),
        Triple("Bundaberg", -24.8661, 152.3489),
        Triple("Maryborough", -25.5407, 152.7049),
        Triple("Hervey Bay", -25.2882, 152.8400),
        Triple("Gympie", -26.1898, 152.6650),
        Triple("Gladstone", -23.8427, 151.2550),
        Triple("Rockhampton", -23.3791, 150.5100),
        Triple("Mackay", -21.1411, 149.1860),
        Triple("Townsville", -19.2590, 146.8169),
        Triple("Cairns", -16.9186, 145.7781),
        Triple("Sydney", -33.8688, 151.2093),
        Triple("Newcastle", -32.9283, 151.7817),
        Triple("Wollongong", -34.4278, 150.8931),
        Triple("Canberra", -35.2809, 149.1300),
        Triple("Melbourne", -37.8136, 144.9631),
        Triple("Geelong", -38.1499, 144.3617),
        Triple("Hobart", -42.8821, 147.3272),
        Triple("Adelaide", -34.9285, 138.6007),
        Triple("Perth", -31.9505, 115.8605),
        Triple("Darwin", -12.4634, 130.8456),
        Triple("Auckland", -36.8509, 174.7645),
        Triple("Wellington", -41.2866, 174.7756),
        Triple("Christchurch", -43.5321, 172.6362),
        Triple("Hamilton", -37.7870, 175.2793),
        Triple("Tauranga", -37.6878, 176.1651),
        Triple("Dunedin", -45.8788, 170.5028),
        Triple("London", 51.5074, -0.1278),
        Triple("Manchester", 53.4808, -2.2426),
        Triple("Birmingham", 52.4862, -1.8904),
        Triple("Liverpool", 53.4084, -2.9916),
        Triple("Leeds", 53.8008, -1.5491),
        Triple("Glasgow", 55.8642, -4.2518),
        Triple("Edinburgh", 55.9533, -3.1883),
        Triple("Cardiff", 51.4816, -3.1791),
        Triple("Belfast", 54.5973, -5.9301),
        Triple("Dublin", 53.3498, -6.2603),
        Triple("Cork", 51.8985, -8.4756),
        Triple("Galway", 53.2707, -9.0568),
        Triple("New York", 40.7128, -74.0060),
        Triple("Los Angeles", 34.0522, -118.2437),
        Triple("Chicago", 41.8781, -87.6298),
        Triple("Houston", 29.7604, -95.3698),
        Triple("Phoenix", 33.4484, -112.0740),
        Triple("Philadelphia", 39.9526, -75.1652),
        Triple("Dallas", 32.7767, -96.7970),
        Triple("San Francisco", 37.7749, -122.4194),
        Triple("Seattle", 47.6062, -122.3321),
        Triple("Denver", 39.7392, -104.9903),
        Triple("Boston", 42.3601, -71.0589),
        Triple("Miami", 25.7617, -80.1918),
        Triple("Atlanta", 33.7490, -84.3880),
        Triple("Toronto", 43.6532, -79.3832),
        Triple("Montreal", 45.5017, -73.5673),
        Triple("Vancouver", 49.2827, -123.1207),
        Triple("Calgary", 51.0447, -114.0719),
        Triple("Ottawa", 45.4215, -75.6972),
        Triple("Edmonton", 53.5461, -113.4938),
    )

    // ---- helpers ----------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Small JSON lookups get a hard 10 s ceiling so a slow free service can never stall a refresh. */
    private val quickClient by lazy { Http.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    private fun getJson(url: String): Any? {
        quickClient.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body.string().trim()
            return when {
                body.startsWith("{") -> JSONObject(body)
                body.startsWith("[") -> JSONArray(body)
                else -> null
            }
        }
    }
}
