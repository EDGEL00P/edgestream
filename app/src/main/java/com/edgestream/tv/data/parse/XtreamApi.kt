package com.edgestream.tv.data.parse

import android.util.JsonReader
import android.util.JsonToken
import com.edgestream.tv.data.Http
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Client for the Xtream Codes "player_api.php" protocol. Responses can hold tens of thousands
 * of items, so everything is read with a streaming [JsonReader] into flat maps.
 */
class XtreamApi(baseUrl: String, private val username: String, private val password: String) {
    val base: String = normaliseBase(baseUrl)
    private val u = enc(username)
    private val p = enc(password)

    data class Account(
        val status: String?,
        val expires: Long?,
        val maxConnections: String?,
        val activeConnections: String?,
        val createdAt: Long?,
        val isTrial: Boolean,
        val allowedFormats: List<String>,
        val timezone: String?,
        val serverUrl: String?,
    )

    data class ShortEpg(val start: Long, val stop: Long, val title: String, val description: String?)
    data class Episode(
        val id: String, val season: Int, val number: Int, val title: String,
        val plot: String?, val image: String?, val durationSecs: Long?, val url: String,
    )
    data class SeriesInfo(val plot: String?, val cover: String?, val episodes: List<Episode>)

    fun account(): Account {
        val root = readObject(api(null))
        val user = root["user_info"] as? Map<*, *>
        val server = root["server_info"] as? Map<*, *>
        val auth = user?.get("auth")?.toString()
        if (user == null || auth == "0") throw IllegalStateException("Login rejected by server")
        return Account(
            status = user["status"]?.toString(),
            expires = user["exp_date"]?.toString()?.toLongOrNull()?.times(1000),
            maxConnections = user["max_connections"]?.toString(),
            activeConnections = user["active_cons"]?.toString(),
            createdAt = user["created_at"]?.toString()?.toLongOrNull()?.times(1000),
            isTrial = user["is_trial"]?.toString() == "1",
            allowedFormats = (user["allowed_output_formats"] as? List<*>)?.map { it.toString() }.orEmpty(),
            timezone = server?.get("timezone")?.toString(),
            serverUrl = server?.get("url")?.toString(),
        )
    }

    /** Provider-side now/next for one channel; titles and descriptions arrive base64-encoded. */
    fun shortEpg(streamId: Long, limit: Int = 6): List<ShortEpg> {
        val root = readObject(api("get_short_epg") + "&stream_id=$streamId&limit=$limit")
        return (root["epg_listings"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val start = m["start_timestamp"]?.toString()?.toLongOrNull() ?: return@mapNotNull null
            val stop = m["stop_timestamp"]?.toString()?.toLongOrNull() ?: return@mapNotNull null
            ShortEpg(start * 1000, stop * 1000, b64(m["title"]) ?: "No title", b64(m["description"]))
        }
    }

    private fun b64(v: Any?): String? = v?.toString()?.takeIf { it.isNotBlank() }?.let {
        runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(it)
    }?.trim()?.ifEmpty { null }

    fun categories(action: String): Map<String, String> =
        readArray(api(action)).associate { it["category_id"].orEmpty() to it["category_name"].orEmpty() }

    fun liveStreams(categoryId: String? = null): List<Map<String, String>> =
        readArray(api("get_live_streams" + (categoryId?.let { "&category_id=$it" } ?: "")))

    /**
     * Hands each item of a list endpoint to [onItem] as it is parsed, so catalogues with
     * 100k+ films never sit in memory all at once. Fully suspend-safe: [onItem] runs in the
     * caller's coroutine context (DB inserts, etc. suspend naturally).
     */
    suspend fun stream(action: String, onItem: suspend (Map<String, String>) -> Unit) {
        val channel = kotlinx.coroutines.channels.Channel<Map<String, String>>(capacity = 512)
        kotlinx.coroutines.coroutineScope {
            val producer = launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    Http.open(api(action)) { input ->
                        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                            r.isLenient = true
                            if (r.peek() != JsonToken.BEGIN_ARRAY) return@open
                            r.beginArray()
                            while (r.hasNext()) {
                                if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                    kotlinx.coroutines.runBlocking { channel.send(readFlatObject(r)) }
                                } else r.skipValue()
                            }
                            r.endArray()
                        }
                    }
                } finally {
                    channel.close()
                }
            }
            try {
                for (item in channel) onItem(item)
            } finally {
                producer.cancel()
            }
        }
    }

    fun liveUrl(streamId: String, ext: String) = "$base/live/$u/$p/$streamId.$ext"
    fun xmltvUrl() = "$base/xmltv.php?username=$u&password=$p"

    private fun api(action: String?): String =
        "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "")

    // ---- streaming JSON helpers -----------------------------------------------------------

    private fun readArray(url: String): List<Map<String, String>> = Http.open(url) { stream ->
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return@use emptyList()
            val out = ArrayList<Map<String, String>>()
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() == JsonToken.BEGIN_OBJECT) out += readFlatObject(r) else r.skipValue()
            }
            r.endArray()
            out
        }
    }

    private fun readObject(url: String): Map<String, Any?> = Http.open(url) { stream ->
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            @Suppress("UNCHECKED_CAST")
            (readAny(r) as? Map<String, Any?>) ?: emptyMap()
        }
    }

    /** Reads an object, keeping scalar fields as strings and dropping nested structures. */
    private fun readFlatObject(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>(24)
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[name] = r.nextString()
                JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private fun readAny(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) m[r.nextName()] = readAny(r)
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l += readAny(r)
            r.endArray()
            l
        }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        else -> { r.skipValue(); null }
    }

    companion object {
        fun normaliseBase(url: String): String {
            var b = url.trim().trimEnd('/')
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
            return b.removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Xtream timeshift URL; the start time is expressed in the server's own timezone. */
        fun timeshiftUrl(base: String, user: String, pass: String, streamId: Long, startMs: Long, durationMin: Long, serverTz: String?): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            fmt.timeZone = serverTz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
            return "$base/timeshift/${enc(user)}/${enc(pass)}/$durationMin/${fmt.format(Date(startMs))}/$streamId.ts"
        }
    }
}
