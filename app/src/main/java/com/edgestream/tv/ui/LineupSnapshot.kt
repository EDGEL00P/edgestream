package com.edgestream.tv.ui

import android.content.Context
import com.edgestream.tv.data.CleanName
import com.edgestream.tv.data.db.ChannelEntity
import java.io.File
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The finished lineup (cleaned names, badges, raw names) persisted to disk, so the app opens with
 * every channel ready instantly instead of waiting ~27 s for the catalogue and name cleaning.
 * The snapshot only ever stands in until the real pipeline's first emission replaces it.
 */
internal object LineupSnapshot {
    private const val FILE = "lineup-snapshot.json"
    /** A snapshot older than this is ignored: the provider's list has surely moved on. */
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private val shared = kotlinx.coroutines.CompletableDeferred<ChannelCatalog?>()

    /** Started from Application.onCreate so the parse overlaps Activity creation, not after it. */
    fun init(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (shared.isCompleted) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { shared.complete(load(context)) }
    }

    suspend fun await(): ChannelCatalog? = shared.await()

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun save(context: Context, catalog: ChannelCatalog) {
        runCatching { file(context).writeText(toJson(catalog.display, catalog.badges, catalog.rawNames)) }
    }

    /** The snapshot, or null when there is none, it is unreadable, or it is too old. */
    fun load(context: Context): ChannelCatalog? {
        val f = file(context)
        if (!f.exists()) return null
        val parsed = runCatching { fromJson(f.readText()) }.getOrNull() ?: return null
        return parsed.takeIf { it.display.isNotEmpty() && System.currentTimeMillis() - it.at < MAX_AGE_MS }
    }

    // ---- JSON -------------------------------------------------------------------------------

    private fun JSONObject.putOpt(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }

    internal fun toJson(display: List<ChannelEntity>, badges: Map<String, CleanName>, rawNames: Map<String, String>): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("at", System.currentTimeMillis())
        val chans = JSONArray()
        display.forEach { ch ->
            val o = JSONObject()
            o.put("key", ch.key).put("sourceId", ch.sourceId).put("name", ch.name).put("groupName", ch.groupName)
            o.putOpt("logo", ch.logo).put("streamUrl", ch.streamUrl).putOpt("epgId", ch.epgId)
            o.put("number", ch.number).put("catchupDays", ch.catchupDays).putOpt("catchupSource", ch.catchupSource)
            o.putOpt("xtreamStreamId", ch.xtreamStreamId).put("offline", ch.offline).putOpt("tvgId", ch.tvgId).putOpt("identity", ch.identity)
            chans.put(o)
        }
        root.put("display", chans)
        val b = JSONObject()
        badges.forEach { (k, clean) ->
            b.put(k, JSONObject().put("title", clean.title).putOpt("quality", clean.quality).put("tags", JSONArray(clean.tags)))
        }
        root.put("badges", b)
        val raw = JSONObject()
        rawNames.forEach { (k, v) -> raw.put(k, v) }
        root.put("rawNames", raw)
        return root.toString()
    }

    internal fun fromJson(text: String): ChannelCatalog? = runCatching {
        val root = JSONObject(text)
        if (root.optInt("v") != 1) return@runCatching null
        fun JSONObject.strOpt(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
        val chans = ArrayList<ChannelEntity>()
        val arr = root.optJSONArray("display") ?: return@runCatching null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            chans += ChannelEntity(
                key = key, sourceId = o.optLong("sourceId"), name = o.optString("name"), groupName = o.optString("groupName"),
                logo = o.strOpt("logo"), streamUrl = o.optString("streamUrl"), epgId = o.strOpt("epgId"),
                number = o.optInt("number"), catchupDays = o.optInt("catchupDays"),
                catchupSource = o.strOpt("catchupSource"),
                xtreamStreamId = if (o.isNull("xtreamStreamId")) null else o.optLong("xtreamStreamId"),
                offline = o.optBoolean("offline"), tvgId = o.strOpt("tvgId"), identity = o.strOpt("identity"),
            )
        }
        val badges = HashMap<String, CleanName>()
        root.optJSONObject("badges")?.let { b ->
            b.keys().forEach { k ->
                val o = b.optJSONObject(k) ?: return@forEach
                val tags = ArrayList<String>()
                o.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) tags += t.optString(i) }
                badges[k] = CleanName(o.optString("title"), o.strOpt("quality"), tags)
            }
        }
        val raws = HashMap<String, String>()
        root.optJSONObject("rawNames")?.let { r -> r.keys().forEach { k -> r.optString(k).takeIf { it.isNotBlank() }?.let { raws[k] = it } } }
        ChannelCatalog(chans.toList(), badges, raws, at = root.optLong("at"))
    }.getOrNull()
}
