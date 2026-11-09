package com.edgestream.tv.data

import androidx.room.withTransaction
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.EdgeDatabase
import com.edgestream.tv.data.db.FavoriteEntity
import com.edgestream.tv.data.db.HistoryEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.data.db.ProgressEntity
import com.edgestream.tv.data.db.SourceEntity
import com.edgestream.tv.data.db.SourceType
import com.edgestream.tv.data.db.VodEntity
import com.edgestream.tv.data.db.VodKind
import com.edgestream.tv.data.parse.M3uParser
import com.edgestream.tv.data.parse.XmltvParser
import com.edgestream.tv.data.parse.XtreamApi
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** A short human-readable line describing what a background refresh is doing. */
data class SyncStatus(val running: Boolean = false, val message: String = "")

/**
 * How far ahead the guide is kept. The grid asks for 30 h of listings per row and its 12-hour
 * buckets can reach a day past the focused time, so two days keeps every visible cell filled.
 */
private const val GUIDE_AHEAD_MS = 48 * 3_600_000L

/** Built every 6 h by .github/workflows/epg.yml from tools/epg/wanted.json. */
internal const val MERGED_GUIDE = "https://github.com/EDGEL00P/edgestream/releases/download/epg/edgestream-epg.xml.gz"

private fun mergedGuideAvailable(): Boolean = runCatching {
    Http.client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        .newCall(okhttp3.Request.Builder().url(MERGED_GUIDE).head().build()).execute().use { it.isSuccessful }
}.getOrDefault(false)
/** Games that started this long ago can still be live (cricket, golf, a long NFL game). */
private const val GUIDE_BACK_MS = 6 * 3_600_000L
private const val SPORTS_GUIDE_AHEAD_MS = 12 * 3_600_000L

/** Safety cap for guide files that publish very short programmes (some use 5-minute slots). */
private const val GUIDE_MAX_PER_CHANNEL = 160

/**
 * Hard ceiling for the whole programme table. The 48-hour window against the English guides lands
 * around 140k rows; this stops a richer feed (or a provider with thousands more channels) from
 * ever growing the guide back toward a quarter of a million rows.
 */
private const val GUIDE_MAX_ROWS = 180_000

/**
 * Descriptions are the bulk of a guide file. Real files top out around 500 characters; the cap
 * only guards against a pathological plot, so the guide can show the whole thing.
 */
private const val GUIDE_DESCRIPTION_MAX = 1_000

/**
 * Name-listing ids are built from a channel key ("name:<key>"). Provider sources use keys such
 * as "6:L:<stream>", so a current listing id looks like "name:6:L:1394991". Those rows are
 * rebuilt from the channel list on every refresh, so a name id is only preserved when this
 * refresh regenerated it (see [resolved]); any other "name:" id is kept rather than thrown away.
 */
private val NAME_LISTING_ID = Regex("^name:\\d+:L:")

/**
 * Collects a channel's compact schedule while a large XMLTV file streams past.
 *
 * The old reader stored every programme in the download window, which reached 300k+ rows and
 * made every now/next refresh scan the whole guide. Here the parser keeps only what the app
 * renders: the next [maxFuture] programmes for each channel (bounded by [GUIDE_AHEAD_MS]),
 * plus catch-up history for channels that actually offer it. Everything else is dropped.
 */
private class GuideAccumulator(private val maxFuture: Int) {
    private val future = HashMap<String, MutableList<ProgrammeEntity>>()
    private val history = HashMap<String, MutableList<ProgrammeEntity>>()

    fun add(row: ProgrammeEntity, now: Long) {
        if (row.stop <= now) {
            history.getOrPut(row.epgId) { ArrayList() } += row
            return
        }
        val list = future.getOrPut(row.epgId) { ArrayList(maxFuture) }
        // XMLTV is normally sorted by start time, so the common case is one comparison.
        if (list.size >= maxFuture && row.start >= list.last().start) return
        val at = list.indexOfFirst { it.start > row.start }
        if (at < 0) list += row else list.add(at, row)
        if (list.size > maxFuture) list.removeAt(list.size - 1)
    }

    fun rows(): List<ProgrammeEntity> = future.values.flatten() + history.values.flatten()
}


