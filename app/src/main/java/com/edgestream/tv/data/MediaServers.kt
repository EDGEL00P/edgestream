package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The viewer's own media servers (Jellyfin, Emby, Plex) as a source for On Demand: their rows
 * (continue watching, recently added) and, when Play is pressed on any title, a copy found by
 * its IMDb id, tried before the IPTV provider. Tokens are stored sealed ([Secrets]).
 */
object MediaServers {
    enum class Kind { JELLYFIN, EMBY, PLEX }

    data class Server(val id: String, val kind: Kind, val name: String, val url: String, val userId: String, val token: String)

    /** A title on a server: enough for a row tile and to open it like a catalogue title. */
    data class Title(val imdbId: String, val name: String, val year: String?, val poster: String?, val backdrop: String?, val overview: String?, val movie: Boolean)

    private const val PREFS = "edgestream"
    private const val KEY = "mediaServers"
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _servers.value = runCatching { decode(Secrets.openOrBlank(prefs?.getString(KEY, "").orEmpty())) }.getOrDefault(emptyList())
    }

    private fun save(list: List<Server>) {
        _servers.value = list
        prefs?.edit()?.putString(KEY, Secrets.seal(encode(list)))?.apply()
    }

    fun remove(id: String) = save(_servers.value.filterNot { it.id == id })

    internal fun encode(list: List<Server>) = JSONArray(list.map { s ->
        JSONObject().put("id", s.id).put("kind", s.kind.name).put("name", s.name).put("url", s.url).put("user", s.userId).put("token", s.token)
    }).toString()

    internal fun decode(raw: String): List<Server> {
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Server(o.getString("id"), Kind.valueOf(o.getString("kind")), o.getString("name"), o.getString("url"), o.optString("user"), o.getString("token"))
        }
    }

    private val deviceId by lazy { prefs?.getString("mediaDeviceId", null) ?: java.util.UUID.randomUUID().toString().also { prefs?.edit()?.putString("mediaDeviceId", it)?.apply() } }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        Http.client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code, "HTTP ${r.code}")
            r.body.string()
        }

    // ---- Jellyfin / Emby -----------------------------------------------------------------------

    private fun embyAuth(token: String? = null) = "MediaBrowser Client=\"EdgeStream\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"1.0\"" +
        (token?.let { ", Token=\"$it\"" } ?: "")

    /** Signs in to a Jellyfin or Emby server and keeps it. Returns what went wrong, or null. */
    suspend fun signIn(kind: Kind, address: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        val base = address.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val body = JSONObject().put("Username", username).put("Pw", password).toString().toRequestBody("application/json".toMediaType())
        val reply = runCatching {
            Http.client.newCall(
                Request.Builder().url("$base/Users/AuthenticateByName").post(body)
                    .header("X-Emby-Authorization", embyAuth()).header("Authorization", embyAuth()).build(),
            ).execute().use { r -> r.code to r.body.string() }
        }.getOrElse { return@withContext "Couldn't reach $base" }
        if (reply.first == 401) return@withContext "Wrong username or password"
        if (reply.first !in 200..299) return@withContext "The server answered ${reply.first}"
        val o = runCatching { JSONObject(reply.second) }.getOrNull() ?: return@withContext "That doesn't look like a Jellyfin or Emby server"
        val info = runCatching { JSONObject(get("$base/System/Info/Public")) }.getOrNull()
        val server = Server(
            id = "${kind.name.lowercase()}:${o.optJSONObject("User")?.optString("ServerId") ?: base}", kind = kind,
            name = info?.optString("ServerName")?.ifBlank { null } ?: kind.name.lowercase().replaceFirstChar { it.uppercase() },
            url = base, userId = o.optJSONObject("User")?.optString("Id").orEmpty(), token = o.getString("AccessToken"),
        )
        save(_servers.value.filterNot { it.id == server.id } + server)
        null
    }

    private fun embyTitles(s: Server, path: String): List<Title> {
        fun items(raw: String) = raw.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it).optJSONArray("Items") ?: JSONArray() }
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val list = items(get("${s.url}$path${if ('?' in path) "&" else "?"}Fields=ProviderIds,Overview,ProductionYear&api_key=${enc(s.token)}"))
        // An episode stands for its series (the details page lists the episodes): fetch those
        // series in one request for their IMDb ids.
        val seriesIds = list.filter { it.optString("Type") == "Episode" }.mapNotNull { it.optString("SeriesId").ifBlank { null } }.distinct()
        val series = if (seriesIds.isEmpty()) emptyMap() else
            items(get("${s.url}/Users/${s.userId}/Items?Ids=${seriesIds.joinToString(",")}&Fields=ProviderIds,Overview,ProductionYear&api_key=${enc(s.token)}"))
                .associateBy { it.optString("Id") }
        return list.mapNotNull { o -> if (o.optString("Type") == "Episode") series[o.optString("SeriesId")] else o }
            .distinctBy { it.optString("Id") }
            .mapNotNull { o ->
                val imdb = imdbOf(o) ?: return@mapNotNull null
                val id = o.optString("Id")
                Title(
                    imdb, o.optString("Name"), o.optInt("ProductionYear").takeIf { it > 0 }?.toString(),
                    "${s.url}/Items/$id/Images/Primary?maxHeight=480&api_key=${enc(s.token)}",
                    "${s.url}/Items/$id/Images/Backdrop?maxWidth=1280&api_key=${enc(s.token)}",
                    o.optString("Overview").ifBlank { null }, movie = o.optString("Type") == "Movie",
                )
            }
    }

    private fun imdbOf(o: JSONObject): String? =
        o.optJSONObject("ProviderIds")?.let { p -> p.keys().asSequence().firstOrNull { it.equals("imdb", true) }?.let { p.optString(it) } }?.takeIf { it.startsWith("tt") }

    /**
     * The stream for a film or episode on a Jellyfin/Emby server: searched by title, accepted
     * only when the server's IMDb id for it is the one wanted.
     */
    private fun embyStream(s: Server, imdbId: String, title: String, season: Int?, episode: Int?): String? {
        val type = if (season == null) "Movie" else "Series"
        val hits = JSONObject(get("${s.url}/Users/${s.userId}/Items?Recursive=true&IncludeItemTypes=$type&SearchTerm=${enc(title)}&Fields=ProviderIds&Limit=20&api_key=${enc(s.token)}"))
            .optJSONArray("Items") ?: return null
        val found = (0 until hits.length()).map { hits.getJSONObject(it) }.firstOrNull { imdbOf(it) == imdbId }?.optString("Id") ?: return null
        val itemId = if (season == null) found else {
            val eps = JSONObject(get("${s.url}/Shows/$found/Episodes?UserId=${s.userId}&Season=$season&api_key=${enc(s.token)}")).optJSONArray("Items") ?: return null
            (0 until eps.length()).map { eps.getJSONObject(it) }.firstOrNull { it.optInt("IndexNumber") == episode }?.optString("Id") ?: return null
        }
        return "${s.url}/Videos/$itemId/stream?static=true&api_key=${enc(s.token)}"
    }

    // ---- Plex ------------------------------------------------------------------------------------

    private fun plexHeaders(token: String? = null) = buildMap {
        put("X-Plex-Product", "EdgeStream"); put("X-Plex-Client-Identifier", deviceId); put("Accept", "application/json")
        token?.let { put("X-Plex-Token", it) }
    }

    data class PlexPin(val id: Long, val code: String)

    /** A link code for plex.tv/link. */
    suspend fun plexPin(): PlexPin = withContext(Dispatchers.IO) {
        val o = Http.client.newCall(
            Request.Builder().url("https://plex.tv/api/v2/pins?strong=false").post(ByteArray(0).toRequestBody(null))
                .apply { plexHeaders().forEach { (k, v) -> header(k, v) } }.build(),
        ).execute().use { r -> JSONObject(r.body.string()) }
        PlexPin(o.getLong("id"), o.getString("code"))
    }

    /**
     * Polls the link code until the viewer approves it, then keeps every Plex server on the
     * account. Returns the number of servers added, or null if the code was never approved.
     */
    suspend fun plexClaim(pin: PlexPin): Int? = withContext(Dispatchers.IO) {
        val token = JSONObject(get("https://plex.tv/api/v2/pins/${pin.id}", plexHeaders())).optString("authToken").ifBlank { null } ?: return@withContext null
        val resources = JSONArray(get("https://plex.tv/api/v2/resources?includeHttps=1", plexHeaders(token)))
        val added = (0 until resources.length()).map { resources.getJSONObject(it) }
            .filter { "server" in it.optString("provides") }
            .mapNotNull { r ->
                val conns = r.optJSONArray("connections") ?: return@mapNotNull null
                val all = (0 until conns.length()).map { conns.getJSONObject(it) }
                // The local address first: same network, no relay.
                val best = all.firstOrNull { it.optBoolean("local") && !it.optBoolean("relay") } ?: all.firstOrNull { !it.optBoolean("relay") } ?: all.firstOrNull()
                best?.optString("uri")?.let { uri -> Server("plex:${r.optString("clientIdentifier")}", Kind.PLEX, r.optString("name"), uri.trimEnd('/'), "", r.optString("accessToken").ifBlank { token }) }
            }
        save(_servers.value.filterNot { s -> added.any { it.id == s.id } } + added)
        added.size
    }

    private fun plexTitles(s: Server, path: String): List<Title> {
        val meta = JSONObject(get("${s.url}$path", plexHeaders(s.token))).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        return (0 until meta.length()).mapNotNull { i ->
            val o = meta.getJSONObject(i)
            val episode = o.optString("type") == "episode"
            val guids = o.optJSONArray("Guid")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("id") } }.orEmpty()
            // An episode stands for its series; the series' IMDb id needs one more request.
            val imdb = if (episode) runCatching { plexImdb(s, o.optString("grandparentRatingKey")) }.getOrNull()
            else guids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://")
            imdb ?: return@mapNotNull null
            val thumb = (if (episode) o.optString("grandparentThumb") else o.optString("thumb")).ifBlank { null }
            Title(
                imdb, if (episode) o.optString("grandparentTitle") else o.optString("title"), o.optInt("year").takeIf { it > 0 }?.toString(),
                thumb?.let { "${s.url}$it?X-Plex-Token=${enc(s.token)}" }, o.optString("art").ifBlank { null }?.let { "${s.url}$it?X-Plex-Token=${enc(s.token)}" },
                o.optString("summary").ifBlank { null }, movie = o.optString("type") == "movie",
            )
        }.distinctBy { it.imdbId }
    }

    private fun plexImdb(s: Server, ratingKey: String): String? =
        JSONObject(get("${s.url}/library/metadata/$ratingKey", plexHeaders(s.token))).optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
            ?.optJSONArray("Guid")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("id") } }
            ?.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://")

    /** Searched by title, accepted only when Plex's IMDb id for it matches. */
    private fun plexStream(s: Server, imdbId: String, title: String, season: Int?, episode: Int?): String? {
        val want = if (season == null) "movie" else "show"
        val hubs = JSONObject(get("${s.url}/hubs/search?query=${enc(title)}&limit=10&includeGuids=1", plexHeaders(s.token)))
            .optJSONObject("MediaContainer")?.optJSONArray("Hub") ?: return null
        val hit = (0 until hubs.length()).flatMap { h -> hubs.getJSONObject(h).optJSONArray("Metadata")?.let { m -> (0 until m.length()).map { m.getJSONObject(it) } }.orEmpty() }
            .filter { it.optString("type") == want }
            .firstOrNull { m -> (m.optJSONArray("Guid")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("id") } }.orEmpty() + listOfNotNull(plexImdb(s, m.optString("ratingKey"))))
                .any { it == "imdb://$imdbId" || it == imdbId } } ?: return null
        val item = if (season == null) {
            JSONObject(get("${s.url}/library/metadata/${hit.optString("ratingKey")}", plexHeaders(s.token))).optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0) ?: return null
        } else {
            val eps = JSONObject(get("${s.url}/library/metadata/${hit.optString("ratingKey")}/allLeaves", plexHeaders(s.token)))
                .optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return null
            (0 until eps.length()).map { eps.getJSONObject(it) }.firstOrNull { it.optInt("parentIndex") == season && it.optInt("index") == episode } ?: return null
        }
        val part = item.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key") ?: return null
        return "${s.url}$part?X-Plex-Token=${enc(s.token)}"
    }

    // ---- shared -------------------------------------------------------------------------------

    data class Row(val title: String, val items: List<Title>)

    /** Each server's Continue Watching and Recently Added for films or shows; a server that's down is skipped. */
    suspend fun rows(movies: Boolean): List<Row> = withContext(Dispatchers.IO) {
        _servers.value.flatMap { s ->
            runCatching {
                when (s.kind) {
                    Kind.PLEX -> listOf(
                        Row("Continue Watching on ${s.name}", plexTitles(s, "/library/onDeck")),
                        Row("Recently Added on ${s.name}", plexTitles(s, "/library/recentlyAdded")),
                    )
                    else -> listOf(
                        Row("Continue Watching on ${s.name}", embyTitles(s, "/Users/${s.userId}/Items/Resume?Limit=24&MediaTypes=Video")),
                        Row("Recently Added on ${s.name}", embyTitles(s, "/Users/${s.userId}/Items?Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=30&IncludeItemTypes=${if (movies) "Movie" else "Series"}")),
                    )
                }.map { r -> r.copy(items = r.items.filter { it.movie == movies }) }.filter { it.items.isNotEmpty() }
            }.onFailure { android.util.Log.w("EdgeStream", "media server ${s.name} unavailable", it) }.getOrDefault(emptyList())
        }
    }

    /** The first server copy of a title (by IMDb id), or null when no server has it. */
    suspend fun stream(imdbId: String, title: String, season: Int?, episode: Int?): String? = withContext(Dispatchers.IO) {
        for (s in _servers.value) {
            val url = runCatching {
                if (s.kind == Kind.PLEX) plexStream(s, imdbId, title, season, episode) else embyStream(s, imdbId, title, season, episode)
            }.getOrNull()
            if (url != null) return@withContext url
        }
        null
    }
}
