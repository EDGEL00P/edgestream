package com.edgestream.tv.ui

import android.content.Context
import com.edgestream.tv.data.CleanName
import com.edgestream.tv.data.db.ChannelEntity
import java.io.File
import kotlinx.coroutines.launch

/**
 * The finished lineup (cleaned names, badges, raw names) persisted to disk, so the app opens with
 * every channel ready instantly instead of waiting ~27 s for the catalogue and name cleaning.
 * The snapshot only ever stands in until the real pipeline's first emission replaces it.
 *
 * The file is a private line format, not JSON: 6k+ channels parse in milliseconds (org.json needed
 * seconds on the SHIELD). Fields are separated by \u0001 and empty values are marked with \u0002 —
 * control characters no provider name or URL contains; values are sanitized on write so a stray
 * separator or newline can never corrupt a row.
 */
internal object LineupSnapshot {
    private const val FILE = "lineup-snapshot.json"
    /** \u0001: a control character no provider name or URL can contain. */
    private val SEP = 1.toChar()
    /** \u0002: marks an empty field, distinct from a blank-but-present value. */
    private val NIL = 2.toChar().toString()
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
        runCatching { file(context).writeText(serialize(catalog.display, catalog.badges, catalog.rawNames)) }
    }

    /** The snapshot, or null when there is none, it is unreadable, or it is too old. */
    fun load(context: Context): ChannelCatalog? {
        val f = file(context)
        if (!f.exists()) return null
        val parsed = runCatching { deserialize(f.readText()) }.getOrNull() ?: return null
        return parsed.takeIf { it.display.isNotEmpty() && System.currentTimeMillis() - it.at < MAX_AGE_MS }
    }

    // ---- the private line format ---------------------------------------------------------------

    private fun clean(s: String?): String? =
        s?.replace(SEP, ' ')?.replace('\n', ' ')?.replace('\r', ' ')?.ifBlank { null }

    private fun field(s: String): String? = if (s == NIL) null else s.ifBlank { null }

    internal fun serialize(display: List<ChannelEntity>, badges: Map<String, CleanName>, rawNames: Map<String, String>): String = buildString {
        append("v1\n").append(System.currentTimeMillis()).append('\n')
        display.forEach { ch ->
            append('C').append(SEP).append(ch.key).append(SEP).append(ch.sourceId).append(SEP)
            append(clean(ch.name) ?: "").append(SEP).append(clean(ch.groupName) ?: "").append(SEP)
            append(clean(ch.logo) ?: NIL).append(SEP).append(ch.streamUrl).append(SEP)
            append(clean(ch.epgId) ?: NIL).append(SEP).append(ch.number).append(SEP).append(ch.catchupDays).append(SEP)
            append(clean(ch.catchupSource) ?: NIL).append(SEP)
            append(ch.xtreamStreamId?.toString() ?: NIL).append(SEP)
            append(if (ch.offline) 1 else 0).append(SEP)
            append(clean(ch.tvgId) ?: NIL).append(SEP).append(clean(ch.identity) ?: NIL).append('\n')
        }
        badges.forEach { (k, c) ->
            append('B').append(SEP).append(k).append(SEP).append(clean(c.title) ?: "").append(SEP)
            append(clean(c.quality) ?: NIL).append(SEP).append(c.tags.joinToString(",") { it.replace(',', ' ') }).append('\n')
        }
        rawNames.forEach { (k, v) -> append('R').append(SEP).append(k).append(SEP).append(clean(v) ?: "").append('\n') }
    }

    internal fun deserialize(text: String): ChannelCatalog? {
        val lines = text.split('\n')
        if (lines.firstOrNull() != "v1") return null
        val at = lines.getOrNull(1)?.toLongOrNull() ?: return null
        val display = ArrayList<ChannelEntity>(4096)
        val badges = HashMap<String, CleanName>()
        val raws = HashMap<String, String>()
        for (i in 2 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val f = line.split(SEP)
            when (line[0]) {
                // C  key  sourceId  name  groupName  logo  streamUrl  epgId  number  catchupDays
                //    catchupSource  xtreamStreamId  offline  tvgId  identity
                'C' -> if (f.size >= 15) {
                    display += ChannelEntity(
                        key = f[1], sourceId = f[2].toLongOrNull() ?: 0L,
                        name = f[3], groupName = f[4], logo = field(f[5]), streamUrl = f[6], epgId = field(f[7]),
                        number = f[8].toIntOrNull() ?: 0, catchupDays = f[9].toIntOrNull() ?: 0,
                        catchupSource = field(f[10]), xtreamStreamId = field(f[11])?.toLongOrNull(),
                        offline = f[12] == "1", tvgId = field(f[13]), identity = field(f[14]),
                    )
                }
                // B  key  title  quality  tags(comma separated)
                'B' -> if (f.size >= 5) {
                    badges[f[1]] = CleanName(f[2], field(f[3]), f[4].split(',').filter { it.isNotBlank() })
                }
                // R  key  rawName
                'R' -> if (f.size >= 3) raws[f[1]] = f[2]
            }
        }
        return ChannelCatalog(display, badges, raws, at)
    }
}