class Repository(
    private val db: EdgeDatabase,
    private val settings: Settings,
    private val cacheDir: java.io.File,
    private val epgMap: EpgMap,
) {
    private val refreshLock = Mutex()
    /** The provider's films and series, as a play source only (see [providerMovie]). */
    private val providerLibrary = ProviderLibrary(cacheDir)
    private val libraryLocks = HashMap<Long, Mutex>()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    val sources get() = db.sources().observeAll()
    val groups get() = db.channels().observeGroups()
    val channels get() = db.channels().observeAll()
    val favoriteKeys get() = db.user().observeFavoriteKeys()
    val historyKeys get() = db.user().observeHistoryKeys()
    val continueWatching get() = db.user().observeContinueWatching()
    val programmeCount get() = db.programmes().observeCount()

    fun recentVod(kind: VodKind, limit: Int = 30) =
        db.vod().observeRecent(kind, limit).map { list -> list.dedupeVodTitles() }
    suspend fun vodByKey(key: String) = db.vod().byKey(key)
    suspend fun vodByKeys(keys: Collection<String>): List<VodEntity> =
        if (keys.isEmpty()) emptyList() else withContext(Dispatchers.IO) { db.vod().byKeys(keys.toList()) }
    fun vodInCategories(kind: VodKind, categories: List<String>) =
        db.vod().observeInCategories(kind, categories).map { list -> list.dedupeVodTitles() }

    // ---- sources ---------------------------------------------------------------------------

    /** Updates just the guide URL on an existing source — no delete/re-add needed. */
    suspend fun updateGuideUrl(id: Long, url: String) = withContext(Dispatchers.IO) {
        db.sources().get(id)?.let { db.sources().upsert(it.copy(epgUrl = url.trim())) }
    }

    /** Saves a new source; the caller schedules its first load with [com.edgestream.tv.work.RefreshWorker]. */
    /** Logins are encrypted before they're stored; see [Secrets]. */
    suspend fun insertSource(source: SourceEntity): Long = withContext(Dispatchers.IO) { db.sources().insert(source.sealed()) }

    /**
     * Proves a provider works before it is saved: the Xtream login is accepted, or the address
     * really serves an M3U playlist. Null when it does; otherwise a reason the user can act on.
     */
    suspend fun verifySource(source: SourceEntity): String? = withContext(Dispatchers.IO) {
        try {
            when (source.type) {
                SourceType.XTREAM -> { XtreamApi(source.url, source.username, source.password).account(); null }
                SourceType.M3U -> Http.open(source.url) { input ->
                    val head = ByteArray(4_096)
                    var n = 0
                    while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
                    val text = String(head, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                    if (text.startsWith("#EXTM3U") || "#EXTINF" in text) null else "That address works, but it isn't an M3U playlist."
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            when {
                t is IllegalStateException && "rejected" in t.message.orEmpty() -> "The provider rejected that username or password."
                t is HttpStatusException && (t.code == 401 || t.code == 403) -> "The provider refused access (HTTP ${t.code}). Check the login."
                t is HttpStatusException -> "The server answered with an error (HTTP ${t.code}). Check the address."
                t is java.net.UnknownHostException -> "Can't find that server. Check the address."
                t is java.io.IOException -> "The server didn't answer. Check the address and port."
                else -> "The provider's reply wasn't understood. Check the address."
            }
        }
    }

    private fun SourceEntity.sealed() = copy(username = Secrets.seal(username), password = Secrets.seal(password))

    /** One pass at start-up: logins saved before encryption existed are encrypted in place. */
    suspend fun sealStoredLogins() = withContext(Dispatchers.IO) {
        db.sources().all().filter { (it.username.isNotEmpty() && !Secrets.isSealed(it.username)) || (it.password.isNotEmpty() && !Secrets.isSealed(it.password)) }
            .forEach { db.sources().upsert(it.sealed()) }
    }

    /** Sources whose last load never finished (e.g. the app was killed part-way). */
    suspend fun unfinishedSources(): List<Long> = withContext(Dispatchers.IO) { db.sources().all().filter { it.lastRefresh == 0L }.map { it.id } }

    suspend fun deleteSource(id: Long) = withContext(Dispatchers.IO) {
        db.channels().deleteForSource(id)
        db.vod().deleteForSource(id)
        db.programmes().deleteForSource(id)
        db.sources().delete(id)
    }

    /** Re-downloads every source's playlist, then its guide. Safe to call from a worker. */
    suspend fun refreshAll(includeEpg: Boolean = true) = withContext(Dispatchers.IO) {
        db.sources().all().forEach { refreshSource(it.id, includeEpg) }
    }

    suspend fun refreshSource(id: Long, includeEpg: Boolean = true) = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val source = db.sources().get(id) ?: return@withLock
            try {
                status("Updating ${source.name}…")
                val updated = when (source.type) {
                    SourceType.M3U -> refreshM3u(source).copy(lastError = null)
                    SourceType.XTREAM -> refreshXtream(source)
                }
                db.sources().upsert(updated)
                if (includeEpg) refreshEpgLocked(updated)
                db.sources().get(id)?.let { db.sources().upsert(it.copy(lastRefresh = System.currentTimeMillis())) }
                // Public lists change daily and always carry dead links; re-check so they stay hidden.
                // Capped so the worldwide list (~10k streams) doesn't spend half an hour probing.
                if (updated.type == SourceType.M3U && "iptv-org.github.io" in updated.url && updated.channelCount <= 2_000) checkStreams(updated.id)
                else status(null)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Re-read: a partial load may already have saved channel counts worth keeping.
                val latest = db.sources().get(id) ?: source
                db.sources().upsert(latest.copy(lastError = t.message ?: t.javaClass.simpleName))
                status("${source.name}: ${t.message ?: "update failed"}", done = true)
            }
        }
    }

    suspend fun refreshEpg(id: Long) = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val source = db.sources().get(id) ?: return@withLock
            try {
                refreshEpgLocked(source)
                status(null)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                db.sources().upsert(source.copy(lastError = "Guide: ${t.message}"))
                status("Guide for ${source.name}: ${t.message ?: "failed"}", done = true)
            }
        }
    }

    private fun status(message: String?, done: Boolean = false) {
        android.util.Log.i("EdgeStream", message ?: "idle")
        _status.value = if (message == null) SyncStatus() else SyncStatus(running = !done, message = message)
    }

    /**
     * Swaps in a freshly downloaded lineup without losing guide links: each channel keeps the
     * guide channel it was resolved to last time (matched by its stable key), so the guide stays
     * filled while a playlist refresh runs instead of going blank until the next guide update.
     */
    private suspend fun replaceChannels(source: SourceEntity, channels: List<ChannelEntity>) {
        val previous = db.channels().guideLinks(source.id).associate { it.key to it.epgId }
        val linked = channels.map { c ->
            val old = previous[c.key]
            if (old != null && c.epgId == c.tvgId) c.copy(epgId = old) else c
        }
        db.channels().replaceForSource(source.id, linked)
        // Event/24-7 listings come from the names, so they can be refreshed right away.
        writeNameListings(source, db.channels().forSource(source.id), System.currentTimeMillis())
    }

    /** Listings for event, idle-slot and 24/7 channels, built from their names (see [NameListings]). */
    private suspend fun writeNameListings(source: SourceEntity, channels: List<ChannelEntity>, now: Long): Map<Long, String> {
        val resolved = HashMap<Long, String>()
        val updates = ArrayList<Pair<Long, String?>>()
        val listed = ArrayList<ProgrammeEntity>()
        channels.forEach { c ->
            if (epgMap.lookup(c.name, EpgMatcher.countryOf(c.name) ?: EpgMatcher.countryOf(c.tvgId)) != null) return@forEach
            val id = "name:${c.key}"
            NameListings.programmes(c, source.id, id, now)?.let { list ->
                listed += list; resolved[c.id] = id
                if (c.epgId != id) updates += c.id to id
            }
        }
        if (listed.isNotEmpty()) {
            // "24/7" channels generate a week of two-hour slots and idle event slots run a week
            // ahead; keep only the stored horizon and trim a row that would overshoot it.
            val horizon = now + GUIDE_AHEAD_MS
            val compact = listed
                .map { if (it.stop > horizon) it.copy(stop = horizon) else it }
                .filter { it.stop > now && it.start < horizon && it.stop > it.start }
                .sortedBy { it.start }
            listed.map { it.epgId }.distinct().chunked(500).forEach { db.programmes().deleteFor(source.id, it) }
            compact.chunked(500).forEach { db.programmes().insertAll(it) }
        }
        if (updates.isNotEmpty()) db.channels().setEpgIds(updates)
        return resolved
    }

    /** Applies the "English channels only" setting at import, so dropped channels never reach the guide. */
    private fun languageFiltered(channels: List<ChannelEntity>): List<ChannelEntity> =
        channels.filter { keepChannel(it) }

    /** Section headers and idle event slots are never real channels; with English-only, foreign ones go too. */
    fun keepChannel(c: ChannelEntity): Boolean =
        !NameListings.isHeader(c.name) && !NameListings.isIdleSlot(c.name) &&
            (!settings.value.englishOnly || EnglishFilter.isEnglish(c.name, c.groupName, c.tvgId))

    /**
     * Drops what the current settings exclude from an existing database right away, so a
     * library synced before English-only was switched on shrinks without a full re-sync.
     */
    suspend fun purgeExcluded() = withContext(Dispatchers.IO) {
        val drop = db.channels().all().filter { !keepChannel(it) }.map { it.id }
        val foreign = if (!settings.value.englishOnly) emptyList()
            else db.vod().categories().filter { !EnglishFilter.isEnglishVodCategory(it) }
        if (drop.isEmpty() && foreign.isEmpty()) return@withContext
        // One transaction: lists reload once, and never see the library half-deleted.
        db.withTransaction {
            drop.chunked(500).forEach { db.channels().deleteIds(it) }
            foreign.chunked(200).forEach { db.vod().deleteCategories(it) }
        }
        db.programmes().deleteOrphans()
    }

    private suspend fun refreshM3u(source: SourceEntity): SourceEntity {
        val result = Http.open(source.url) { M3uParser.parse(it) }
        val seen = HashMap<String, Int>()
        fun uniqueKey(base: String): String {
            val n = checkNotNull(seen.merge(base, 1, Int::plus))
            return if (n == 1) base else "$base#$n"
        }
        val channels = ArrayList<ChannelEntity>()
        val films = ArrayList<ProviderLibrary.Hit>()
        val episodes = ArrayList<ProviderLibrary.Hit>()
        result.entries.forEach { e ->
            if (e.isVod) {
                // Not a library to browse: an index Play looks titles up in.
                val ep = ProviderLibrary.episodeOf(e.name)
                if (ep != null) {
                    episodes += ProviderLibrary.Hit(ProviderLibrary.key(ep.first) + ProviderLibrary.episodeSuffix(ep.second, ep.third), null, e.url)
                } else {
                    films += ProviderLibrary.Hit(ProviderLibrary.key(e.name), ProviderLibrary.yearOf(e.name), e.url)
                }
            } else {
                channels += ChannelEntity(
                    key = uniqueKey("${source.id}:L:${e.name}"),
                    sourceId = source.id, name = e.name, groupName = e.group, logo = e.logo,
                    streamUrl = e.url, epgId = e.tvgId ?: e.tvgName, tvgId = e.tvgId ?: e.tvgName, number = channels.size + 1,
                    catchupDays = e.catchupDays,
                    catchupSource = when {
                        e.catchupDays <= 0 && e.catchup == null -> null
                        e.catchup.equals("flussonic", true) || e.catchup.equals("fs", true) -> "flussonic"
                        e.catchup.equals("shift", true) && e.catchupSource == null -> "shift"
                        e.catchup.equals("append", true) -> "append|${e.catchupSource.orEmpty()}"
                        else -> "default|${e.catchupSource.orEmpty()}"
                    },
                )
            }
        }
        val kept = languageFiltered(channels)
        replaceChannels(source, withLogos(kept))
        db.vod().deleteForSource(source.id)
        val titles = providerLibrary.write(source.id, "movie", films.asSequence()) + providerLibrary.write(source.id, "episode", episodes.asSequence())
        // What was kept, as Xtream reports: the raw playlist size hid a filter that dropped everything.
        return source.copy(headerEpgUrls = result.epgUrls.joinToString(","), channelCount = kept.size, vodCount = titles)
    }

    private suspend fun refreshXtream(source: SourceEntity): SourceEntity {
        val api = XtreamApi(source.url, Secrets.open(source.username), Secrets.open(source.password))
        val account = Http.withRetry { api.account() }
        var current = source.copy(
            serverTimezone = account.timezone, expiresAt = account.expires,
            accountStatus = account.status, maxConnections = account.maxConnections,
            activeConnections = account.activeConnections, isTrial = account.isTrial,
        )

        status("${source.name}: live channels…")
        val liveCats = Http.withRetry { api.categories("get_live_categories") }
        val liveFormat = settings.value.liveFormat
        val englishOnly = settings.value.englishOnly
        // English-only: ask the server for just the English categories, so foreign channels are
        // never downloaded at all. Some providers ignore category_id; there the whole list is
        // fetched once as a fallback and filtered below.
        val liveRaw = if (englishOnly) {
            val englishIds = liveCats.filterValues { EnglishFilter.isEnglishGroup(it) }.keys
            if (englishIds.isEmpty()) {
                Http.withRetry { api.liveStreams() }
            } else {
                val rows = ArrayList<Map<String, String>>()
                val seenIds = HashSet<String>()
                englishIds.forEach { id ->
                    runCatching { Http.withRetry(attempts = 2) { api.liveStreams(id) } }.getOrNull()?.forEach { row ->
                        // A provider that ignores category_id returns the whole list every time.
                        val streamId = row["stream_id"]
                        if (streamId == null || seenIds.add(streamId)) rows += row
                    }
                }
                rows.ifEmpty { Http.withRetry { api.liveStreams() } }
            }
        } else {
            Http.withRetry { api.liveStreams() }
        }
        val numbers = HashSet<Int>(liveRaw.size * 2)
        val channels = liveRaw.mapIndexedNotNull { i, s ->
            val id = s["stream_id"] ?: return@mapIndexedNotNull null
            val archive = s["tv_archive"] == "1"
            // Per-category responses may restart "num" at 1; keep the list keyed and ordered.
            val wanted = s["num"]?.toIntOrNull()
            val number = if (wanted != null && numbers.add(wanted)) wanted else {
                var n = i + 1
                while (!numbers.add(n)) n++
                n
            }
            ChannelEntity(
                key = "${source.id}:L:$id",
                sourceId = source.id,
                name = s["name"].orEmpty().ifBlank { "Channel $id" },
                groupName = liveCats[s["category_id"]] ?: "Uncategorised",
                logo = s["stream_icon"]?.ifBlank { null },
                streamUrl = api.liveUrl(id, liveFormat),
                epgId = s["epg_channel_id"]?.ifBlank { null },
                tvgId = s["epg_channel_id"]?.ifBlank { null },
                number = number,
                catchupDays = if (archive) s["tv_archive_duration"]?.toIntOrNull() ?: 0 else 0,
                catchupSource = if (archive) "xtream" else null,
                xtreamStreamId = id.toLongOrNull(),
            )
        }
        val kept = languageFiltered(channels)
        replaceChannels(source, withLogos(kept))
        // Live TV is usable right away; the guide keeps loading behind it. lastRefresh is only
        // stamped once the guide is in, so a load cut short is resumed on the next launch.
        current = current.copy(channelCount = kept.size, lastError = null)
        db.sources().upsert(current)

        // Films and series are not downloaded: On Demand browses the universal catalogue and
        // Play looks the provider's copy up on demand (index rebuilt after this refresh).
        db.vod().deleteForSource(source.id)
        providerLibrary.forget(source.id)
        return current.copy(vodCount = 0)
    }

    /** One request for the whole catalogue, or one per English category when English-only is on. */
    private fun actionsFor(action: String, cats: Map<String, String>, englishOnly: Boolean): List<String> =
        if (!englishOnly) listOf(action)
        else cats.filterValues { EnglishFilter.isEnglishVodCategory(it) }.keys.map { "$action&category_id=$it" }

    // ---- the provider's films and series, as a play source -----------------------------------

    /** The provider's stream for a film, from the first source that carries it; null when none does. */
    suspend fun providerMovie(title: String, year: String?): String? = withContext(Dispatchers.IO) {
        for (source in db.sources().all()) {
            val hit = when (source.type) {
                SourceType.XTREAM -> { ensureXtreamIndex(source, "movie"); providerLibrary.find(source.id, "movie", title, year) }
                else -> providerLibrary.find(source.id, "movie", title, year)
            }
            if (hit != null) return@withContext hit.ref
        }
        null
    }

    /** The provider's stream for one episode of a series; null when no source carries it. */
    suspend fun providerEpisode(title: String, year: String?, season: Int, episode: Int): String? = withContext(Dispatchers.IO) {
        for (source in db.sources().all()) {
            val url = when (source.type) {
                SourceType.XTREAM -> {
                    ensureXtreamIndex(source, "series")
                    val seriesId = providerLibrary.find(source.id, "series", title, year)?.ref?.toLongOrNull() ?: continue
                    runCatching { xtream(source).seriesInfo(seriesId) }.getOrNull()
                        ?.episodes?.firstOrNull { it.season == season && it.number == episode }?.url
                }
                else -> providerLibrary.find(source.id, "episode", title, null, ProviderLibrary.episodeSuffix(season, episode))?.ref
            }
            if (url != null) return@withContext url
        }
        null
    }

    private fun xtream(source: SourceEntity) = XtreamApi(source.url, Secrets.open(source.username), Secrets.open(source.password))

    /**
     * Xtream has no search, so the first lookup in a day fetches the source's title list once
     * (English categories only when English-only is on) into the index. One build per source at
     * a time; a failed build leaves the old index in place.
     */
    private suspend fun ensureXtreamIndex(source: SourceEntity, kind: String) {
        if (providerLibrary.isFresh(source.id, kind)) return
        val lock = synchronized(libraryLocks) { libraryLocks.getOrPut(source.id) { Mutex() } }
        lock.withLock {
            if (providerLibrary.isFresh(source.id, kind)) return
            val api = xtream(source)
            val englishOnly = settings.value.englishOnly
            val hits = ArrayList<ProviderLibrary.Hit>()
            runCatching {
                if (kind == "movie") {
                    val cats = Http.withRetry { api.categories("get_vod_categories") }
                    actionsFor("get_vod_streams", cats, englishOnly).forEach { action ->
                        api.stream(action) { s ->
                            val id = s["stream_id"] ?: return@stream
                            val name = s["name"].orEmpty()
                            hits += ProviderLibrary.Hit(
                                ProviderLibrary.key(name), s["year"]?.ifBlank { null } ?: ProviderLibrary.yearOf(name),
                                api.movieUrl(id, s["container_extension"]?.ifBlank { null } ?: "mp4"),
                            )
                        }
                    }
                } else {
                    val cats = Http.withRetry { api.categories("get_series_categories") }
                    actionsFor("get_series", cats, englishOnly).forEach { action ->
                        api.stream(action) { s ->
                            val id = s["series_id"] ?: return@stream
                            val name = s["name"].orEmpty()
                            hits += ProviderLibrary.Hit(
                                ProviderLibrary.key(name), s["releaseDate"]?.take(4)?.ifBlank { null } ?: s["year"]?.ifBlank { null } ?: ProviderLibrary.yearOf(name), id,
                            )
                        }
                    }
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("EdgeStream", "provider $kind index for ${source.name} failed", e)
                return
            }
            providerLibrary.write(source.id, kind, hits.asSequence())
        }
    }

    /**
     * Gives every channel its [ChannelIdentity] (stored as [ChannelEntity.identity]) and its logo:
     * the curated map's logo always wins; then the catalogue's real logo for the channel itself.
     * A local or regional feed (NBC's WTLV) keeps the provider's own logo, which shows the
     * station, and only takes the network's when it has none. iptv-org's name-only fill covers
     * what's left when fillLogos is on. Never fails the refresh.
     */
    private suspend fun withLogos(channels: List<ChannelEntity>): List<ChannelEntity> {
        val identities = ChannelIdentities.await()
        fun curated(c: ChannelEntity): String? =
            epgMap.logo(c.name, EpgMatcher.countryOf(c.name) ?: EpgMatcher.countryOf(c.tvgId))
        val resolved = channels.map { c ->
            val identity = identities.resolve(c.name, c.tvgId, c.groupName)
            val logo = curated(c)
                ?: identity?.logo?.takeIf { identity.feed == null || c.logo == null }
                ?: c.logo
            c.copy(identity = identity?.ref, logo = logo)
        }
        if (!settings.value.fillLogos || resolved.none { it.logo == null }) return resolved
        status("Finding channel logos…")
        val index = runCatching { PublicApis.logoIndex(cacheDir) }.getOrNull() ?: return resolved
        return resolved.map { c -> if (c.logo != null) c else c.copy(logo = index.find(c.epgId, c.name)) }
    }

    /**
     * Probes every channel of an M3U source and marks the ones that don't answer as offline.
     * Never run against Xtream accounts: thousands of requests could trip the provider's
     * connection limit or look like abuse.
     */
    suspend fun checkStreams(sourceId: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val source = db.sources().get(sourceId) ?: return@withContext 0 to 0
        if (source.type != SourceType.M3U) return@withContext 0 to 0
        val channels = db.channels().forSource(sourceId)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val dead = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val alive = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            channels.map { c ->
                pool.submit {
                    if (probe(c.streamUrl)) alive += c.id else dead += c.id
                    status("Checking ${source.name}: ${done.incrementAndGet()}/${channels.size}…")
                }
            }.forEach { it.get() }
        } finally { pool.shutdown() }
        if (dead.isNotEmpty()) db.channels().setOffline(dead.toList(), true)
        if (alive.isNotEmpty()) db.channels().setOffline(alive.toList(), false)
        status("${source.name}: ${alive.size} working, ${dead.size} offline", done = true)
        alive.size to dead.size
    }

    private fun probe(raw: String): Boolean = runCatching {
        val s = StreamUrl.parse(raw)
        val req = okhttp3.Request.Builder().url(s.url).header("Range", "bytes=0-2047")
            .apply { s.headers.forEach { (k, v) -> header(k, v) } }.build()
        Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build().newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** Adds iptv-org's free-to-air playlist, for one country or the whole world. */
    suspend fun addFreeChannels(countryCode: String?): Long {
        val name = if (countryCode != null) "Free channels · ${countryCode.uppercase()}" else "Free channels · Worldwide"
        val url = countryCode?.let { PublicApis.iptvOrgCountry(it) } ?: PublicApis.IPTV_ORG_ALL
        // The refresh checks small public lists for dead links once loaded.
        return insertSource(SourceEntity(name = name, type = SourceType.M3U, url = url))
    }

    // ---- guide -----------------------------------------------------------------------------

    private suspend fun refreshEpgLocked(source: SourceEntity) {
        // Two guides only: EdgeStream's merged guide first, then the provider's own (plus any extra
        // URL the viewer added). The provider's tvg-ids are often copied from other channels (AHC
        // tagged as AMC), so it only fills what the merged guide could not.
        val providerGuide = when (source.type) {
            SourceType.XTREAM -> listOf(XtreamApi(source.url, Secrets.open(source.username), Secrets.open(source.password)).xmltvUrl())
            else -> source.headerEpgUrls.split(',')
        }
        val explicit = (providerGuide + source.epgUrl.split(','))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val channels = languageFiltered(db.channels().forSource(source.id))
        // The merged guide is rebuilt on GitHub every 6 h from the public guides this lineup uses
        // (one download instead of ~30). Unreachable this time: the provider guide covers alone.
        val merged = mergedGuideAvailable()
        // Channels in the prebuilt map go straight to their id in the merged guide.
        val byId = channels.associateBy { it.id }
        fun channelCountry(c: ChannelEntity) = EpgMatcher.countryOf(c.name) ?: EpgMatcher.countryOf(c.tvgId)
        val premapped = HashMap<String, MutableMap<Long, String>>()
        channels.forEach { c ->
            (epgMap.lookup(c.name, channelCountry(c)) ?: ChannelIdentities.current?.get(c.identity)?.takeIf { it.feed == null }?.let { epgMap.lookup(it.name, it.country.lowercase()) })
                ?.let { (_, id) -> if (merged) premapped.getOrPut(MERGED_GUIDE) { HashMap() }[c.id] = id }
        }
        // The merged file keeps every source guide's own ids and names, so mapped and
        // name-matched channels both resolve against it unchanged.
        val guides = (listOfNotNull(if (merged) PublicApis.Guide(MERGED_GUIDE) else null) + explicit.map { PublicApis.Guide(it) }).distinctBy { it.url }
        val urls = guides.map { it.url }
        if (urls.isEmpty()) return

        val now = System.currentTimeMillis()
        val offset = settings.value.epgOffsetMinutes * 60_000L

        // channel row id -> guide channel id, decided by the first guide that knows the channel.
        val resolved = HashMap<Long, String>()
        // guide channel id -> catch-up days, so only those keep history.
        val catchupByEpg = HashMap<String, Int>()
        val epgUpdates = ArrayList<Pair<Long, String?>>()
        val logoUpdates = ArrayList<Pair<Long, String?>>()
        val logoQueued = HashSet<Long>()
        fun queueLogo(channelId: Long, logo: String) {
            val c = byId[channelId] ?: return
            if (c.logo == logo || !logoQueued.add(channelId)) return
            logoUpdates += channelId to logo
        }

        // Event, idle-slot and 24/7 channels: no guide lists them, but their names say what's on.
        resolved += writeNameListings(source, channels, now)

        // Apply the checked map before any guide download. The map has already picked the right
        // country, so an earlier guide can no longer claim the channel with a looser name match
        // (that is what put UK channels on US/IE listings and AHC on AMC Canada).
        val premappedIds = HashMap<String, MutableSet<String>>()
        premapped.forEach { (url, links) ->
            val ids = premappedIds.getOrPut(url) { HashSet() }
            links.forEach { (channelId, guideId) ->
                val c = byId[channelId] ?: return@forEach
                if (channelId in resolved) return@forEach
                resolved[channelId] = guideId
                ids += guideId
                if (c.catchupDays > 0) catchupByEpg[guideId] = maxOf(catchupByEpg[guideId] ?: 0, c.catchupDays.coerceAtMost(14))
                if (guideId != c.epgId) epgUpdates += channelId to guideId
                // Curated logo is the source of truth for premapped channels (same update path as runtime icons).
                epgMap.logo(c.name, channelCountry(c))?.let { queueLogo(channelId, it) }
            }
        }
        if (logoUpdates.isNotEmpty()) { db.channels().setLogos(logoUpdates.toList()); logoUpdates.clear() }

        val used = mutableListOf<String>()
        db.programmes().deleteOrphans()

        guides.forEachIndexed { idx, guide ->
            val url = guide.url
            val prewanted = premappedIds[url]
            if (resolved.size == channels.size && prewanted.isNullOrEmpty()) return@forEachIndexed
            status("Guide for ${source.name} (${idx + 1}/${urls.size})…")
            val wanted = prewanted?.toHashSet() ?: HashSet()
            val acc = GuideAccumulator(GUIDE_MAX_PER_CHANNEL)
            val filledBefore = resolved.size
            val began = System.nanoTime()
            try {
                Http.open(url) { stream ->
                    XmltvParser.parse(
                        stream,
                        onChannels = { xml ->
                            // The checked map was built against this guide; if an id vanished,
                            // release the channel so normal name matching can retry it.
                            premapped[url]?.forEach { (channelId, guideId) ->
                                if (guideId !in xml && resolved[channelId] == guideId) {
                                    resolved.remove(channelId)
                                    wanted.remove(guideId)
                                }
                            }
                            resolveChannels(
                                channels, xml, resolved, wanted, guide.region, epgUpdates, catchupByEpg,
                            )
                        },
                        onChannelIcons = { icons ->
                            // Runtime guide <icon>: apply to every channel resolved to an id this file carries.
                            resolved.forEach { (channelId, guideId) ->
                                icons[guideId]?.let { queueLogo(channelId, it) }
                            }
                        },
                        accept = { id, start, stop ->
                            val lookback = (catchupByEpg[id] ?: 0).toLong() * TimeUnit.DAYS.toMillis(1)
                            id in wanted && stop + offset > now - lookback && start + offset < now + GUIDE_AHEAD_MS
                        },
                        onProgramme = { p ->
                            val start = p.start + offset
                            // Some guides publish a single programme stretching weeks into the
                            // future. Keep its beginning on the grid, but stop it at the stored
                            // horizon so a few rows can't bloat the guide past 48 hours.
                            val stop = minOf(p.stop + offset, now + GUIDE_AHEAD_MS)
                            if (stop > start) acc.add(
                                ProgrammeEntity(
                                    sourceId = source.id, epgId = p.channelId, start = start, stop = stop,
                                    title = p.title, description = p.description?.take(GUIDE_DESCRIPTION_MAX),
                                    category = p.category, subtitle = p.subtitle, year = p.year, rating = p.rating,
                                ),
                                now,
                            )
                        },
                    )
                }
                val rows = acc.rows()
                if (rows.isNotEmpty()) {
                    // Delete only the ids this file actually refreshed. A guide that arrives with
                    // its <channel> list but no programmes (upstream still catching up) then keeps
                    // the previous rows instead of blanking the grid; stale ones age out by time.
                    val refreshed = rows.mapTo(HashSet()) { it.epgId }
                    // One transaction per guide file: Room publishes a single change, so the guide
                    // and now/next do not re-read after every 2,000 inserted rows.
                    db.withTransaction {
                        refreshed.chunked(400).forEach { db.programmes().deleteFor(source.id, it) }
                        rows.chunked(500).forEach { db.programmes().insertAll(it) }
                    }
                    used += url
                }
                // Debug builds: what each guide actually contributes, to decide which are worth fetching.
                if (Sports.traceOn) android.util.Log.d(
                    "EdgeEpg",
                    "${Http.redact(url).substringAfter("://").take(90)}: +${resolved.size - filledBefore} channels, ${rows.size} programmes, ${(System.nanoTime() - began) / 1_000_000} ms",
                )
                // Publish this guide's matches now, so channels light up as each file finishes.
                if (epgUpdates.isNotEmpty()) { db.channels().setEpgIds(epgUpdates.toList()); epgUpdates.clear() }
                if (logoUpdates.isNotEmpty()) { db.channels().setLogos(logoUpdates.toList()); logoUpdates.clear() }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // One unreachable guide shouldn't sink the others; only the provider's own guide is worth reporting.
                if (url in explicit) status("Guide ${idx + 1} failed: ${t.message}", done = true)
            }
        }
        // Channels no guide claimed this time go back to their own id instead of a stale match.
        // Live short-EPG ids ("xt:…") are kept: they come from the provider, not a guide file.
        // Name listings ("name:<key>") are rebuilt from the channel keys on every refresh; the
        // ones this refresh regenerated are already in [resolved]. Any other "name:" id is kept.
        channels.forEach { c ->
            val keep = c.epgId?.startsWith("xt:") == true ||
                (c.epgId?.startsWith("name:") == true && !NAME_LISTING_ID.containsMatchIn(c.epgId))
            if (c.id !in resolved && c.epgId != c.tvgId && !keep) epgUpdates += c.id to c.tvgId
        }
        if (epgUpdates.isNotEmpty()) db.channels().setEpgIds(epgUpdates)
        if (logoUpdates.isNotEmpty()) db.channels().setLogos(logoUpdates.toList())
        // A guide may still contain stale far-future rows; keep the same compact window.
        db.programmes().deleteStartingAfter(now + GUIDE_AHEAD_MS)
        db.programmes().deleteOlderThan(now - TimeUnit.DAYS.toMillis(15))
        db.programmes().deleteStartedBefore(now - TimeUnit.DAYS.toMillis(15))
        db.programmes().clampStop(now + GUIDE_AHEAD_MS)
        db.programmes().deleteOrphaned()
        trimGuide()
        db.sources().get(source.id)?.let {
            db.sources().upsert(it.copy(lastEpgRefresh = now, guideMatched = resolved.size, guideUrlsUsed = used.joinToString(",")))
        }
    }

    /**
     * Enforces [GUIDE_MAX_ROWS] so the guide can never creep back to the old 250k+ row size.
     * Called inside the refresh transaction and at start-up; the count check keeps it free when
     * the guide is already inside the ceiling.
     */
    private suspend fun trimGuide() {
        if (db.programmes().count() > GUIDE_MAX_ROWS) db.programmes().trimTo(GUIDE_MAX_ROWS)
    }

    /**
     * Keeps the stored guide to what the app renders: the near schedule for every channel, plus
     * catch-up history where the channel supports it. Runs at start-up so a database written by
     * an older build (300k+ rows) shrinks without waiting for a full guide download.
     */
    suspend fun compactGuide() = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val now = System.currentTimeMillis()
            db.withTransaction {
                // History only where the channel can actually play it back; keep the last hour
                // elsewhere so "just ended" still renders.
                db.programmes().deleteEnded(now - TimeUnit.HOURS.toMillis(1))
                db.programmes().deleteOlderThan(now - TimeUnit.DAYS.toMillis(14))
                db.programmes().deleteStartedBefore(now - TimeUnit.DAYS.toMillis(14))
                db.programmes().deleteStartingAfter(now + GUIDE_AHEAD_MS)
                db.programmes().clampStop(now + GUIDE_AHEAD_MS)
                db.programmes().deleteOrphaned()
                trimGuide()
            }
            // Deleting rows leaves the pages in the file. An old build's 300k-row guide was ~90 MB;
            // rewrite the file once the free space is worth reclaiming.
            val free = runCatching {
                db.openHelper.writableDatabase.query("PRAGMA freelist_count").use { if (it.moveToFirst()) it.getInt(0) else 0 }
            }.getOrDefault(0)
            if (free > 2_000) runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        }
    }

    /**
     * Maps still-unmatched channels onto this guide (see [EpgMatcher]). Channels mapped by an
     * earlier guide are left alone, so a second guide only fills gaps.
     */
    private fun resolveChannels(
        channels: List<ChannelEntity>,
        xml: Map<String, List<String>>,
        resolved: MutableMap<Long, String>,
        wanted: MutableSet<String>,
        guideRegion: String?,
        epgUpdates: MutableList<Pair<Long, String?>>,
        catchupByEpg: MutableMap<String, Int>,
    ) {
        fun link(c: ChannelEntity, id: String) {
            resolved[c.id] = id
            wanted += id
            if (c.catchupDays > 0) catchupByEpg[id] = maxOf(catchupByEpg[id] ?: 0, c.catchupDays.coerceAtMost(14))
            if (id != c.epgId) epgUpdates += c.id to id
        }
        if (xml.isEmpty()) {
            // A guide without <channel> entries: only exact ids can match.
            channels.forEach { c ->
                val id = c.tvgId ?: c.epgId
                if (c.id !in resolved && id != null) {
                    wanted += id
                    if (c.catchupDays > 0) catchupByEpg[id] = maxOf(catchupByEpg[id] ?: 0, c.catchupDays.coerceAtMost(14))
                }
            }
            return
        }
        val matcher = EpgMatcher(xml)
        channels.forEach { c ->
            if (c.id in resolved) return@forEach
            // Only the provider's tvg-id, never the stored epgId: epgId may be a stale match from
            // an older guide (that is how "US| AHC FHD" inherited AMC Canada's listings).
            val original = c.tvgId
            val match = if (guideRegion == null) {
                matcher.match(original, c.name)?.guideId ?: identityGuideMatch(matcher, c)
            } else {
                // A city guide only serves channels from its own state (or with no state at all).
                val region = EpgMatcher.regionOf(c.name, original)
                if (region != null && region != guideRegion) return@forEach
                matcher.match(null, EpgMatcher.withoutRegion(c.name))?.guideId
            } ?: return@forEach
            link(c, match)
        }
    }

    /**
     * A second try with the channel's catalogue identity: its real name and its iptv-org id,
     * which many guides use ("AU| FOX LEAGUE 502 HD" is Fox League, `FoxLeague.au`). Regional
     * and local feeds are skipped: the network's name would find the national schedule.
     */
    private fun identityGuideMatch(matcher: EpgMatcher, c: ChannelEntity): String? {
        val identity = ChannelIdentities.current?.get(c.identity)?.takeIf { it.feed == null } ?: return null
        return matcher.match(identity.id, "${identity.country}| ${identity.name}")?.guideId
    }

    suspend fun programmesFor(epgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity> =
        if (epgIds.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) { epgIds.chunked(400).flatMap { db.programmes().forChannels(it, from, to) } }

    suspend fun nowAndNext(): Map<String, Pair<ProgrammeEntity?, ProgrammeEntity?>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.programmes().nowAndNext(now, now + TimeUnit.HOURS.toMillis(6))
            .groupBy { it.epgId }
            .mapValues { (_, list) ->
                val current = list.firstOrNull { it.start <= now }
                val next = list.firstOrNull { it.start >= (current?.stop ?: now) && it !== current }
                current to next
            }
    }

    /**
     * For each game, the guide ids whose programme at kick-off names both teams. One query for
     * the whole window (matchup-looking programmes only), then each game is matched in memory;
     * only games from six hours ago to twelve ahead: further out the guide is thin.
     */
    suspend fun guideHits(games: List<Sports.Game>, now: Long = System.currentTimeMillis()): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            val began = System.nanoTime()
            val window = (now - GUIDE_BACK_MS)..(now + SPORTS_GUIDE_AHEAD_MS)
            val wanted = games.filter { it.state != Sports.State.FINAL && it.startMs in window }
            if (wanted.isEmpty()) return@withContext emptyMap()
            // Ten minutes in: past any pre-game show, still inside the broadcast.
            val times = wanted.map { it.startMs + 10 * 60_000L }
            val lines = db.programmes().matchupsBetween(times.min(), times.max() + 1)
            wanted.mapNotNull { g ->
                val at = g.startMs + 10 * 60_000L
                lines.filter { it.start <= at && it.stop > at && Sports.airsGame(it.title, it.subtitle, g) }
                    .map { it.epgId }.distinct().takeIf { it.isNotEmpty() }?.let { g.id to it }
            }.toMap().also {
                android.util.Log.i("EdgeStream", "guide matched ${it.size}/${wanted.size} games from ${lines.size} listings in ${(System.nanoTime() - began) / 1_000_000} ms")
            }
        }

    suspend fun searchProgrammes(q: String) = withContext(Dispatchers.IO) { db.programmes().search(q, System.currentTimeMillis()) }
    suspend fun searchChannels(q: String) = withContext(Dispatchers.IO) { db.channels().search(q) }
    suspend fun searchVod(q: String) = withContext(Dispatchers.IO) { db.vod().search(q).dedupeVodTitles() }

    // ---- catch-up --------------------------------------------------------------------------

    fun canCatchUp(channel: ChannelEntity, programme: ProgrammeEntity): Boolean {
        val now = System.currentTimeMillis()
        return channel.catchupSource != null && channel.catchupDays > 0 &&
            programme.start < now && programme.start > now - TimeUnit.DAYS.toMillis(channel.catchupDays.toLong())
    }

    suspend fun catchUpUrl(channel: ChannelEntity, programme: ProgrammeEntity): String? = withContext(Dispatchers.IO) {
        val type = channel.catchupSource ?: return@withContext null
        val start = programme.start
        val end = programme.stop.coerceAtMost(System.currentTimeMillis())
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(end - start).coerceAtLeast(1)
        when {
            type == "xtream" -> {
                val source = db.sources().get(channel.sourceId) ?: return@withContext null
                val id = channel.xtreamStreamId ?: return@withContext null
                XtreamApi.timeshiftUrl(
                    XtreamApi.normaliseBase(source.url), Secrets.open(source.username), Secrets.open(source.password),
                    id, start, durationMin, source.serverTimezone,
                )
            }
            type == "flussonic" -> {
                val s = start / 1000
                val d = (end - start) / 1000
                val u = channel.streamUrl
                when {
                    u.contains("/mpegts") -> u.replace("/mpegts", "/timeshift_abs-$s.ts")
                    Regex("""/[^/]*\.m3u8""").containsMatchIn(u) -> u.replace(Regex("""/[^/]*\.m3u8"""), "/archive-$s-$d.m3u8")
                    else -> u.trimEnd('/') + "/archive-$s-$d.m3u8"
                }
            }
            type == "shift" -> appendQuery(channel.streamUrl, "utc=${start / 1000}&lutc=${System.currentTimeMillis() / 1000}")
            type.startsWith("append|") -> channel.streamUrl + fillTemplate(type.substringAfter('|'), start, end)
            type.startsWith("default|") -> {
                val template = type.substringAfter('|')
                if (template.isBlank()) appendQuery(channel.streamUrl, "utc=${start / 1000}&lutc=${System.currentTimeMillis() / 1000}")
                else fillTemplate(template, start, end)
            }
            else -> null
        }
    }

    private fun appendQuery(url: String, q: String) = url + (if ('?' in url) "&" else "?") + q

    private fun fillTemplate(t: String, start: Long, end: Long): String {
        val now = System.currentTimeMillis() / 1000
        val s = start / 1000
        val e = end / 1000
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        fun two(v: Int) = v.toString().padStart(2, '0')
        return t
            .replace("\${start}", "$s").replace("{utc}", "$s").replace("{start}", "$s")
            .replace("\${end}", "$e").replace("{utcend}", "$e").replace("{end}", "$e")
            .replace("\${timestamp}", "$now").replace("{lutc}", "$now").replace("{now}", "$now")
            .replace("\${duration}", "${e - s}").replace("{duration}", "${e - s}")
            .replace("\${offset}", "${now - s}").replace("{offset}", "${now - s}")
            .replace("{Y}", "${cal.get(Calendar.YEAR)}").replace("{m}", two(cal.get(Calendar.MONTH) + 1))
            .replace("{d}", two(cal.get(Calendar.DAY_OF_MONTH))).replace("{H}", two(cal.get(Calendar.HOUR_OF_DAY)))
            .replace("{M}", two(cal.get(Calendar.MINUTE))).replace("{S}", two(cal.get(Calendar.SECOND)))
    }

    // ---- series ----------------------------------------------------------------------------

    suspend fun seriesInfo(item: VodEntity): XtreamApi.SeriesInfo? = withContext(Dispatchers.IO) {
        if (isCatalogue(item.key)) return@withContext catalogueSeries(item)
        val source = db.sources().get(item.sourceId) ?: return@withContext null
        if (source.type != SourceType.XTREAM) return@withContext null
        XtreamApi(source.url, Secrets.open(source.username), Secrets.open(source.password)).seriesInfo(item.xtreamId ?: return@withContext null)
    }

    suspend fun vodInfo(item: VodEntity): XtreamApi.VodInfo? = withContext(Dispatchers.IO) {
        val source = db.sources().get(item.sourceId) ?: return@withContext null
        if (source.type != SourceType.XTREAM || item.kind != VodKind.MOVIE) return@withContext null
        runCatching { XtreamApi(source.url, Secrets.open(source.username), Secrets.open(source.password)).vodInfo(item.xtreamId ?: return@withContext null) }.getOrNull()
    }

    /**
     * Guide for one channel: the stored XMLTV data, or the provider's short EPG when the
     * channel isn't covered by any guide file.
     */
    suspend fun channelGuide(channel: ChannelEntity, from: Long, to: Long, requireNow: Boolean = false): List<ProgrammeEntity> = withContext(Dispatchers.IO) {
        val stored = channel.epgId?.let { db.programmes().forChannel(it, from, to) }.orEmpty()
        val now = System.currentTimeMillis()
        // A guide file can run out or have a gap; [requireNow] asks the provider whenever nothing is airing.
        val covered = stored.isNotEmpty() && (!requireNow || stored.any { it.start <= now && it.stop > now })
        if (covered) return@withContext stored
        val id = channel.xtreamStreamId ?: return@withContext emptyList()
        val source = db.sources().get(channel.sourceId) ?: return@withContext emptyList()
        if (source.type != SourceType.XTREAM) return@withContext emptyList()
        val epgId = channel.epgId ?: "xt:$id"
        val live = runCatching {
            XtreamApi(source.url, Secrets.open(source.username), Secrets.open(source.password)).shortEpg(id, limit = 12).map {
                ProgrammeEntity(sourceId = source.id, epgId = epgId, start = it.start, stop = it.stop, title = it.title, description = it.description, category = null)
            }
        }.getOrDefault(emptyList())
        if (live.isNotEmpty()) {
            // Stored so now/next, the guide grid and search all see it, not just this one screen.
            db.programmes().replaceRange(epgId, live)
            if (channel.epgId == null) db.channels().setEpgIds(listOf(channel.id to epgId))
        }
        live.filter { it.stop > from && it.start < to }.ifEmpty { stored }
    }

    private val metaCache = ConcurrentHashMap<String, PublicApis.Meta>()

    /**
     * Everything we can learn about a film or show: the provider's own details first,
     * then TMDB (if the user added a key), then TVMaze for series.
     */
    suspend fun metadata(item: VodEntity): PublicApis.Meta = withContext(Dispatchers.IO) {
        metaCache[item.key]?.let { return@withContext it }
        val isSeries = item.kind == VodKind.SERIES
        val provider = vodInfo(item)?.let {
            PublicApis.Meta(
                plot = it.plot, backdrop = it.backdrop, cast = it.cast, director = it.director, genre = it.genre,
                durationMin = it.durationSecs?.let { s -> (s / 60).toInt() }, rating = it.rating,
                year = it.releaseDate?.take(4), trailer = it.trailer,
            )
        } ?: PublicApis.Meta(plot = item.plot, rating = item.rating, year = item.year)
        var meta = provider.orElse(catalogueMeta(item)).orElse(catalogueSeeds[item.key]).orElse(PublicApis.tmdb(settings.value.tmdbApiKey, item.name, isSeries, item.year))
        if (isSeries && (meta.plot == null || meta.genre == null)) meta = meta.orElse(PublicApis.tvMazeShow(item.name))
        metaCache[item.key] = meta
        meta
    }

    /** Metadata for a film key, or for the series behind an episode key. */
    // ---- setup transfer between TVs ------------------------------------------------------------

    suspend fun exportSetup(context: android.content.Context) = withContext(Dispatchers.IO) { com.edgestream.tv.remote.SetupTransfer.export(context, db) }
    suspend fun importSetup(context: android.content.Context, setup: org.json.JSONObject) =
        withContext(Dispatchers.IO) { com.edgestream.tv.remote.SetupTransfer.import(context, db, setup) }

    // ---- catalogue titles (On Demand) -------------------------------------------------------

    /**
     * A catalogue title the viewer opened, kept as a [VodEntity] (source 0, key "cat:M:tt…"/
     * "cat:S:tt…") so details, Continue Watching and the watch list treat it like any title.
     */
    suspend fun rememberCatalogueTitle(item: VodEntity, meta: PublicApis.Meta?) = withContext(Dispatchers.IO) {
        db.vod().insertAll(listOf(item))
        // The row already gave the wide art and plot; Cinemeta's full record still loads over them.
        if (meta != null) catalogueSeeds[item.key] = meta
    }

    private val catalogueSeeds = java.util.concurrent.ConcurrentHashMap<String, PublicApis.Meta>()

    private val tmdbImdb = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The IMDb id of a catalogue title: in its key for Cinemeta titles ("cat:M:tt…"), looked up
     * through TMDB for TMDB ones ("cat:S:tmdb:1399"). Blocking; call on an IO thread.
     */
    private fun imdbOf(item: VodEntity): String? = imdbOf(item.key, item.kind == VodKind.SERIES)

    /** The IMDb id behind a catalogue key (an episode key gives its series'), or null. */
    suspend fun catalogueImdb(key: String, series: Boolean): String? = withContext(Dispatchers.IO) { imdbOf(key.substringBefore(":E:"), series) }

    private fun imdbOf(key: String, series: Boolean): String? {
        if (!isCatalogue(key)) return null
        val id = key.substringAfter(':').substringAfter(':')
        if (id.startsWith("tt")) return id
        val tmdb = id.removePrefix("tmdb:").toLongOrNull() ?: return null
        val key = settings.value.tmdbApiKey.ifBlank { return null }
        val type = if (series) "tv" else "movie"
        tmdbImdb["$type/$tmdb"]?.let { return it }
        return runCatching {
            Http.client.newCall(okhttp3.Request.Builder().url("https://api.themoviedb.org/3/$type/$tmdb/external_ids?api_key=${java.net.URLEncoder.encode(key, "UTF-8")}").build())
                .execute().use { r -> if (r.isSuccessful) org.json.JSONObject(r.body.string()).optString("imdb_id").takeIf { it.startsWith("tt") } else null }
        }.getOrNull()?.also { tmdbImdb["$type/$tmdb"] = it }
    }

    /** Cinemeta (keyless) for a catalogue title: plot, art, cast, runtime. */
    private fun catalogueMeta(item: VodEntity): PublicApis.Meta? {
        val id = imdbOf(item) ?: return null
        val m = runCatching { Debrid.cinemetaMeta(if (item.kind == VodKind.SERIES) "series" else "movie", id) }.getOrNull() ?: return null
        return PublicApis.Meta(
            plot = m.description, poster = m.poster, backdrop = m.background,
            cast = m.cast.take(6).joinToString().ifEmpty { null }, director = m.director.joinToString().ifEmpty { null },
            genre = m.genres.take(3).joinToString().ifEmpty { null },
            durationMin = m.runtime?.filter { it.isDigit() }?.toIntOrNull(), rating = m.rating, year = m.year,
        )
    }

    /** A catalogue series' seasons and episodes from Cinemeta; streams are found when one is played. */
    private fun catalogueSeries(item: VodEntity): XtreamApi.SeriesInfo? {
        val id = imdbOf(item) ?: return null
        val m = Debrid.cinemetaMeta("series", id) ?: return null
        val episodes = m.episodes.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (se, e) ->
                XtreamApi.Episode(
                    id = "${se.first}x${se.second}", season = se.first, number = se.second,
                    title = e.name ?: "Episode ${se.second}", plot = e.overview, image = e.thumbnail, durationSecs = null, url = "",
                )
            }
        return XtreamApi.SeriesInfo(m.description, m.poster, episodes)
    }

    suspend fun metadataByKey(key: String): PublicApis.Meta? {
        val seriesKey = key.substringBefore(":E:", key)
        return vodByKey(seriesKey)?.let { metadata(it) }
    }

    // ---- user data -------------------------------------------------------------------------

    suspend fun toggleFavorite(key: String) = withContext(Dispatchers.IO) {
        if (db.user().isFavorite(key)) db.user().removeFavorite(key)
        else db.user().addFavorite(FavoriteEntity(key, System.currentTimeMillis()))
    }

    suspend fun markWatched(channel: ChannelEntity) = withContext(Dispatchers.IO) {
        db.user().touchHistory(HistoryEntity(channel.key, System.currentTimeMillis()))
    }

    suspend fun channelByKey(key: String) = withContext(Dispatchers.IO) { db.channels().byKey(key) }

    suspend fun progress(key: String) = withContext(Dispatchers.IO) { db.user().progress(key) }

    suspend fun progressMap(keys: Collection<String>): Map<String, ProgressEntity> =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) emptyMap()
            else keys.toList().chunked(400).flatMap { db.user().progressForKeys(it) }
                .associateBy { it.key }
        }

    suspend fun deleteProgress(key: String) = withContext(Dispatchers.IO) { db.user().deleteProgress(key) }
    suspend fun removeHistory(key: String) = withContext(Dispatchers.IO) { db.user().deleteHistory(key) }

    suspend fun saveProgress(p: ProgressEntity) = withContext(Dispatchers.IO) { db.user().saveProgress(p) }

    /** One row per title: providers often list the same film or series in several categories. */
    private fun List<VodEntity>.dedupeVodTitles(): List<VodEntity> = dedupeTitles(this, { it.name }, { it.year })


    companion object {
        /** Keys of titles from the universal catalogue rather than a provider's library. */
        fun isCatalogue(key: String) = key.startsWith("cat:")
    }
}
