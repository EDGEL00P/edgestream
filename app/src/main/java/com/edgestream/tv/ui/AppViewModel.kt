package com.edgestream.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgestream.tv.EdgeStreamApp
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.data.db.ProgressEntity
import com.edgestream.tv.data.db.SourceEntity
import com.edgestream.tv.data.db.VodEntity
import com.edgestream.tv.data.db.VodKind
import com.edgestream.tv.work.RefreshWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

typealias NowNext = Pair<ProgrammeEntity?, ProgrammeEntity?>

sealed interface NowPlaying {
    data class Live(val channel: ChannelEntity, val list: List<ChannelEntity>, val catchup: ProgrammeEntity? = null) : NowPlaying
    /** [saveUrl] is what Continue Watching stores: the provider's lasting link, not a TorBox link that expires. */
    data class Vod(
        val key: String, val title: String, val poster: String?, val url: String,
        val subtitle: String? = null, val saveUrl: String = url,
        /** External ids for optional TheIntroDB skip data. */
        val imdbId: String? = null, val tmdbId: Int? = null,
        val season: Int? = null, val episode: Int? = null,
    ) : NowPlaying
}

enum class Screen(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Rounded.Home), LIVE("TV Guide", Icons.Rounded.LiveTv), GUIDE("Guide", Icons.Rounded.CalendarViewWeek),
    MOVIES("Movies", Icons.Rounded.Movie), SERIES("TV Shows", Icons.Rounded.VideoLibrary), SPORTS("Sports", Icons.Rounded.SportsSoccer),
    TIMELINE("Schedule", Icons.Rounded.Timeline),
    BETTING("Odds", Icons.Rounded.QueryStats), RADAR("Radar", Icons.Rounded.Radar),
    DEBRID("Cloud Library", Icons.Rounded.CloudQueue), APPS("Apps", Icons.Rounded.GridView), SYSTEM("Device", Icons.Rounded.Speed),
    SEARCH("Search", Icons.Rounded.Search), SETTINGS("Settings", Icons.Rounded.Settings),
    CALENDAR("Planner", Icons.Rounded.CalendarMonth),
}

