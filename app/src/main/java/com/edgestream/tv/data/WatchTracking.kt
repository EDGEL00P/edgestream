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

/**
 * Watch tracking with Trakt (live scrobbling: start when a film or episode starts, stop with the
 * progress when it ends; Trakt counts 80 % as watched) and Simkl (the title is added to history
 * once 90 % has been seen). Both link with a code entered on their website; the viewer's own
 * app keys come from Settings › Services & API Keys. Tokens are stored sealed.
 */
object WatchTracking {
    private const val TRAKT = "https://api.trakt.tv"
    private const val SIMKL = "https://api.simkl.com"
    private const val PREFS = "edgestream"
    private val JSON = "application/json".toMediaType()

    data class Linked(val trakt: Boolean, val simkl: Boolean)
    private val _linked = MutableStateFlow(Linked(false, false))
    val linked: StateFlow<Linked> = _linked
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        refreshLinked()
    }

    private fun token(name: String) = Secrets.openOrBlank(prefs?.getString(name, "").orEmpty())
    private fun saveToken(name: String, value: String?) {
        prefs?.edit()?.apply { if (value == null) remove(name) else putString(name, Secrets.seal(value)) }?.apply()
        refreshLinked()
    }
    private fun refreshLinked() { _linked.value = Linked(token("traktToken").isNotBlank(), token("simklToken").isNotBlank()) }

    fun unlinkTrakt() = saveToken("traktToken", null)
    fun unlinkSimkl() = saveToken("simklToken", null)

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): Pair<Int, String> =
        Http.client.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
            .execute().use { it.code to it.body.string() }

    private fun get(url: String, headers: Map<String, String>): Pair<Int, String> =
        Http.client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { it.code to it.body.string() }

    /** A code to enter on the service's website, and how to finish linking with it. */
    data class LinkCode(val code: String, val url: String, val intervalSec: Int, val expiresSec: Int, internal val device: String)

    // ---- Trakt ---------------------------------------------------------------------------------

    suspend fun traktCode(clientId: String): LinkCode = withContext(Dispatchers.IO) {
        val (status, body) = post("$TRAKT/oauth/device/code", JSONObject().put("client_id", clientId), mapOf("Content-Type" to "application/json"))
        check(status in 200..299) { "Trakt didn't accept that Client ID ($status)" }
        val o = JSONObject(body)
        LinkCode(o.getString("user_code"), o.optString("verification_url", "trakt.tv/activate"), o.optInt("interval", 5), o.optInt("expires_in", 600), o.getString("device_code"))
    }

    /** True once the viewer has approved the code; false while still waiting. */
    suspend fun traktClaim(code: LinkCode, clientId: String, secret: String): Boolean = withContext(Dispatchers.IO) {
        val (status, body) = post(
            "$TRAKT/oauth/device/token",
            JSONObject().put("code", code.device).put("client_id", clientId).put("client_secret", secret), mapOf("Content-Type" to "application/json"),
        )
        if (status != 200) return@withContext false
        saveToken("traktToken", JSONObject(body).getString("access_token"))
        true
    }

    // ---- Simkl ---------------------------------------------------------------------------------

    suspend fun simklCode(clientId: String): LinkCode = withContext(Dispatchers.IO) {
        val (status, body) = get("$SIMKL/oauth/pin?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}", emptyMap())
        check(status in 200..299) { "Simkl didn't accept that Client ID ($status)" }
        val o = JSONObject(body)
        LinkCode(o.getString("user_code"), o.optString("verification_url", "simkl.com/pin"), o.optInt("interval", 5), o.optInt("expires_in", 900), o.getString("user_code"))
    }

    suspend fun simklClaim(code: LinkCode, clientId: String): Boolean = withContext(Dispatchers.IO) {
        val (status, body) = get("$SIMKL/oauth/pin/${code.device}?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}", emptyMap())
        val token = if (status == 200) JSONObject(body).optString("access_token").ifBlank { null } else null
        if (token == null) return@withContext false
        saveToken("simklToken", token)
        true
    }

    // ---- what's playing --------------------------------------------------------------------------

    /** The film or episode being watched, by IMDb id (episodes by their series' id). */
    data class Watching(val imdbId: String, val season: Int?, val episode: Int?)

    /** Trakt request body for [w] at [progress] percent. */
    internal fun traktBody(w: Watching, progress: Double): JSONObject {
        val ids = JSONObject().put("ids", JSONObject().put("imdb", w.imdbId))
        return JSONObject().put("progress", progress.coerceIn(0.0, 100.0)).apply {
            if (w.season == null || w.episode == null) put("movie", ids)
            else put("show", ids).put("episode", JSONObject().put("season", w.season).put("number", w.episode))
        }
    }

    /** Simkl history body for [w]. */
    internal fun simklBody(w: Watching): JSONObject =
        if (w.season == null || w.episode == null) JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", JSONObject().put("imdb", w.imdbId))))
        else JSONObject().put(
            "shows",
            JSONArray().put(
                JSONObject().put("ids", JSONObject().put("imdb", w.imdbId)).put(
                    "seasons", JSONArray().put(JSONObject().put("number", w.season).put("episodes", JSONArray().put(JSONObject().put("number", w.episode)))),
                ),
            ),
        )

    /** Playback started (or resumed): Trakt shows the viewer as watching it. */
    suspend fun started(w: Watching, progress: Double, traktClientId: String) = trakt("start", w, progress, traktClientId)

    /**
     * Playback ended at [progress] percent: Trakt records the stop (watched from 80 %), Simkl adds
     * the title to history from 90 %.
     */
    suspend fun stopped(w: Watching, progress: Double, traktClientId: String, simklClientId: String) {
        trakt("stop", w, progress, traktClientId)
        val token = token("simklToken")
        if (progress >= 90 && token.isNotBlank() && simklClientId.isNotBlank()) withContext(Dispatchers.IO) {
            runCatching {
                post("$SIMKL/sync/history", simklBody(w), mapOf("Authorization" to "Bearer $token", "simkl-api-key" to simklClientId, "Content-Type" to "application/json"))
            }.onFailure { android.util.Log.w("EdgeStream", "Simkl history failed", it) }
        }
    }

    private suspend fun trakt(action: String, w: Watching, progress: Double, clientId: String) {
        val token = token("traktToken")
        if (token.isBlank() || clientId.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val (status, _) = post(
                    "$TRAKT/scrobble/$action", traktBody(w, progress),
                    mapOf("Authorization" to "Bearer $token", "trakt-api-version" to "2", "trakt-api-key" to clientId, "Content-Type" to "application/json"),
                )
                // A revoked token: forget it, so Settings shows Trakt as not linked.
                if (status == 401) saveToken("traktToken", null)
            }.onFailure { android.util.Log.w("EdgeStream", "Trakt $action failed", it) }
        }
    }
}
