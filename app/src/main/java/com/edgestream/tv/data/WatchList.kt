package com.edgestream.tv.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One saved title: either the provider's copy ([vodKey]) or a catalogue title that plays
 * through the debrid service ([imdbId] helps it find a copy). Newest first.
 */
data class WatchItem(
    val id: String,
    val title: String,
    val year: String?,
    val poster: String?,
    val movie: Boolean,
    val vodKey: String? = null,
    val imdbId: String? = null,
)

object WatchListCodec {
    /** Adds [item] at the front, or removes it if already saved. */
    fun toggle(list: List<WatchItem>, item: WatchItem): List<WatchItem> =
        if (list.any { it.id == item.id }) list.filterNot { it.id == item.id } else listOf(item) + list

    fun encode(list: List<WatchItem>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("id", it.id).put("t", it.title).put("y", it.year ?: JSONObject.NULL).put("p", it.poster ?: JSONObject.NULL)
                .put("m", it.movie).put("k", it.vodKey ?: JSONObject.NULL).put("i", it.imdbId ?: JSONObject.NULL))
        }
    }.toString()

    fun decode(json: String?): List<WatchItem> = try {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            fun opt(k: String) = if (o.isNull(k)) null else o.getString(k)
            WatchItem(o.getString("id"), o.getString("t"), opt("y"), opt("p"), o.getBoolean("m"), opt("k"), opt("i"))
        }
    } catch (_: JSONException) { emptyList() }
}