/** Everything derived from the provider's channel list: display names, badges and raw names. */
internal data class ChannelCatalog(
    val display: List<com.edgestream.tv.data.db.ChannelEntity>,
    val badges: Map<String, com.edgestream.tv.data.CleanName>,
    val rawNames: Map<String, String>,
    /** When a snapshot was taken (0 for the live pipeline's own catalogs). */
    val at: Long = 0,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AppViewModel : ViewModel(), com.edgestream.tv.remote.PhoneCommandTarget {
    private val app = EdgeStreamApp.instance
    val repo = app.repository
    val player = app.player
    private val settingsStore = app.settings

    val settings = settingsStore.flow
    val status = repo.status

    val sources: StateFlow<List<SourceEntity>?> = repo.sources.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Everything derived from the provider's channel list: display names, badges and raw names. */
    private val pipeline: kotlinx.coroutines.flow.Flow<ChannelCatalog> =
        combine(
            repo.channels,
            settings.map { Triple(it.cleanNames, it.nameRegex, it.englishOnly) }.distinctUntilChanged(),
            repo.favoriteKeys.distinctUntilChanged(),
        ) { all, (on, regex, englishOnly), favourites ->
            // Grouping and numbering read each channel's catalogue identity: never build the
            // lineup before the catalogue has loaded, or numbers cache their fallback guess.
            fun t() = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
            val trace = com.edgestream.tv.data.Sports.traceOn
            if (trace) android.util.Log.d("EdgeStartup", "lineup: ${all.size} channels from db at ${t()} ms")
            com.edgestream.tv.data.ChannelIdentities.await()
            if (trace) android.util.Log.d("EdgeStartup", "lineup: catalogue ready at ${t()} ms")
            // Language is judged on the raw name, before cleaning strips its "US|"/"FR|" prefix.
            val list = all.filter { repo.keepChannel(it) }
            if (trace) android.util.Log.d("EdgeStartup", "lineup: ${list.size} kept by language at ${t()} ms")
            val rawByKey = list.associate { it.key to it.name }
            val cleanInfos = HashMap<String, com.edgestream.tv.data.CleanName>(list.size)
            val cleanTitles = HashMap<String, String>(list.size)
            val display = if (!on) {
                list
            } else {
                val cleaner = com.edgestream.tv.data.NameCleaner(regex)
                list.map { ch ->
                    val clean = cleaner.clean(ch.name)
                    cleanInfos[ch.key] = clean
                    cleanTitles[ch.key] = clean.title
                    ch.copy(name = clean.title)
                }
            }
            if (trace) android.util.Log.d("EdgeStartup", "lineup: names cleaned at ${t()} ms")
            val deduped = com.edgestream.tv.data.ChannelDedupe.apply(display, favourites.toSet(), cleanTitles)
            val kept = deduped.mapTo(HashSet(deduped.size)) { it.key }
            if (trace) android.util.Log.d("EdgeStartup", "lineup: ${deduped.size} shown, cleaned at ${t()} ms")
            // Debug builds: the lineup's names for tools/epg (map rebuild, merged guide). Names and
            // guide ids only: no stream addresses, no logins.
            if (trace) runCatching {
                val out = org.json.JSONArray()
                list.forEach { c ->
                    out.put(org.json.JSONObject().put("name", c.name).put("groupName", c.groupName)
                        .put("epgId", c.epgId ?: org.json.JSONObject.NULL).put("tvgId", c.tvgId ?: org.json.JSONObject.NULL))
                }
                app.getExternalFilesDir(null)?.let { java.io.File(it, "lineup.json").writeText(out.toString()) }
            }
            ChannelCatalog(
                display = deduped,
                badges = if (!on) emptyMap() else cleanInfos.filterKeys { it in kept },
                rawNames = deduped.associate { it.key to (rawByKey[it.key] ?: it.name) },
                at = System.currentTimeMillis(),
            )
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    // Warm start: the saved lineup shows the moment the app opens; the real pipeline replaces it
    // when it lands. After that the warm value can never come back (liveArrived gate).
    private val liveArrived = MutableStateFlow(false)
    private val warmCatalog = MutableStateFlow<ChannelCatalog?>(null)
    private var snapshotSave: kotlinx.coroutines.Job? = null

    private val cleaned: StateFlow<ChannelCatalog> = kotlinx.coroutines.flow.merge(
        warmCatalog.filterNotNull().filter { !liveArrived.value }.take(1).onEach {
            if (com.edgestream.tv.data.Sports.traceOn) android.util.Log.d(
                "EdgeStartup", "lineup: warm snapshot ${it.display.size} channels at ${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} ms",
            )
        },
        pipeline.onEach { live ->
            val first = !liveArrived.value
            liveArrived.value = true
            if (com.edgestream.tv.data.Sports.traceOn && first) android.util.Log.d(
                "EdgeStartup", "lineup: live lineup ${live.display.size} channels at ${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} ms",
            )
            // Persist the finished lineup (debounced: favourite toggles must not hammer the disk).
            if (live.display.isNotEmpty()) {
                snapshotSave?.cancel()
                snapshotSave = viewModelScope.launch {
                    kotlinx.coroutines.delay(3_000)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LineupSnapshot.save(app, live) }
                }
            }
        },
    ).stateIn(viewModelScope, SharingStarted.Eagerly, ChannelCatalog(emptyList(), emptyMap(), emptyMap()))

    val channels: StateFlow<List<ChannelEntity>> = cleaned.map { it.display }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Original provider names, used where matching needs the full event text (sports, guide). */
    val rawChannelNames: StateFlow<Map<String, String>> =
        cleaned.map { it.rawNames }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Quality ("4K", "FHD"…) and tags ("Geo-blocked"…) for a channel key. */
    fun badge(key: String): com.edgestream.tv.data.CleanName? = cleaned.value.badges[key]

    /** Applies display cleaning to channels fetched straight from the database (e.g. search). */
    fun display(list: List<ChannelEntity>): List<ChannelEntity> {
        val s = settings.value
        if (!s.cleanNames) return list
        val c = com.edgestream.tv.data.NameCleaner(s.nameRegex)
        return list.map { it.copy(name = c.clean(it.name).title) }
    }
    val favoriteKeys: StateFlow<Set<String>> = repo.favoriteKeys.map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val historyKeys = repo.historyKeys.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val continueWatching: StateFlow<List<ProgressEntity>> = repo.continueWatching.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** The library: My List, watched episodes, last episode per series. */
    val library: StateFlow<com.edgestream.tv.data.LibraryState> = com.edgestream.tv.data.Library.state
    /** When the viewer dismisses the up-next card, auto-next is skipped for this episode key. */
    val suppressAutoNext = MutableStateFlow<String?>(null)
    /** Cached episode order for the series currently browsed, so up-next/play-next avoids a refetch. */
    private var episodeQueue: Pair<VodEntity, List<com.edgestream.tv.data.parse.XtreamApi.Episode>>? = null
    fun setEpisodeQueue(series: VodEntity, episodes: List<com.edgestream.tv.data.parse.XtreamApi.Episode>) {
        episodeQueue = series to episodes
    }
    fun cancelAutoNext() {
        (nowPlaying.value as? NowPlaying.Vod)?.let { suppressAutoNext.value = it.key }
    }

    /** Ticks every 30 s so progress bars and "on now" stay current. */
    val clock: StateFlow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    /**
     * Bumps when stored programmes change. Debounced: a guide refresh inserts in batches of 2,000,
     * and re-reading now/next after every batch starved the UI thread.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val guideVersion: StateFlow<Int> = repo.programmeCount.debounce(2_000)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val nowNext: StateFlow<Map<String, NowNext>> =
        combine(guideVersion, clock.map { it / 60_000 }) { count, minute -> count to minute }
            .mapLatest { repo.nowAndNext() }
            // A minute tick rarely changes what's on: only a programme boundary replaces the map,
            // so every screen that reads it skips the other 59 recompositions per hour.
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val liveEpgLimit = kotlinx.coroutines.sync.Semaphore(3)
    private val liveEpgTried = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Fills in a channel the guide files don't cover by asking the provider directly (Xtream
     * short EPG). Called for channels as they scroll on screen; each is retried at most every
     * 20 minutes, and only three requests run at once.
     */
    fun ensureLiveEpg(channel: ChannelEntity) {
        if (channel.xtreamStreamId == null) return
        val now = System.currentTimeMillis()
        if (channel.epgId != null && nowNext.value[channel.epgId]?.first != null) return
        val last = liveEpgTried[channel.key]
        if (last != null && now - last < 20 * 60_000L) return
        liveEpgTried[channel.key] = now
        viewModelScope.launch {
            liveEpgLimit.acquire()
            try { runCatching { repo.channelGuide(channel, now, now + 12 * 3_600_000L, requireNow = true) } } finally { liveEpgLimit.release() }
        }
    }

    /** Groups in display order, each with its channels already sorted. */
    private val byGroup: StateFlow<Map<String, List<ChannelEntity>>> =
        combine(channels, settings, historyKeys) { list, s, hist ->
            val recency = hist.withIndex().associate { (i, k) -> k to i }
            val visible = if (s.hideOffline) list.filter { !it.offline } else list
            // One classified bucket per channel, so Live and Guide show the same country/genre
            // hierarchy instead of each provider's inconsistent group names.
            val groups = com.edgestream.tv.data.ChannelTaxonomy.groupAll(visible).filterKeys { !(s.hideAdult && isAdult(it)) && it !in s.hiddenGroups }
                .mapValues { (_, chs) -> sortChannels(chs, s.channelSort, recency) }
            val ordered = when (s.groupSort) {
                "az" -> groups.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                "size" -> groups.entries.sortedByDescending { it.value.size }
                "watched" -> groups.entries.sortedWith(
                    compareBy<Map.Entry<String, List<ChannelEntity>>> { e -> e.value.minOfOrNull { recency[it.key] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
                )
                else -> groups.entries.toList() // provider order: groupBy keeps first-seen order
            }
            LinkedHashMap<String, List<ChannelEntity>>().apply { ordered.forEach { put(it.key, it.value) } }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Every group with its channel count, hidden ones included, for Settings › Channels › Groups. */
    val allGroups: StateFlow<List<Pair<String, Int>>> =
        combine(channels, settings.map { it.hideAdult to it.hideOffline }.distinctUntilChanged()) { list, (adult, offline) ->
            com.edgestream.tv.data.ChannelTaxonomy.groupAll(if (offline) list.filter { !it.offline } else list)
                .mapValues { it.value.size }
                .filterKeys { !(adult && isAdult(it)) }
                .toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setGroupHidden(group: String, hidden: Boolean) =
        updateSettings { it.copy(hiddenGroups = if (hidden) it.hiddenGroups + group else it.hiddenGroups - group) }

    private fun sortChannels(list: List<ChannelEntity>, mode: String, recency: Map<String, Int>): List<ChannelEntity> = when (mode) {
        "az" -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        "quality" -> list.sortedWith(compareBy({ QUALITY_RANK[badge(it.key)?.quality] ?: 4 }, { com.edgestream.tv.data.ChannelNumbers.lcn(it) }, { it.sourceId }, { it.number }))
        "watched" -> list.sortedWith(compareBy({ recency[it.key] ?: Int.MAX_VALUE }, { com.edgestream.tv.data.ChannelNumbers.lcn(it) }, { it.sourceId }, { it.number }))
        else -> list.sortedWith(compareBy({ com.edgestream.tv.data.ChannelNumbers.lcn(it) }, { it.sourceId }, { it.number }))
    }

    /** Real groups, preceded by Favourites / Recently watched / All channels. */
    val groups: StateFlow<List<Pair<String, Int>>> =
        combine(byGroup, favoriteKeys, historyKeys) { g, fav, hist ->
            buildList {
                add(FAVOURITES to fav.size)
                add(RECENT to hist.size)
                add(ALL to g.values.flatten().distinctBy { it.key }.size)
                g.forEach { (name, list) -> add(name to list.size) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The whole lineup sorted per the channel-sort setting, computed off the main thread once per change. */
    private val allSorted: StateFlow<List<ChannelEntity>> =
        combine(channels, settings, historyKeys) { list, s, hist ->
            sortChannels(list.distinctBy { it.key }, s.channelSort, hist.withIndex().associate { (i, k) -> k to i })
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun channelsIn(group: String): List<ChannelEntity> {
        val all = channels.value
        return when (group) {
            // A channel can sit in several groups; list it once here.
            ALL -> allSorted.value
            FAVOURITES -> { val byKey = all.associateBy { it.key }; favoriteKeys.value.mapNotNull { byKey[it] } }
            RECENT -> { val byKey = all.associateBy { it.key }; historyKeys.value.mapNotNull { byKey[it] } }
            else -> byGroup.value[group].orEmpty()
        }
    }

    val favouriteChannels: StateFlow<List<ChannelEntity>> = combine(channels, favoriteKeys) { all, fav ->
        val byKey = all.associateBy { it.key }; fav.mapNotNull { byKey[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentChannels: StateFlow<List<ChannelEntity>> = combine(channels, historyKeys) { all, hist ->
        val byKey = all.associateBy { it.key }; hist.mapNotNull { byKey[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- reminders ---------------------------------------------------------------------------

    private val reminderStore = com.edgestream.tv.data.ReminderStore(java.io.File(app.filesDir, "reminders.json"))
    val reminders: StateFlow<List<com.edgestream.tv.data.Reminder>> = reminderStore.all
    /** The reminder whose programme is starting, shown as a pop-up until watched or dismissed. */
    val dueReminder = MutableStateFlow<com.edgestream.tv.data.Reminder?>(null)
    /** A one-line confirmation ("Reminder set…") shown briefly at the bottom of the screen. */
    val notice = MutableStateFlow<String?>(null)
    private val shownReminders = HashSet<String>()

    init {
        // Warm start: the snapshot parse began in Application.onCreate; take it the moment the
        // ViewModel exists and publish it through warmCatalog.
        viewModelScope.launch { warmCatalog.value = LineupSnapshot.await() }
    }

    init {
        viewModelScope.launch {
            while (true) {
                if (dueReminder.value == null) {
                    reminderStore.due(System.currentTimeMillis()).firstOrNull { it.id !in shownReminders }?.let {
                        shownReminders += it.id; dueReminder.value = it
                    }
                }
                delay(5_000)
            }
        }
    }

    fun toggleReminder(ch: ChannelEntity, p: com.edgestream.tv.data.db.ProgrammeEntity) {
        val set = reminderStore.toggle(com.edgestream.tv.data.Reminder(ch.key, ch.name, p.title, p.start, p.stop))
        val when_ = "${com.edgestream.tv.ui.Format.day(p.start)} ${com.edgestream.tv.ui.Format.time(p.start, settings.value.use24h)}"
        showNotice(if (set) "Reminder set: ${p.title}, $when_ on ${ch.name}" else "Reminder cancelled: ${p.title}")
    }

    fun watchReminder() {
        val r = dueReminder.value ?: return
        dismissReminder()
        channels.value.firstOrNull { it.key == r.channelKey }?.let { playChannel(it, zapListFor(it)) }
    }

    fun dismissReminder() {
        dueReminder.value?.let { reminderStore.remove(it) }
        dueReminder.value = null
    }

    private var noticeJob: kotlinx.coroutines.Job? = null
    fun showNotice(text: String) {
        noticeJob?.cancel()
        notice.value = text
        noticeJob = viewModelScope.launch { delay(3_500); notice.value = null }
    }

    // ---- debrid library ----------------------------------------------------------------------

    sealed interface DebridState {
        data object NoKey : DebridState
        data object Loading : DebridState
        data class Ready(val library: com.edgestream.tv.data.Debrid.Library) : DebridState
        data class Failed(val message: String) : DebridState
    }

    val debrid = MutableStateFlow<DebridState>(DebridState.NoKey)
    private var debridJob: kotlinx.coroutines.Job? = null

    fun refreshDebrid() {
        val key = settings.value.torboxApiKey.trim()
        debridJob?.cancel()
        if (key.isEmpty()) { debrid.value = DebridState.NoKey; return }
        // Keep showing the old library while a refresh runs.
        if (debrid.value !is DebridState.Ready) debrid.value = DebridState.Loading
        debridJob = viewModelScope.launch {
            debrid.value = runCatching { com.edgestream.tv.data.Debrid.library(key, app.cacheDir) }
                .fold({ DebridState.Ready(it) }, { DebridState.Failed(it.message ?: "Couldn't reach the debrid service") })
        }
    }

    init {
        viewModelScope.launch {
            settings.map { it.torboxApiKey.trim() }.distinctUntilChanged().collect { kotlinx.coroutines.delay(600); refreshDebrid() }
        }
    }

    /** Approximate location from the public IP, used for weather and the local free-channel list. */
    private val location = flow { emit(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.edgestream.tv.data.PublicApis.location() }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The picked weather place, else a city typed in an older version, else the IP location. */
    private val weatherLocation = combine(location, settings.map { it.weatherPlace to it.weatherCity.trim() }.distinctUntilChanged()) { ip, chosen -> ip to chosen }
        .mapLatest { (ip, chosen) ->
            val (picked, city) = chosen
            when {
                picked != null -> picked.toLocation()
                city.isEmpty() -> ip
                else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.edgestream.tv.data.PublicApis.geocode(city) } ?: ip
            }
        }

    /** Resolved location for weather and the radar tab. */
    val place: StateFlow<com.edgestream.tv.data.PublicApis.Location?> =
        weatherLocation.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- calendar: the viewer's own events and alarms --------------------------------------

    // Each profile has its own calendar file ("main" keeps calendar.json).
    private val calendarStores = java.util.concurrent.ConcurrentHashMap<String, com.edgestream.tv.data.CalendarStore>()
    private fun calendarStore(profile: String = com.edgestream.tv.data.Profiles.active.value) = calendarStores.getOrPut(profile) {
        com.edgestream.tv.data.CalendarStore(java.io.File(app.filesDir, com.edgestream.tv.data.ProfileCodec.scoped("calendar", profile) + ".json"))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val myCalendar = com.edgestream.tv.data.Profiles.active.flatMapLatest { calendarStore(it).all }
    private val calendarPrefs = app.getSharedPreferences("edgestream", android.content.Context.MODE_PRIVATE)
    private fun profileKey(base: String) = com.edgestream.tv.data.ProfileCodec.scoped(base, com.edgestream.tv.data.Profiles.active.value)

    /** Followed-team fixtures the viewer asked to be alarmed about (15 minutes before). */
    private val gameAlarms = MutableStateFlow<Set<String>>(emptySet())

    /** Titles saved for later, per profile (provider copies and catalogue titles alike). */
    val watchList = MutableStateFlow<List<com.edgestream.tv.data.WatchItem>>(emptyList())

    init {
        com.edgestream.tv.data.Profiles.active.onEach {
            gameAlarms.value = calendarPrefs.getStringSet(profileKey("gameAlarms"), emptySet()).orEmpty()
            watchList.value = com.edgestream.tv.data.WatchListCodec.decode(calendarPrefs.getString(profileKey("watchList"), null))
            if (it == com.edgestream.tv.data.ProfileCodec.MAIN && !calendarPrefs.contains("watchList")) migrateOldMyList()
        }.launchIn(viewModelScope)
    }

    fun inWatchList(id: String) = watchList.value.any { it.id == id }

    /** One-time move of the old global "My List" keys into the main profile's watch list. */
    private fun migrateOldMyList() {
        val keys = com.edgestream.tv.data.Library.snapshot().myList
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val vods = repo.vodByKeys(keys.filter { ":M:" in it || ":S:" in it }).associateBy { it.key }
            val items = keys.mapNotNull { k ->
                vods[k]?.let { v -> com.edgestream.tv.data.WatchItem(k, com.edgestream.tv.data.NameCleaner.tidyTitle(v.name), v.year, v.poster, v.kind == com.edgestream.tv.data.db.VodKind.MOVIE, vodKey = k) }
                    ?: k.split('|').takeIf { (it.firstOrNull() == "movie" || it.firstOrNull() == "series") && it.size >= 2 }?.let { parts ->
                        // Cloud keys are "movie|<normalised title>|<year>"; the cloud screen resolves them.
                        com.edgestream.tv.data.WatchItem(k, parts[1].replaceFirstChar(Char::uppercase), parts.getOrNull(2)?.ifBlank { null }, null, parts[0] == "movie")
                    }
            }
            watchList.value = items
            calendarPrefs.edit().putString("watchList", com.edgestream.tv.data.WatchListCodec.encode(items)).apply()
        }
    }

    fun toggleWatchList(item: com.edgestream.tv.data.WatchItem) {
        val had = inWatchList(item.id)
        watchList.value = com.edgestream.tv.data.WatchListCodec.toggle(watchList.value, item)
        calendarPrefs.edit().putString(profileKey("watchList"), com.edgestream.tv.data.WatchListCodec.encode(watchList.value)).apply()
        showNotice(if (had) "Removed from My List" else "Added to My List: ${item.title}")
    }

    /** OK on a watch-list card: the provider's copy opens its details; anything else plays through debrid. */
    fun openWatchItem(w: com.edgestream.tv.data.WatchItem) {
        val key = w.vodKey
        if (key != null) viewModelScope.launch { repo.vodByKey(key)?.let { vodDetail.value = it } }
        else if (w.id.startsWith("movie|") || w.id.startsWith("series|")) screen.value = Screen.DEBRID
        else openUniversal(BrowseItem(w.id, w.title, w.year, w.poster, null, null, null, null, null, w.imdbId), w.movie)
    }

    fun toggleGameAlarm(id: String) {
        val on = id !in gameAlarms.value
        gameAlarms.value = if (on) gameAlarms.value + id else gameAlarms.value - id
        calendarPrefs.edit().putStringSet(profileKey("gameAlarms"), gameAlarms.value).apply()
        showNotice(if (on) "Alarm set: 15 minutes before kick-off" else "Alarm off")
    }

    /** Filled in by itself: public holidays where the viewer is, and every followed team's fixtures. Refreshed every 6 h. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val autoCalendar: StateFlow<List<com.edgestream.tv.data.CalendarEvent>> =
        combine(place, com.edgestream.tv.data.SportsFollows.state) { p, f -> p to f }
            .distinctUntilChanged()
            .flatMapLatest { (p, follows) ->
                flow {
                    while (true) {
                        val zone = java.time.ZoneId.systemDefault()
                        val holidays = p?.let { com.edgestream.tv.data.AutoCalendar.holidays(it.countryCode, it.region, java.time.LocalDate.now()) }.orEmpty()
                        val games = com.edgestream.tv.data.AutoCalendar.followedFixtures(follows, System.currentTimeMillis(), zone)
                        emit(holidays + games)
                        delay(6 * 3_600_000L)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Everything on the calendar: the viewer's entries plus the automatic ones (with any game alarms applied). */
    val calendarEvents: StateFlow<List<com.edgestream.tv.data.CalendarEvent>> =
        combine(myCalendar, autoCalendar, gameAlarms) { mine, auto, alarms ->
            mine + auto.map { if (it.id in alarms) it.copy(alarmMinutesBefore = 15) else it }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** The alarm ringing now (pop-up + sound) until dismissed or snoozed. */
    val ringingAlarm = MutableStateFlow<com.edgestream.tv.data.DueAlarm?>(null)
    private val alarmSound = com.edgestream.tv.system.AlarmSound(app)
    private val snoozed = MutableStateFlow<List<com.edgestream.tv.data.DueAlarm>>(emptyList())

    fun saveEvent(e: com.edgestream.tv.data.CalendarEvent) = calendarStore().put(e)
    fun deleteEvent(id: String) = calendarStore().delete(id)

    fun dismissAlarm() { alarmSound.stop(); ringingAlarm.value = null }

    fun snoozeAlarm(minutes: Int = 10) {
        val a = ringingAlarm.value ?: return
        snoozed.value = snoozed.value + a.copy(alarmAt = System.currentTimeMillis() + minutes * 60_000L)
        dismissAlarm()
        showNotice("Snoozed for $minutes minutes")
    }

    init {
        // Keep exactly one OS wake-up armed for the next alarm (or snooze), so it rings even
        // behind another app or after the process was killed.
        viewModelScope.launch {
            combine(calendarEvents, snoozed) { _, _ -> }.collect { armNextAlarm(System.currentTimeMillis()) }
        }
        // Ring what fell due since the last look (a missed alarm up to 10 minutes old still rings).
        viewModelScope.launch {
            var checked = System.currentTimeMillis() - 10 * 60_000L
            while (true) {
                val now = System.currentTimeMillis()
                val due = com.edgestream.tv.data.dueAlarms(calendarEvents.value, checked, now, java.time.ZoneId.systemDefault()) +
                    snoozed.value.filter { it.alarmAt <= now }
                checked = now
                if (due.isNotEmpty()) {
                    snoozed.value = snoozed.value.filter { it.alarmAt > now }
                    if (ringingAlarm.value == null) { ringingAlarm.value = due.last(); alarmSound.start() }
                    armNextAlarm(now)
                }
                delay(5_000)
            }
        }
    }

    private fun armNextAlarm(now: Long) {
        val next = listOfNotNull(
            com.edgestream.tv.data.nextAlarm(calendarEvents.value, now, java.time.ZoneId.systemDefault())?.alarmAt,
            snoozed.value.filter { it.alarmAt > now }.minOfOrNull { it.alarmAt },
        ).minOrNull()
        com.edgestream.tv.system.CalendarAlarms.schedule(app, next)
    }

    override fun onCleared() {
        alarmSound.stop(); secondPlayer?.release()
        if (com.edgestream.tv.remote.PhoneLink.answerer === questionAnswerer) com.edgestream.tv.remote.PhoneLink.answerer = null
        if (com.edgestream.tv.remote.PhoneLink.lineup === channels) com.edgestream.tv.remote.PhoneLink.lineup = null
        super.onCleared()
    }


    // ---- extreme weather alerts (real, keyless feeds) ------------------------------------
    val weatherAlert = MutableStateFlow<com.edgestream.tv.data.Trackers.Warning?>(null)
    private val seenWeatherAlerts = mutableSetOf<String>()
    fun dismissWeatherAlert() {
        weatherAlert.value?.let { seenWeatherAlerts += alertKey(it) }
        weatherAlert.value = null
    }
    private fun alertKey(w: com.edgestream.tv.data.Trackers.Warning) =
        "${w.source}|${w.severity}|${w.title}".take(180)
    private fun isExtreme(w: com.edgestream.tv.data.Trackers.Warning): Boolean {
        val s = "${w.severity} ${w.title} ${w.detail}".lowercase()
        return listOf("extreme", "severe", "tornado", "hurricane", "cyclone", "typhoon",
            "destructive", "emergency", "tsunami", "flash flood").any { it in s }
    }
    init {
        viewModelScope.launch {
            place.collectLatest { loc ->
                weatherAlert.value = null
                if (loc == null) return@collectLatest
                while (true) {
                    runCatching {
                        val cc = loc.countryCode ?: countryCode
                        val warnings = com.edgestream.tv.data.Trackers.warnings(cc, loc.lat, loc.lon)
                        val hit = warnings.firstOrNull { isExtreme(it) && alertKey(it) !in seenWeatherAlerts }
                        val cyclone = com.edgestream.tv.data.Trackers.cyclones(loc.lat, loc.lon)
                            .firstOrNull { it.km <= 50 && alertKey(com.edgestream.tv.data.Trackers.Warning(it.name, "", it.alert, "GDACS")) !in seenWeatherAlerts }
                        // Real lightning (Xweather) when keys present — proximity tiers.
                        val strikes = com.edgestream.tv.data.XweatherApi.nearby(
                            loc.lat, loc.lon,
                            clientId = settings.value.xweatherClientId,
                            secret = settings.value.xweatherSecret,
                        ).let { list ->
                            val nowMs = System.currentTimeMillis()
                            list.mapNotNull { s ->
                                val km = com.edgestream.tv.data.Storm.distanceKm(loc.lat, loc.lon, s.lat, s.lon)
                                if (km <= 50) s to km else null
                            }.sortedBy { it.second }
                        }
                        val nearestStrike = strikes.firstOrNull()
                        when {
                            hit != null && weatherAlert.value == null -> weatherAlert.value = hit
                            cyclone != null && weatherAlert.value == null -> weatherAlert.value =
                                com.edgestream.tv.data.Trackers.Warning(
                                    "Tropical cyclone ${cyclone.name}",
                                    "${cyclone.alert} · ${cyclone.km} km ${cyclone.bearing} · winds ${cyclone.windKph} km/h",
                                    "Extreme", "GDACS",
                                )
                            nearestStrike != null && nearestStrike.second <= 25 && weatherAlert.value == null -> {
                                val (s, km) = nearestStrike
                                val tier = if (km <= 10) "Imminent" else "Close"
                                val ageS = ((System.currentTimeMillis() - s.timestampMs) / 1000).coerceAtLeast(0)
                                weatherAlert.value = com.edgestream.tv.data.Trackers.Warning(
                                    "$tier lightning ${km.toInt()} km away",
                                    "Measured strikes nearby (Xweather) · last ${ageS}s ago${s.peakCurrent?.let { " · peak ${it.toInt()}kA" } ?: ""}",
                                    "Severe", "Xweather",
                                )
                            }
                        }
                    }
                    kotlinx.coroutines.delay(15 * 60_000L)
                }
            }
        }
    }

    val weather: StateFlow<com.edgestream.tv.data.PublicApis.Weather?> =
        combine(weatherLocation, settings.map { it.showWeather }, clock.map { it / 1_800_000 }) { loc, on, _ -> loc to on }
            .mapLatest { (loc, on) ->
                if (!on || loc == null) null
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.edgestream.tv.data.PublicApis.weather(loc) }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val countryCode: String? get() = location.value?.countryCode

    fun addFreeChannels(local: Boolean, done: () -> Unit = {}) = viewModelScope.launch {
        RefreshWorker.runNow(app, repo.addFreeChannels(if (local) countryCode else null)); done()
    }

    val latestMovies = repo.recentVod(VodKind.MOVIE).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val latestSeries = repo.recentVod(VodKind.SERIES).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- navigation & playback -------------------------------------------------------------

    /** Where the app opens. Never the guide: it's an overlay on live TV, not a place to start. */
    val screen = MutableStateFlow(
        Screen.entries.firstOrNull { it.name == settings.value.startScreen && it != Screen.GUIDE } ?: Screen.HOME,
    )
    val liveGroup = MutableStateFlow(ALL)
    val guideGroup = MutableStateFlow(ALL)
    /** Film or series whose details sheet is open, from any screen. */
    val vodDetail = MutableStateFlow<VodEntity?>(null)
    val fullscreen = MutableStateFlow(false)

    // ---- Multiview: two live channels side by side (the SHIELD's two decoders) --------------

    /** Left is the main player, right the second player; [audioRight] says whose sound plays. */
    data class Multiview(val left: ChannelEntity, val right: ChannelEntity, val audioRight: Boolean = false)

    val multiview = MutableStateFlow<Multiview?>(null)
    private var secondPlayer: com.edgestream.tv.player.SecondPlayer? = null
    val second: com.edgestream.tv.player.SecondPlayer? get() = secondPlayer

    /** Adds [right] beside the live channel already playing. */
    fun startMultiview(right: ChannelEntity) {
        val left = (nowPlaying.value as? NowPlaying.Live)?.channel
        if (left == null || player.state.value.url == null) { showNotice("Start a live channel first, then add a second game"); return }
        val p = secondPlayer ?: com.edgestream.tv.player.SecondPlayer(app).also { secondPlayer = it }
        player.resume() // the left picture may have been stopped while browsing
        p.play(right.streamUrl)
        multiview.value = Multiview(left, right)
        applyMultiviewAudio()
        fullscreen.value = true
    }

    fun setMultiviewAudio(right: Boolean) {
        multiview.value = multiview.value?.copy(audioRight = right)
        applyMultiviewAudio()
    }

    /** Changes one side's channel without leaving Multiview. */
    fun changeMultiviewPane(right: Boolean, channel: ChannelEntity) {
        val mv = multiview.value ?: return
        if (right) { secondPlayer?.play(channel.streamUrl); multiview.value = mv.copy(right = channel) }
        else { startLive(channel, listOf(channel)); multiview.value = mv.copy(left = channel) }
        applyMultiviewAudio()
    }

    /** Back to one picture: the side the viewer was on carries on full screen. */
    fun endMultiview(keepRight: Boolean) {
        val mv = multiview.value ?: return
        secondPlayer?.release(); secondPlayer = null
        multiview.value = null
        player.exo.volume = 1f
        if (keepRight) playChannel(mv.right, zapListFor(mv.right))
    }

    fun pauseMultiview() = secondPlayer?.stop()
    fun resumeMultiview() = secondPlayer?.resume()

    private fun applyMultiviewAudio() {
        val mv = multiview.value ?: return
        player.exo.volume = if (mv.audioRight) 0f else 1f
        secondPlayer?.exo?.volume = if (mv.audioRight) 1f else 0f
    }

    /** The guide drawn over full-screen video, as on a set-top box, rather than as its own screen. */
    val guideOverlay = MutableStateFlow(false)

    /** Guide button: over the picture while live TV plays, otherwise the guide screen. */
    fun openGuide() {
        val np = nowPlaying.value
        when {
            np is NowPlaying.Live && np.catchup == null -> { fullscreen.value = true; guideOverlay.value = true }
            // Full screen on a film or catch-up: leave the picture first, or the request is
            // swallowed — EdgeApp shows the player layer ahead of the screen switch.
            fullscreen.value -> { exitFullscreen(); screen.value = Screen.GUIDE }
            else -> screen.value = Screen.GUIDE
        }
    }

    init {
        viewModelScope.launch { fullscreen.collect { player.fullscreen.value = it; player.setSessionActive(it) } }
        // Previews are silent by default; sound comes in with full screen.
        viewModelScope.launch {
            combine(fullscreen, settings) { full, s -> full || s.previewSound }.collect { audible -> player.exo.volume = if (audible) 1f else 0f }
        }
    }

    val searchQuery = MutableStateFlow("")

    // Commands from the phone companion that need app state rather than a key press.
    private val phoneCommands = com.edgestream.tv.remote.PhoneCommands(this)

    init {
        viewModelScope.launch { com.edgestream.tv.remote.PhoneLink.commands.collect(phoneCommands::handle) }
    }

    override fun playFromPhone(channelKey: String) {
        val ch = channels.value.firstOrNull { it.key == channelKey } ?: return
        vodDetail.value = null
        playChannel(ch, zapListFor(ch))
    }

    override fun openSearch(query: String) {
        vodDetail.value = null
        if (fullscreen.value) exitFullscreen()
        searchQuery.value = query
        screen.value = Screen.SEARCH
    }

    override fun tuneNumber(number: Int) {
        vodDetail.value = null
        number.toString().forEach(channelPad::append)
        channelPad.commit()
    }

    override fun playNamed(name: String) {
        viewModelScope.launch {
            // "BBC One" should be BBC One, not BBC One Scotland: exact name first, then the shortest.
            val match = channelNamed(name)
                ?: repo.searchChannels(name).minByOrNull { if (it.name.equals(name, true)) 0 else it.name.length }
            if (match != null) playFromPhone(match.key) else openSearch(name)
        }
    }

    /**
     * The lineup channel a spoken name means. The catalogue knows other names and rebrands
     * ("Nat Geo", "Pick" is now Sky Mix), read in the viewer's own country first; then a channel
     * whose cleaned name is exactly what was said.
     */
    private fun channelNamed(spoken: String): ChannelEntity? {
        val list = channels.value
        val ids = com.edgestream.tv.data.ChannelIdentities.current
        val identity = ids?.resolve("${java.util.Locale.getDefault().country}| $spoken", null) ?: ids?.resolve(spoken, null)
        identity?.let { i ->
            list.filter { it.identity?.substringBefore('@') == i.id }.minByOrNull { if (it.identity == i.ref) 0 else 1 }
        }?.let { return it }
        val want = com.edgestream.tv.data.ChannelIdentities.keys(spoken).toSet()
        return list.filter { c -> com.edgestream.tv.data.ChannelIdentities.keys(c.name).any(want::contains) }.minByOrNull { it.name.length }
    }

    override fun openScreen(screen: Screen) {
        vodDetail.value = null
        if (fullscreen.value) exitFullscreen()
        this.screen.value = screen
    }
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)

    init {
        // Logins saved before encryption existed get encrypted in place, once.
        viewModelScope.launch { runCatching { repo.sealStoredLogins() } }
    }

    /** Set by the first key press; until then screens may place focus themselves as they load. */
    val remoteUsed = MutableStateFlow(false)
    private var progressJob: Job? = null
    private var restored = false

    init {
        // On launch the last channel comes back in the Home preview window, silently, rather than
        // taking over the screen: the user lands on Home and decides what to watch.
        viewModelScope.launch {
            val list = channels.first { it.isNotEmpty() }
            if (restored) return@launch
            restored = true
            val s = settings.value
            val key = s.lastChannelKey ?: return@launch
            if (!s.resumeLastChannel) return@launch
            val ch = list.firstOrNull { it.key == key } ?: return@launch
            preview(ch, zapListFor(ch))
        }
    }

    init {
        // Warm the image cache with the first channel logos and title art, so Live TV, the guide
        // and Home paint real artwork on first paint instead of placeholders. Small decode sizes
        // keep the preload light: the layouts ask Coil for their real size on demand.
        viewModelScope.launch {
            val urls = buildList {
                addAll(channels.first { it.isNotEmpty() }.mapNotNull { it.logo }.distinct().take(60))
                addAll(continueWatching.first().mapNotNull { it.poster }.take(6))
                addAll(latestMovies.first().mapNotNull { it.poster }.take(8))
            }.distinct()
            val loader = coil3.SingletonImageLoader.get(app)
            urls.forEach { u ->
                loader.enqueue(
                    coil3.request.ImageRequest.Builder(app)
                        .data(u)
                        .size(192)
                        .build(),
                )
            }
        }
    }

    fun epgFor(channel: ChannelEntity): NowNext? = channel.epgId?.let { nowNext.value[it] }

    /** How a live channel appears on the launcher: what's on now, then the channel. */
    private fun liveInfo(channel: ChannelEntity): com.edgestream.tv.player.NowPlayingInfo {
        val now = epgFor(channel)?.first
        return com.edgestream.tv.player.NowPlayingInfo(
            title = now?.title ?: channel.name,
            subtitle = if (now != null) channel.name else null,
            artworkUri = channel.logo,
            link = com.edgestream.tv.launcher.Links.live(channel.key),
            live = true,
        )
    }

    init {
        // When the programme changes on the channel being watched, the launcher's card follows.
        viewModelScope.launch {
            combine(nowPlaying, nowNext) { np, _ -> (np as? NowPlaying.Live)?.takeIf { it.catchup == null }?.channel }
                .collect { ch -> if (ch != null) player.updateInfo(liveInfo(ch)) }
        }
    }

    /** Plays [channel] in the small preview window without leaving the current screen. */
    fun preview(channel: ChannelEntity, list: List<ChannelEntity>) {
        if (!settings.value.livePreview) return
        val current = nowPlaying.value
        if (current is NowPlaying.Live && current.channel.key == channel.key && current.catchup == null) return
        startLive(channel, list)
    }

    fun playChannel(channel: ChannelEntity, list: List<ChannelEntity>, fullscreen: Boolean = true) {
        startLive(channel, list)
        viewModelScope.launch { repo.markWatched(channel) }
        settingsStore.update { it.copy(lastChannelKey = channel.key) }
        if (fullscreen) this.fullscreen.value = true
    }

    private fun startLive(channel: ChannelEntity, list: List<ChannelEntity>) {
        stopProgressTracking()
        nowPlaying.value = NowPlaying.Live(channel, list.ifEmpty { listOf(channel) })
        player.play(channel.streamUrl, channel.name, isLive = true, info = liveInfo(channel))
        ensureLiveEpg(channel)
    }

    /** Channel up/down within the list the current channel was opened from. */
    fun zap(delta: Int) {
        val np = nowPlaying.value as? NowPlaying.Live ?: return
        val list = np.list
        val idx = list.indexOfFirst { it.key == np.channel.key }
        if (idx < 0 || list.size < 2) return
        val next = list[(idx + delta).mod(list.size)]
        playChannel(next, list, fullscreen = true)
    }

    /** Channel number → best channel, rebuilt off the main thread once per lineup change. */
    val lcnIndex: StateFlow<Map<Int, ChannelEntity>> = channels
        .map { buildLcnIndex(it) }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Universal-remote number entry (101 → BBC 1 HD). */
    val channelPad = ChannelPad(viewModelScope, indexLoading = { channels.value.isEmpty() }) { jumpToNumber(it) }

    init {
        viewModelScope.launch { lcnIndex.collect(channelPad::setIndex) }
    }

    /** Live sports + racing for Home, Betting and Timeline: one refresh loop for the app. */
    val sportsFeed = com.edgestream.tv.data.LiveSportsFeed(
        viewModelScope,
        fetchGames = { com.edgestream.tv.data.Sports.games() },
        fetchRaces = { com.edgestream.tv.data.Racing.nextToGo() },
        initialGames = { com.edgestream.tv.data.Sports.cachedGames().orEmpty() },
    )

    /** The games every screen shows: the live feed, with scores hidden while the spoiler switch is on. */
    val sportsGames: StateFlow<List<com.edgestream.tv.data.Sports.Game>> =
        combine(sportsFeed.games, settings.map { it.hideScores }.distinctUntilChanged()) { games, hide ->
            if (hide) games.map(com.edgestream.tv.data.Sports::spoilerFree) else games
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The nflverse-backed NFL model, rebuilt every 6 h while the Betting board is open. */
    private val nflModel = flow {
        while (true) {
            fun t() = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
            // The lineup comes first: number entry, previews and "Watch on" all wait on it, and the
            // model's 20 MB parse would otherwise take the CPU it needs at launch. Capped so no
            // provider (no lineup) never blocks picks.
            kotlinx.coroutines.withTimeoutOrNull(90_000) { channels.first { it.isNotEmpty() } }
            if (com.edgestream.tv.data.Sports.traceOn) android.util.Log.d("EdgeStartup", "nfl model: start at ${t()} ms")
            com.edgestream.tv.data.NflPicks.loadModel(app.filesDir)?.let { emit(it) }
            if (com.edgestream.tv.data.Sports.traceOn) android.util.Log.d("EdgeStartup", "nfl model: done at ${t()} ms")
            delay(6 * 3_600_000L)
        }
    }

    /** The live channel previewing behind Home while a live game is focused there (null: highlights). */
    val preview = MutableStateFlow<ChannelEntity?>(null)

    /** An NFL game the Odds hub should open on (from Ask); the hub clears it once shown. */
    val nflFocus = MutableStateFlow<String?>(null)

    fun openNflPick(espnId: String) {
        nflFocus.value = espnId
        screen.value = Screen.BETTING
    }

    /** NFL Picks: model winners for upcoming games plus rule-based value picks. Score ticks don't rebuild it. */
    val nflBoard: StateFlow<com.edgestream.tv.data.NflPicks.Board?> =
        combine(
            nflModel,
            sportsFeed.games.map { games -> games.filter { it.league.id == "nfl" && it.state == com.edgestream.tv.data.Sports.State.UPCOMING } }
                .distinctUntilChanged { a, b -> a.map { it.id to it.line } == b.map { it.id to it.line } },
        ) { model, games -> model to games }
            .mapLatest { (model, games) -> com.edgestream.tv.data.NflPicks.board(model, games, app.filesDir) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Game id → guide ids airing it. Score ticks don't re-query: only a new slate or a new guide does. */
    private val sportsGuide: StateFlow<Map<String, List<String>>> =
        combine(
            sportsFeed.games.map { games -> games.filter { it.state != com.edgestream.tv.data.Sports.State.FINAL } }
                .distinctUntilChanged { a, b -> a.map { it.id to it.startMs } == b.map { it.id to it.startMs } },
            guideVersion,
        ) { games, _ -> games }
            .mapLatest { repo.guideHits(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The one game → channel matcher every sports screen reads, so they all agree. */
    val sportsLookup: StateFlow<com.edgestream.tv.data.Sports.ChannelLookup> =
        combine(channels, rawChannelNames, com.edgestream.tv.data.ChannelPins.state, sportsGuide) { c, raw, pins, guide ->
            com.edgestream.tv.data.Sports.ChannelLookup(c, raw, pins, guide, java.util.Locale.getDefault().country).also { lookup ->
                // Debug builds trace every game's choice once (EdgeMatch log), not just the ones on screen.
                if (com.edgestream.tv.data.Sports.traceOn && c.isNotEmpty()) sportsFeed.games.value.forEach { lookup.find(it) }
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.edgestream.tv.data.Sports.ChannelLookup(emptyList()))

    // ---- Glass shell: backdrop photo, forecast and the preview band ------------------------

    /** How long each backdrop photo stays on screen (its Ken Burns move takes the same time). */
    val BACKDROP_SHOT_MS = 90_000L

    /** Real highlight clips for the moving Home backdrop, refreshed every 3 hours. */
    val reels: StateFlow<List<com.edgestream.tv.data.SportsReels.Reel>> = flow {
        while (true) {
            com.edgestream.tv.data.SportsReels.load().takeIf { it.isNotEmpty() }?.let { emit(it) }
            delay(3 * 3_600_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The current backdrop photo and its frost; null until loaded (or offline with nothing cached). */
    val backdrop = MutableStateFlow<com.edgestream.tv.data.BackdropImages?>(null)

    /** Full weather (incl. the 5-day forecast) for the ambient widget; refreshed every 30 min. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val weatherDetail: StateFlow<com.edgestream.tv.data.PublicApis.WeatherDetail?> = place
        .flatMapLatest { loc ->
            if (loc == null) flowOf<com.edgestream.tv.data.PublicApis.WeatherDetail?>(null) else flow {
                while (true) {
                    emit(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.edgestream.tv.data.PublicApis.weatherDetail(loc)
                    })
                    delay(30 * 60_000L)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What another app is playing (Spotify, YouTube…), for the dock's Now Playing chip. */
    val otherNowPlaying: StateFlow<com.edgestream.tv.system.OtherNowPlaying?> =
        com.edgestream.tv.system.OtherAppsNowPlaying(app).state
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Headlines for the ticker (ported from EdgeLauncher): the country's news + sport feeds, every 15 min. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val headlines: StateFlow<List<com.edgestream.tv.data.Headline>> = place
        .map { it?.countryCode }
        .distinctUntilChanged()
        .flatMapLatest { cc ->
            flow {
                while (true) {
                    val feeds = com.edgestream.tv.data.News.sourcesFor(cc).map { src ->
                        runCatching { com.edgestream.tv.data.News.fetch(src) }.getOrDefault(emptyList())
                    }
                    // Alternate news and sport so neither hogs the strip.
                    val mixed = (0 until (feeds.maxOfOrNull { it.size } ?: 0)).flatMap { i -> feeds.mapNotNull { it.getOrNull(i) } }.take(16)
                    if (mixed.isNotEmpty()) emit(mixed)
                    delay(15 * 60_000L)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything the band can show, from flows the app already keeps (no new network calls). */
    val bandInputs: StateFlow<com.edgestream.tv.ui.shell.BandInputs> = combine(
        combine(sportsGames, favouriteChannels, recentChannels, nowNext) { g, f, r, nn -> listOf(g, f, r, nn) },
        combine(continueWatching, latestMovies, latestSeries, weatherAlert, weatherDetail) { c, m, s, a, w -> listOf(c, m, s, a, w) },
        watchList,
    ) { x, y, saved ->
        @Suppress("UNCHECKED_CAST")
        val nn = x[3] as Map<String, NowNext>
        @Suppress("UNCHECKED_CAST")
        com.edgestream.tv.ui.shell.BandInputs(
            games = x[0] as List<com.edgestream.tv.data.Sports.Game>,
            favourites = x[1] as List<ChannelEntity>,
            recent = x[2] as List<ChannelEntity>,
            nowTitle = { ch -> ch.epgId?.let { nn[it]?.first?.title } },
            resume = y[0] as List<ProgressEntity>,
            latestMovies = y[1] as List<VodEntity>,
            latestSeries = y[2] as List<VodEntity>,
            alert = y[3] as com.edgestream.tv.data.Trackers.Warning?,
            weather = y[4] as com.edgestream.tv.data.PublicApis.WeatherDetail?,
            forecast = (y[4] as com.edgestream.tv.data.PublicApis.WeatherDetail?)?.daily.orEmpty(),
            watchList = saved,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        com.edgestream.tv.ui.shell.BandInputs(emptyList(), emptyList(), emptyList(), { null }, emptyList(), emptyList(), emptyList(), null, null),
    )

    init {
        // Real game photos one after another (Ken Burns move on screen), a new one every 90 s. The
        // list is re-read after each lap so the rotation follows ESPN's news (refreshed every 6 h).
        viewModelScope.launch {
            var i = 0
            while (true) {
                val photos = com.edgestream.tv.data.Backdrop.loadAll(app)
                if (photos.isEmpty()) { delay(15 * 60_000L); continue }
                repeat(photos.size) { n ->
                    com.edgestream.tv.data.Backdrop.decode(photos[n], i)?.let { backdrop.value = it }
                    i++
                    delay(BACKDROP_SHOT_MS)
                }
            }
        }
    }

    /** Channel Up/Down list for a channel started without a browsed list. */
    fun zapListFor(channel: ChannelEntity): List<ChannelEntity> = zapListFor(channel, byGroup.value)

    fun jumpToNumber(number: Int): Boolean {
        // Fall back to a direct scan: the LCN index can lag the lineup at launch, or a channel
        // may only carry the provider's own number.
        val ch = lcnIndex.value[number]
            ?: channels.value.firstOrNull { com.edgestream.tv.data.ChannelNumbers.lcn(it) == number }
            ?: channels.value.firstOrNull { it.number == number }
        if (com.edgestream.tv.data.Sports.traceOn) {
            android.util.Log.d("EdgePad", "tune $number -> ${ch?.name ?: "none"} (index=${lcnIndex.value.size} channels=${channels.value.size})")
        }
        return if (ch != null) { playChannel(ch, zapListFor(ch)); true } else false
    }

    fun playCatchup(channel: ChannelEntity, programme: ProgrammeEntity, startPositionMs: Long = 0) {
        viewModelScope.launch {
            val url = repo.catchUpUrl(channel, programme) ?: return@launch
            stopProgressTracking()
            nowPlaying.value = NowPlaying.Live(channel, listOf(channel), catchup = programme)
            player.play(
                url, "${channel.name} · ${programme.title}", isLive = false,
                startPositionMs = startPositionMs,
                info = com.edgestream.tv.player.NowPlayingInfo(
                    programme.title, channel.name, channel.logo, com.edgestream.tv.launcher.Links.live(channel.key),
                ),
            )
            fullscreen.value = true
        }
    }

    fun playVod(
        key: String, title: String, poster: String?, url: String, subtitle: String? = null, fromStart: Boolean = false,
        saveUrl: String = url, fallbacks: List<String> = emptyList(),
        imdbId: String? = null, tmdbId: Int? = null, season: Int? = null, episode: Int? = null,
    ) {
        vodFallbacks = ArrayDeque(fallbacks)
        rememberLastEpisode(key, title, poster)
        // Starting an episode that was marked watched makes it unwatched again (rewatching).
        if (fromStart || key.contains(":E:") || key.startsWith("debrid:")) {
            if (com.edgestream.tv.data.Library.isWatched(key)) com.edgestream.tv.data.Library.markUnwatched(key)
        }
        viewModelScope.launch {
            val resumeAt = if (fromStart) 0 else repo.progress(key)?.positionMs ?: 0
            val resolvedTmdb = tmdbId ?: if (imdbId == null) {
                runCatching { repo.metadataByKey(key) }.getOrNull()?.tmdbId
            } else null
            // Skip segments need an id: without a TMDB key, a provider series only has one via TVMaze.
            val resolvedImdb = imdbId ?: if (resolvedTmdb == null) runCatching { repo.seriesImdb(key) }.getOrNull() else null
            // Continue Watching and other callers pass only the title ("Show · S1E2").
            val label = if (season == null || episode == null) episodeLabel.find(title) else null
            stopProgressTracking()
            nowPlaying.value = NowPlaying.Vod(
                key, title, poster, url, subtitle, saveUrl,
                imdbId = resolvedImdb, tmdbId = resolvedTmdb,
                season = season ?: label?.groupValues?.get(1)?.toIntOrNull(),
                episode = episode ?: label?.groupValues?.get(2)?.toIntOrNull(),
            )
            player.play(
                url, title, isLive = false, startPositionMs = resumeAt,
                info = com.edgestream.tv.player.NowPlayingInfo(title, subtitle, poster, com.edgestream.tv.launcher.Links.resume(key)),
            )
            fullscreen.value = true
            // With subtitles switched on, online ones are found and attached as soon as they arrive.
            if (settings.value.subtitlesOn) launch { loadOnlineSubtitles(select = true) }
            launch { trackStart() }
            progressJob = launch {
                while (isActive) {
                    delay(10_000)
                    saveVodProgress()
                    maybeAutoNextEpisode()
                }
            }
        }
    }

    /** The IMDb id of a film or episode's series: known from the catalogue, else found by title. */
    private suspend fun imdbFor(np: NowPlaying.Vod): String? = np.imdbId ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { com.edgestream.tv.data.Debrid.cinemetaFind(if (np.season == null) "movie" else "series", np.title.substringBefore(" · "), null) }.getOrNull()
    }

    /** What Trakt/Simkl are told is playing; set when playback starts, used when it stops. */
    private var tracked: com.edgestream.tv.data.WatchTracking.Watching? = null

    private suspend fun trackStart() {
        val links = com.edgestream.tv.data.WatchTracking.linked.value
        if (!links.trakt && !links.simkl) return
        val np = nowPlaying.value as? NowPlaying.Vod ?: return
        val imdb = imdbFor(np) ?: return
        val w = com.edgestream.tv.data.WatchTracking.Watching(imdb, np.season, np.episode)
        tracked = w
        com.edgestream.tv.data.WatchTracking.started(w, progressPercent(), settings.value.traktClientId)
    }

    private fun progressPercent(): Double {
        val dur = player.exo.duration.takeIf { it > 0 } ?: return 0.0
        return player.exo.currentPosition * 100.0 / dur
    }

    /** Online subtitles for the film or episode playing now: false when none were found. */
    suspend fun loadOnlineSubtitles(select: Boolean): Boolean {
        val np = nowPlaying.value as? NowPlaying.Vod ?: return false
        val imdb = imdbFor(np) ?: return false
        val subs = com.edgestream.tv.data.OnlineSubtitles.find(imdb, np.season, np.episode, settings.value.subtitleLanguage)
        if (subs.isEmpty() || (nowPlaying.value as? NowPlaying.Vod)?.url != np.url) return false
        player.addSubtitles(np.url, subs, select)
        return true
    }

    /** From the player's Subtitles list: fetch online subtitles now and switch the first on. */
    fun findOnlineSubtitles() {
        viewModelScope.launch {
            if (loadOnlineSubtitles(select = true)) showNotice("Subtitles added") else showNotice("No subtitles found for this title")
        }
    }

    private val episodeLabel = Regex(""" · S(\d+)E(\d+)$""")

    /** Remember the last episode per series so Up next + season tabs land on the right place. */
    private fun rememberLastEpisode(key: String, title: String, poster: String?) {
        val m = episodeLabel.find(title) ?: return
        val season = m.groupValues[1].toIntOrNull() ?: return
        val episode = m.groupValues[2].toIntOrNull() ?: return
        val seriesKey = when {
            key.contains(":E:") -> key.substringBefore(":E:", "")
            key.startsWith("debrid:") -> {
                val lib = (debrid.value as? DebridState.Ready)?.library
                val fileId = key.removePrefix("debrid:")
                lib?.series?.firstOrNull { s -> s.episodes.any { it.file.id == fileId } }?.key
            }
            else -> null
        } ?: return
        if (seriesKey.isBlank() || seriesKey == key) return
        com.edgestream.tv.data.Library.rememberEpisode(seriesKey, key, title, season, episode, poster)
    }

    private var lastAutoNext = 0L

    /** Binge: when an episode ends, the next one starts itself (provider + TorBox). */
    private suspend fun maybeAutoNextEpisode() {
        if (!settings.value.autoNextEpisode) return
        val vod = nowPlaying.value as? NowPlaying.Vod ?: return
        if (suppressAutoNext.value == vod.key) return
        val now = System.currentTimeMillis()
        if (now - lastAutoNext < 60_000L) return
        val exo = player.exo
        val dur = exo.duration.takeIf { it > 0 } ?: return
        if (exo.currentPosition < dur - 1_500) return
        lastAutoNext = now
        val next = findNextAfter(vod) ?: return
        next.play()
    }

    /** Next episode after [vod], resolved for provider series (Xtream) and TorBox series alike. */
    private suspend fun findNextAfter(vod: NowPlaying.Vod): UpNext? {
        if (vod.key.startsWith("debrid:")) {
            val lib = (debrid.value as? DebridState.Ready)?.library ?: return null
            val fileId = vod.key.removePrefix("debrid:")
            val series = lib.series.firstOrNull { s -> s.episodes.any { it.file.id == fileId } } ?: return null
            val current = series.episodes.firstOrNull { it.file.id == fileId } ?: return null
            // Next in season, else the first episode of the next season that exists in the cloud.
            val ordered = series.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            val idx = ordered.indexOfFirst { it.file.id == fileId }
            val next = ordered.getOrNull(idx + 1) ?: return null
            return UpNext(series.displayName, next.season, next.episode, next.meta?.name, next.meta?.thumbnail ?: series.poster) {
                playVod(
                    "debrid:${next.file.id}",
                    "${series.displayName} · S${next.season}E${next.episode}",
                    next.meta?.thumbnail ?: series.poster,
                    next.file.url,
                    next.meta?.name ?: "Episode ${next.episode}",
                    imdbId = series.meta?.imdbId, season = next.season, episode = next.episode,
                )
            }
        }
        if (!vod.key.contains(":E:")) return null
        val seriesKey = vod.key.substringBefore(":E:", "")
        val series = repo.vodByKey(seriesKey)?.takeIf { it.kind == VodKind.SERIES } ?: return null
        val episodes = episodeQueue?.takeIf { it.first.key == seriesKey }?.second
            ?: runCatching { repo.seriesInfo(series)?.episodes }.getOrNull() ?: return null
        if (episodes.isEmpty()) return null
        val epId = vod.key.substringAfterLast(":E:", "")
        val curIdx = episodes.indexOfFirst { it.id == epId }.takeIf { it >= 0 }
            ?: episodeLabel.find(vod.title)?.let { m ->
                episodes.indexOfFirst { it.season == m.groupValues[1].toIntOrNull() && it.number == m.groupValues[2].toIntOrNull() }
            }?.takeIf { it >= 0 } ?: return null
        val next = episodes.getOrNull(curIdx + 1) ?: return null
        return UpNext(com.edgestream.tv.data.NameCleaner.tidyTitle(series.name), next.season, next.number, com.edgestream.tv.data.NameCleaner.episodeTitle(next.title, series.name), next.image ?: series.poster) {
            playPreferred(episodeTarget(series, next))
        }
    }

    init {
        // A setup received from another TV replaced settings, profiles and providers under every
        // store already loaded in memory: restart so each one reads the new setup.
        viewModelScope.launch {
            com.edgestream.tv.remote.SetupReceiver.status.first { it is com.edgestream.tv.remote.SetupReceiver.Status.Done }
            delay(1_500)
            val app = com.edgestream.tv.EdgeStreamApp.instance
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return@launch
            val restart = android.app.PendingIntent.getActivity(app, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
            (app.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
                .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 600, restart)
            Runtime.getRuntime().exit(0)
        }
    }

    private val questionAnswerer: suspend (com.edgestream.tv.remote.VoiceCommand) -> String = { answer(it) }
    init {
        com.edgestream.tv.remote.PhoneLink.answerer = questionAnswerer
        com.edgestream.tv.remote.PhoneLink.lineup = channels
    }

    /**
     * A spoken question, answered from what the TV knows now; the answer also shows on the TV,
     * and "what's on" / "today's games" open the Guide / Sports.
     */
    private suspend fun answer(cmd: com.edgestream.tv.remote.VoiceCommand): String {
        val s = settings.value
        val text = when (cmd) {
            is com.edgestream.tv.remote.VoiceCommand.WhatsOn -> {
                val name = cmd.channel
                if (name == null) {
                    openScreen(Screen.LIVE)
                    "Opening the TV Guide"
                } else {
                    val ch = channelNamed(name)
                        ?: repo.searchChannels(name).minByOrNull { if (it.name.equals(name, true)) 0 else it.name.length }
                    if (ch == null) "I couldn't find a channel called $name."
                    else epgFor(ch).let { com.edgestream.tv.remote.VoiceAnswers.channel(ch.name, it?.first, it?.second, s.use24h) }
                }
            }
            com.edgestream.tv.remote.VoiceCommand.Games -> {
                openScreen(Screen.SPORTS)
                com.edgestream.tv.remote.VoiceAnswers.games(sportsGames.value, s.hideScores, s.use24h, System.currentTimeMillis())
            }
            is com.edgestream.tv.remote.VoiceCommand.Team -> {
                val game = com.edgestream.tv.remote.VoiceAnswers.findTeam(sportsGames.value, cmd.team)
                val channel = game?.let { sportsLookup.value.find(it) }?.let { "${it.name} (${com.edgestream.tv.data.ChannelNumbers.lcn(it)})" }
                com.edgestream.tv.remote.VoiceAnswers.team(cmd.team, game, channel, s.hideScores, s.use24h)
            }
            else -> return ""
        }
        showNotice(text)
        return text
    }

    /** The player's Next Episode button. */
    fun playNextEpisode() {
        val vod = nowPlaying.value as? NowPlaying.Vod ?: return
        viewModelScope.launch {
            val next = findNextAfter(vod)
            if (next == null) showNotice("This is the last episode") else next.play()
        }
    }

    /** The up-next target for the episode currently playing, for the player's next-episode card. */
    /** The episode after the one playing: what the Up Next card shows, and how to start it. */
    data class UpNext(val series: String, val season: Int, val episode: Int, val title: String?, val image: String?, val play: () -> Unit)

    suspend fun upNextForCurrent(): UpNext? {
        val vod = nowPlaying.value as? NowPlaying.Vod ?: return null
        return findNextAfter(vod)
    }

    /** The watch list ("My List") for a provider title. */
    fun toggleMyList(v: VodEntity) = toggleWatchList(
        com.edgestream.tv.data.WatchItem(v.key, com.edgestream.tv.data.NameCleaner.tidyTitle(v.name), v.year, v.poster, v.kind == com.edgestream.tv.data.db.VodKind.MOVIE, vodKey = v.key),
    )

    /** The watch list for a cloud-library or catalogue title. */
    fun toggleMyList(key: String, title: String, poster: String?, movie: Boolean, year: String? = null, imdbId: String? = null) =
        toggleWatchList(com.edgestream.tv.data.WatchItem(key, title, year, poster, movie, imdbId = imdbId))

    fun toggleWatched(key: String) {
        if (com.edgestream.tv.data.Library.isWatched(key)) {
            com.edgestream.tv.data.Library.markUnwatched(key)
            showNotice("Marked unwatched")
        } else {
            com.edgestream.tv.data.Library.markWatched(key)
            viewModelScope.launch { repo.deleteProgress(key) }
            showNotice("Marked watched")
        }
    }

    fun removeContinue(key: String) {
        viewModelScope.launch {
            repo.deleteProgress(key)
            showNotice("Removed from Continue Watching")
        }
    }

    /** Remove a channel from Recently Watched (house rule: delete wins over hiding). */
    fun removeRecent(key: String) {
        viewModelScope.launch {
            repo.removeHistory(key)
            showNotice("Removed from Recently Watched")
        }
    }

    fun playVod(item: VodEntity, fromStart: Boolean = false) =
        playVod(item.key, item.name, item.poster, item.streamUrl, item.year, fromStart)

    /** Continue Watching: rebuilds the film or episode so it gets the same TorBox-first treatment as Play. */
    fun resume(p: ProgressEntity) {
        viewModelScope.launch {
            val target = targetFor(p)
            if (target != null) playPreferred(target) else playVod(p.key, p.title, p.poster, p.streamUrl)
        }
    }

    // ---- TorBox versions via stream addons -------------------------------------------------

    /** A film or episode, with what's needed to find other versions of it. */
    data class VodTarget(
        val key: String, val title: String, val poster: String?, val subtitle: String?,
        /** The IPTV provider's own link; the fallback. */
        val providerUrl: String,
        val type: String, val name: String, val year: String?,
        val season: Int? = null, val episode: Int? = null,
        /** Known IMDb id (from the catalogue); skips the name lookup. */
        val imdbId: String? = null,
        /** Known TMDB id, when the caller already resolved one. */
        val tmdbId: Int? = null,
    )

    fun movieTarget(item: VodEntity) =
        VodTarget(item.key, item.name, item.poster, item.year, item.streamUrl, "movie", item.name, item.year, imdbId = catalogueImdb(item.key))

    fun episodeTarget(series: VodEntity, ep: com.edgestream.tv.data.parse.XtreamApi.Episode) = VodTarget(
        "${series.key}:E:${ep.id}", "${series.name} · S${ep.season}E${ep.number}", ep.image ?: series.poster, ep.title,
        ep.url, "series", series.name, series.year, ep.season, ep.number, imdbId = catalogueImdb(series.key),
    )

    /** "cat:M:tt0111161" → tt0111161: catalogue titles carry their IMDb id in the key. */
    private fun catalogueImdb(key: String) =
        key.substringBefore(":E:").substringAfter(':').substringAfter(':').takeIf { com.edgestream.tv.data.Repository.isCatalogue(key) && it.startsWith("tt") }

    /**
     * A catalogue title has no provider link until it's played: look it up on the provider
     * (by name and year, and season/episode for series) and fill it in. Unchanged when the
     * target already has one or the provider doesn't carry the title.
     */
    private suspend fun withProviderCopy(t: VodTarget): VodTarget {
        if (t.providerUrl.isNotBlank() || !com.edgestream.tv.data.Repository.isCatalogue(t.key)) return t
        val season = t.season
        val episode = t.episode
        // The viewer's own media server first (local, usually the best copy), then the provider.
        val imdb = t.imdbId ?: repo.catalogueImdb(t.key, series = season != null)
        val url = imdb?.let { com.edgestream.tv.data.MediaServers.stream(it, t.name, season, episode) }
            ?: if (season != null && episode != null) repo.providerEpisode(t.name, t.year, season, episode)
            else repo.providerMovie(t.name, t.year)
        return if (url != null) t.copy(providerUrl = url, imdbId = imdb ?: t.imdbId) else t
    }

    private suspend fun targetFor(p: ProgressEntity): VodTarget? {
        if (p.key.startsWith("debrid:")) {
            val lib = (debrid.value as? DebridState.Ready)?.library ?: return null
            val fileId = p.key.removePrefix("debrid:")
            val series = lib.series.firstOrNull { s -> s.episodes.any { it.file.id == fileId } }
            if (series != null) {
                val ep = series.episodes.firstOrNull { it.file.id == fileId } ?: return null
                return VodTarget(
                    p.key, p.title, p.poster, null, p.streamUrl, "series", series.displayName, series.displayYear,
                    season = ep.season, episode = ep.episode, imdbId = series.meta?.imdbId,
                )
            }
            val movie = lib.movies.firstOrNull { m -> m.files.any { it.id == fileId } }
            if (movie != null) {
                return VodTarget(
                    p.key, p.title, p.poster, null, p.streamUrl, "movie", movie.displayName, movie.displayYear,
                    imdbId = movie.meta?.imdbId,
                )
            }
            return null
        }
        val seriesKey = p.key.substringBefore(":E:", "")
        // A film from the provider's old downloaded library is gone from the database, but its
        // saved link still resumes it.
        if (seriesKey.isEmpty()) return repo.vodByKey(p.key)?.takeIf { it.kind == VodKind.MOVIE }?.let(::movieTarget)
            ?: p.streamUrl.takeIf { it.isNotBlank() }?.let { VodTarget(p.key, p.title, p.poster, null, it, "movie", p.title, null) }
        val series = repo.vodByKey(seriesKey) ?: return null
        val m = Regex(""" · S(\d+)E(\d+)$""").find(p.title) ?: return null
        return VodTarget(
            p.key, p.title, p.poster, null, p.streamUrl, "series", series.name, series.year,
            m.groupValues[1].toInt(), m.groupValues[2].toInt(),
        )
    }

    private val addonUrls: List<String>
        get() = settings.value.streamAddons.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val imdbIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** TorBox can be used at all: stream addons are set up, or there's a key for the user's own cloud library. */
    val debridAvailable: Boolean
        get() = addonUrls.isNotEmpty() || settings.value.torboxApiKey.isNotBlank()

    /**
     * Every TorBox version of [t], best first: copies already in the user's TorBox cloud, then
     * what their stream addons find (torrent and Usenet). Throws with a readable message.
     */
    private suspend fun findVersions(t: VodTarget): List<com.edgestream.tv.data.StreamAddons.Stream> {
        if (!debridAvailable) throw java.io.IOException("Add your debrid key or a stream addon in Settings → Extras.")
        val name = com.edgestream.tv.data.NameCleaner.tidyTitle(t.name)
        val cacheKey = "${t.type}|$name|${t.year}"
        val imdb = t.imdbId ?: imdbIds[cacheKey] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.edgestream.tv.data.Debrid.cinemetaFind(t.type, name, t.year) }.getOrNull()
        }?.also { imdbIds[cacheKey] = it }
        val cloud = cloudVersions(t, name, imdb)
        // Cloud copies are always ready and never expire: play them instantly, no addon wait.
        if (cloud.isNotEmpty()) return cloud
        val urls = addonUrls
        val found = if (urls.isEmpty() || imdb == null) emptyList() else {
            val id = if (t.type == "series") "$imdb:${t.season ?: 1}:${t.episode ?: 1}" else imdb
            com.edgestream.tv.data.StreamAddons.streams(urls, t.type, id)
        }
        if (cloud.isEmpty() && found.isEmpty()) {
            throw java.io.IOException(
                when {
                    urls.isEmpty() -> "Not in your cloud library. Add a stream addon in Settings → Extras to find more versions."
                    imdb == null -> "Couldn't match \"$name\" to a film or show on IMDb."
                    else -> "No cached debrid version of this title yet."
                },
            )
        }
        return cloud + found
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Matching copies already in the user's TorBox cloud: always ready, never expire. */
    private fun cloudVersions(t: VodTarget, name: String, imdb: String?): List<com.edgestream.tv.data.StreamAddons.Stream> {
        val lib = (debrid.value as? DebridState.Ready)?.library ?: return emptyList()
        fun matches(x: com.edgestream.tv.data.Debrid.Title) =
            (imdb != null && x.meta?.imdbId == imdb) ||
                (norm(x.displayName) == norm(name) && (t.year == null || x.displayYear == null || x.displayYear == t.year))
        val files = if (t.type == "series") {
            lib.series.filter(::matches).flatMap { s -> s.episodes.filter { it.season == t.season && it.episode == t.episode }.map { it.file } }
        } else {
            lib.movies.filter(::matches).flatMap { it.files }
        }
        return files.mapIndexed { i, f ->
            com.edgestream.tv.data.StreamAddons.Stream(
                addon = "Cloud", name = "Cloud Library", details = f.fileName, url = f.url, headers = emptyMap(),
                cached = true, usenet = f.id.startsWith("u"), resolution = com.edgestream.tv.data.StreamAddons.resolution(f.fileName),
                sizeBytes = f.size.takeIf { it > 0 }, hdr = Regex("HDR|DV|Dolby.?Vision", RegexOption.IGNORE_CASE).containsMatchIn(f.fileName),
                rank = -1000 + i,
            )
        }
    }

    /** Links still to try, in order, if the one playing fails: other TorBox versions, then the provider. */
    private var vodFallbacks = ArrayDeque<String>()

    private fun holdNotice(text: String) { noticeJob?.cancel(); notice.value = text }

    /**
     * The title being started while its copy is found (add-ons, the provider): shown as a dark
     * screen with the loading ring and the title, like a streaming app starting playback.
     */
    val starting = MutableStateFlow<String?>(null)
    /** The starting title's poster, so the loading screen shows its artwork. */
    val startingPoster = MutableStateFlow<String?>(null)
    /** What starting is doing right now, in words, for the loading screen. */
    val startStep = MutableStateFlow("")
    private var startJob: Job? = null

    /** Back on the starting screen: stop looking, play nothing. */
    fun cancelStarting() { startJob?.cancel(); starting.value = null; startingPoster.value = null }

    /**
     * Play, TorBox first: the best ready version from the user's addons, then the next two, then
     * the provider's own stream. Without addons it simply plays from the provider.
     */
    fun playPreferred(target: VodTarget, fromStart: Boolean = false) {
        if (!debridAvailable) { playProvider(target, fromStart); return }
        startJob?.cancel()
        startJob = viewModelScope.launch {
            starting.value = target.title
            startingPoster.value = target.poster
            startStep.value = "Finding the best copy"
            // Provider copy and TorBox versions run at the same time, not one after the other:
            // waiting for the provider check first was seconds of dead air on every play.
            val providerDeferred = async { runCatching { withProviderCopy(target) }.getOrDefault(target) }
            // Cleared however this ends: played, nothing found, or cancelled.
            val (t, found) = try {
                val found = runCatching { findVersions(target) }.getOrDefault(emptyList()).filter { it.autoPlayable }
                providerDeferred.await() to found
            } finally { starting.value = null; startingPoster.value = null }
            // runCatching above swallows cancellation: a Back pressed meanwhile must still win.
            ensureActive()
            val chain = found.take(3).map { it.playUrl } + listOfNotNull(t.providerUrl.ifBlank { null })
            if (chain.isEmpty()) { showNotice("${target.name} isn't available to play right now"); return@launch }
            playVod(
                t.key, t.title, t.poster, chain.first(), t.subtitle, fromStart,
                saveUrl = t.providerUrl.ifBlank { chain.first() }, fallbacks = chain.drop(1),
                imdbId = t.imdbId, tmdbId = t.tmdbId, season = t.season, episode = t.episode,
            )
        }
    }

    /** Straight from the IPTV provider, skipping TorBox. A catalogue title is looked up there first. */
    fun playProvider(target: VodTarget, fromStart: Boolean = false) {
        if (target.providerUrl.isBlank() && !com.edgestream.tv.data.Repository.isCatalogue(target.key)) { openVersions(target, fromStart); return }
        startJob?.cancel()
        startJob = viewModelScope.launch {
            if (target.providerUrl.isBlank()) { startStep.value = "Checking your provider"; starting.value = target.title; startingPoster.value = target.poster }
            val t = try { withProviderCopy(target) } finally { starting.value = null; startingPoster.value = null }
            ensureActive()
            if (t.providerUrl.isBlank()) {
                if (debridAvailable) openVersions(t, fromStart)
                else showNotice("${target.name} isn't on your provider. Add a stream add-on in Settings to play it.")
                return@launch
            }
            playVod(
                t.key, t.title, t.poster, t.providerUrl, t.subtitle, fromStart,
                imdbId = t.imdbId, tmdbId = t.tmdbId, season = t.season, episode = t.episode,
            )
        }
    }

    /** The "Sources" sheet: every source for a title, so the user can pick one by hand. */
    data class VersionSheet(
        val target: VodTarget, val fromStart: Boolean,
        val streams: List<com.edgestream.tv.data.StreamAddons.Stream>? = null, val message: String? = null,
    )
    val versionSheet = MutableStateFlow<VersionSheet?>(null)

    /**
     * A catalogue title opens its details page like any title: it is kept as a
     * title under a "cat:" key, and Play finds a copy on the provider or through the add-ons.
     */
    fun openUniversal(item: BrowseItem, movies: Boolean) {
        val title = VodEntity(
            key = "cat:${if (movies) "M" else "S"}:${item.imdbId ?: item.id}", sourceId = 0,
            kind = if (movies) VodKind.MOVIE else VodKind.SERIES, name = item.title, category = "Catalogue",
            poster = item.poster, streamUrl = "", rating = item.rating, year = item.year, plot = item.overview,
            added = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            repo.rememberCatalogueTitle(
                title, com.edgestream.tv.data.PublicApis.Meta(plot = item.overview, poster = item.poster, backdrop = item.backdrop, rating = item.rating, year = item.year),
            )
            vodDetail.value = title
        }
    }

    fun openVersions(target: VodTarget, fromStart: Boolean = false) {
        versionSheet.value = VersionSheet(target, fromStart)
        viewModelScope.launch {
            // The provider's copy is looked up alongside the add-ons, so it's offered in the sheet too.
            val provider = this.async { withProviderCopy(target) }
            val res = runCatching { findVersions(target) }
            val t = provider.await()
            val sheet = versionSheet.value ?: return@launch
            if (sheet.target.key != t.key) return@launch
            versionSheet.value = sheet.copy(
                target = t,
                streams = res.getOrNull().orEmpty(),
                message = res.exceptionOrNull()?.message
                    ?: if (res.getOrNull().isNullOrEmpty()) {
                        if (t.providerUrl.isBlank()) "No version of this title yet, on your provider or the add-ons." else "No add-on version yet; your provider has it."
                    } else null,
            )
        }
    }

    /** From the sheet: [stream] null means the provider's own link. Falls back to the provider if it fails. */
    fun playVersion(t: VodTarget, stream: com.edgestream.tv.data.StreamAddons.Stream?, fromStart: Boolean) {
        versionSheet.value = null
        vodDetail.value = null
        val provider = t.providerUrl.ifBlank { null }
        val first = stream?.playUrl ?: provider ?: return
        playVod(
            t.key, t.title, t.poster, first, t.subtitle, fromStart,
            saveUrl = provider ?: first, fallbacks = listOfNotNull(provider.takeIf { stream != null }),
            imdbId = t.imdbId, tmdbId = t.tmdbId, season = t.season, episode = t.episode,
        )
    }

    init {
        // A version that won't play (expired link, still downloading, codec trouble) hands over
        // to the next one in line, keeping the position.
        viewModelScope.launch {
            player.state.map { it.error to it.isLive }.distinctUntilChanged().collect { (error, live) ->
                if (error == null || live) return@collect
                val np = nowPlaying.value as? NowPlaying.Vod ?: return@collect
                val next = vodFallbacks.removeFirstOrNull() ?: return@collect
                val pos = player.exo.currentPosition.coerceAtLeast(0)
                nowPlaying.value = np.copy(url = next)
                player.play(
                    next, np.title, isLive = false, startPositionMs = pos,
                    info = com.edgestream.tv.player.NowPlayingInfo(np.title, np.subtitle, np.poster, com.edgestream.tv.launcher.Links.resume(np.key)),
                )
            }
        }
    }

    private var lastLauncherPublish = 0L

    /** Saves the position; the home screen's Continue watching card follows at most once a minute, and on stop. */
    fun saveVodProgress(final: Boolean = false) {
        val vod = nowPlaying.value as? NowPlaying.Vod ?: return
        val exo = player.exo
        val pos = exo.currentPosition
        val dur = exo.duration.takeIf { it > 0 } ?: 0
        if (pos <= 0) return
        val now = System.currentTimeMillis()
        val publish = final || now - lastLauncherPublish > 60_000
        if (publish) lastLauncherPublish = now
        viewModelScope.launch {
            // Finished counts as watched: drop it from Continue Watching so the next episode
            // takes its place and the episode gets a tick.
            val finished = dur > 0 && (pos > dur * 0.95 || dur - pos < 10_000)
            if (finished) {
                com.edgestream.tv.data.Library.markWatched(vod.key)
                repo.deleteProgress(vod.key)
                rememberLastEpisode(vod.key, vod.title, vod.poster)
                if (publish) com.edgestream.tv.launcher.TvLauncher.updateContinue(app, ProgressEntity(vod.key, vod.title, vod.poster, vod.saveUrl, dur, dur, now))
            } else {
                val p = ProgressEntity(vod.key, vod.title, vod.poster, vod.saveUrl, pos, dur, now)
                repo.saveProgress(p)
                if (publish) com.edgestream.tv.launcher.TvLauncher.updateContinue(app, p)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
        saveVodProgress(final = true)
        tracked?.let { w ->
            tracked = null
            val progress = progressPercent()
            val s = settings.value
            // Not tied to this screen: the stop must reach Trakt even as the player closes.
            com.edgestream.tv.EdgeStreamApp.instance.appScope.launch { com.edgestream.tv.data.WatchTracking.stopped(w, progress, s.traktClientId, s.simklClientId) }
        }
    }

    // ---- home screen (launcher) --------------------------------------------------------------

    init {
        // The launcher's Live TV row: favourites first, then recent channels, with what's on now.
        // Debounced, so a burst of guide updates rewrites the row once.
        viewModelScope.launch {
            combine(favouriteChannels, recentChannels, nowNext) { fav, rec, _ -> (fav + rec).distinctBy { it.key }.take(20) }
                .debounce(15_000)
                .collect { list ->
                    if (list.isNotEmpty()) com.edgestream.tv.launcher.TvLauncher.syncLive(app, list) { epgFor(it)?.first }
                }
        }
        // A home-screen card (or any edgestream:// link) opening the app.
        viewModelScope.launch {
            LauncherLinks.pending.collect { uri ->
                if (uri == null) return@collect
                LauncherLinks.pending.value = null
                restored = true // the link wins over "resume last channel"
                openLink(uri)
            }
        }
    }

    private suspend fun openLink(uri: android.net.Uri) {
        if (uri.scheme != com.edgestream.tv.launcher.Links.SCHEME) return
        val arg = uri.pathSegments.firstOrNull().orEmpty()
        vodDetail.value = null
        versionSheet.value = null
        when (uri.host) {
            "live" -> {
                val list = channels.first { it.isNotEmpty() }
                val ch = list.firstOrNull { it.key == arg } ?: return
                playChannel(ch, zapListFor(ch))
            }
            "resume" -> {
                val p = repo.progress(arg)
                when {
                    p != null -> resume(p)
                    else -> repo.vodByKey(arg)?.takeIf { it.kind == VodKind.MOVIE }?.let { playPreferred(movieTarget(it)) }
                }
            }
            "open" -> {
                if (fullscreen.value) exitFullscreen()
                Screen.entries.firstOrNull { it.name == arg && it != Screen.GUIDE }?.let { screen.value = it }
            }
            // Global/voice search forwards its query here.
            "search" -> {
                val q = uri.getQueryParameter("q").orEmpty()
                if (uri.getQueryParameter("guessed") == "1") {
                    com.edgestream.tv.data.AiAssist.interpret(q, settings.value.geminiApiKey)?.let { understood ->
                        openLink(com.edgestream.tv.launcher.Links.forCommand(understood, q))
                        return
                    }
                }
                if (fullscreen.value) exitFullscreen()
                if (q.isNotEmpty()) searchQuery.value = q
                screen.value = Screen.SEARCH
            }
            // Parsed voice commands from the Shield remote mic / global search.
            "command" -> {
                if (fullscreen.value) exitFullscreen()
                when (uri.getQueryParameter("action")) {
                    "tune" -> uri.getQueryParameter("n")?.toIntOrNull()?.let(::tuneNumber)
                    "watch" -> uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }?.let(::playNamed)
                    "open" -> Screen.entries.firstOrNull { it.name == uri.getQueryParameter("screen") && it != Screen.GUIDE }?.let { screen.value = it }
                    "search" -> openSearch(uri.getQueryParameter("q").orEmpty())
                    "games" -> screen.value = Screen.SPORTS
                    "team" -> uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }?.let(::openSearch)
                    "whatson" -> {
                        val ch = uri.getQueryParameter("channel").orEmpty()
                        if (ch.isNotBlank()) playNamed(ch) else screen.value = Screen.LIVE
                    }
                }
            }
        }
    }

    /** Leaving full screen: live TV keeps playing in the preview; films and catch-up stop. */
    fun exitFullscreen() {
        fullscreen.value = false
        when (val np = nowPlaying.value) {
            is NowPlaying.Vod -> { stopProgressTracking(); player.stop(); nowPlaying.value = null }
            is NowPlaying.Live -> if (np.catchup != null || !settings.value.livePreview) { player.stop(); nowPlaying.value = null }
            null -> Unit
        }
    }

    fun toggleFavorite(key: String) = viewModelScope.launch { repo.toggleFavorite(key) }

    // ---- sources & settings ----------------------------------------------------------------

    /** Saves the provider only once it is proven to work; [done] gets the reason when it doesn't. */
    fun addSource(source: SourceEntity, done: (error: String?) -> Unit) = viewModelScope.launch {
        val problem = repo.verifySource(source)
        if (problem == null) RefreshWorker.runNow(app, repo.insertSource(source))
        done(problem)
    }
    fun deleteSource(id: Long) = viewModelScope.launch { repo.deleteSource(id) }

    /** Updates a source's guide URL and triggers an EPG refresh with the new URL. */
    fun updateGuideUrl(id: Long, url: String, done: () -> Unit = {}) = viewModelScope.launch {
        repo.updateGuideUrl(id, url)
        RefreshWorker.runNow(app, id, epgOnly = true)
        done()
    }
    fun refreshSource(id: Long) = RefreshWorker.runNow(app, id)
    fun refreshEpg(id: Long) = RefreshWorker.runNow(app, id, epgOnly = true)
    fun refreshAll() = RefreshWorker.runNow(app)
    fun checkStreams(id: Long) = RefreshWorker.runNow(app, id, checkStreams = true)

    fun updateSettings(transform: (com.edgestream.tv.data.AppSettings) -> com.edgestream.tv.data.AppSettings) {
        val before = settings.value
        settingsStore.update(transform)
        val after = settings.value
        player.applyPreferences(after)
        if (before.theme != after.theme) Edge.palette = Palettes.byId(after.theme)
        if (before.epgRefreshHours != after.epgRefreshHours) RefreshWorker.schedule(app, after.epgRefreshHours)
        // Switching English-only on shrinks the stored library at once; off needs a re-sync to refill.
        if (!before.englishOnly && after.englishOnly) viewModelScope.launch { repo.purgeExcluded() }
    }

    companion object {
        private val QUALITY_RANK = mapOf("4K" to 0, "FHD" to 1, "HD" to 2, "SD" to 3)
        private val adultPattern = Regex("""(?<![a-z])(xxx|adults?|porn|18\+)(?![a-z])""", RegexOption.IGNORE_CASE)
        fun isAdult(group: String) = adultPattern.containsMatchIn(group)

        const val FAVOURITES = "★  Favourites"
        const val RECENT = "↺  Recently watched"
        const val ALL = "≡  All channels"
    }
}
