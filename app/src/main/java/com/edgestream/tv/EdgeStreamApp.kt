package com.edgestream.tv

import android.app.Application
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.edgestream.tv.data.EpgMap
import com.edgestream.tv.data.Http
import com.edgestream.tv.data.Repository
import com.edgestream.tv.data.Settings
import com.edgestream.tv.data.db.EdgeDatabase
import com.edgestream.tv.player.PlayerEngine
import com.edgestream.tv.work.RefreshWorker

class EdgeStreamApp : Application(), SingletonImageLoader.Factory {
    lateinit var settings: Settings private set
    lateinit var repository: Repository private set
    lateinit var player: PlayerEngine private set
    /** Lives as long as the process: work started here isn't cut off by a screen or job ending. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val companion by lazy { com.edgestream.tv.remote.CompanionServer(this, repository) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        fun t() = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        val trace = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        fun mark(what: String) { if (trace) android.util.Log.d("EdgeStartup", "app: $what at ${t()} ms") }
        mark("onCreate begin")
        settings = Settings(this)
        com.edgestream.tv.data.RecommendedSetup.applyIfNeeded(settings, getSharedPreferences("edgestream", MODE_PRIVATE))
        com.edgestream.tv.ui.DeviceTier.init(this)
        com.edgestream.tv.ui.DeviceTier.setOverride(settings.value.effects)
        com.edgestream.tv.ui.FocusMemory.init(this)
        com.edgestream.tv.ui.UiSounds.init(this)
        com.edgestream.tv.data.SearchKit.init(this)
        PressMode.init(this)
        com.edgestream.tv.data.ChannelIdentities.init(this, appScope)
        // Warm start: the saved lineup parses while the first screen is still being created.
        com.edgestream.tv.ui.LineupSnapshot.init(this, appScope)
        mark("pre-database inits done")
        val db = EdgeDatabase.create(this)
        mark("database open")
        val map = EpgMap.load(this)
        mark("epg map loaded")
        repository = Repository(db, settings, cacheDir, map)
        player = PlayerEngine(this, settings)
        mark("repository + player built")
        // Warm the Sports hub from its last snapshot before the user opens it.
        com.edgestream.tv.data.Sports.init(this)
        com.edgestream.tv.data.Sports.traceOn = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        com.edgestream.tv.data.Profiles.init(this)
        com.edgestream.tv.data.SportsFollows.init(this)
        com.edgestream.tv.data.ChannelPins.init(this)
        com.edgestream.tv.data.MediaServers.init(this)
        com.edgestream.tv.data.WatchTracking.init(this)
        // One-time move onto the ESPN palette; any later choice sticks.
        val prefs = getSharedPreferences("edgestream", MODE_PRIVATE)
        if (!prefs.getBoolean("houseTheme", false)) {
            settings.update { it.copy(theme = com.edgestream.tv.ui.Palettes.Primetime.id) }
            prefs.edit().putBoolean("houseTheme", true).apply()
        }
        com.edgestream.tv.ui.Edge.palette = com.edgestream.tv.ui.Palettes.byId(settings.value.theme)
        RefreshWorker.schedule(this, settings.value.epgRefreshHours)
        // A load that was cut short (app killed by a cleaner, power loss) picks up again on launch.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // Let the first screen draw before a one-off guide compaction touches the database.
            kotlinx.coroutines.delay(15_000)
            // Housekeeping touches every programme row, so it runs at most once a day instead of
            // on every cold start (which delayed the first frame on large libraries).
            val last = prefs.getLong("lastGuideMaintenance", 0L)
            if (System.currentTimeMillis() - last > 24 * 3_600_000L) {
                // Older builds stored every programme in the guide window (300k+ rows). Trim it to
                // the compact schedule before anything else reads it.
                runCatching { repository.compactGuide() }
                // Settings may exclude things an older sync stored (foreign titles, idle event slots).
                runCatching { repository.purgeExcluded() }
                prefs.edit().putLong("lastGuideMaintenance", System.currentTimeMillis()).apply()
            }
            repository.unfinishedSources().forEach { RefreshWorker.runNow(this@EdgeStreamApp, it) }
        }
    }

    /** The home screen never exits, so give memory back when the system asks: images reload on demand. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION") // still delivered on the SHIELD's Android 11
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) SingletonImageLoader.get(this).memoryCache?.clear()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
                // Press mode: no third-party image loads anywhere; screens show their placeholders.
                add(coil3.intercept.Interceptor { chain ->
                    if (PressMode.on && !PressMode.isOwnArt(chain.request.data.toString())) coil3.request.ErrorResult(null, chain.request, IllegalStateException("press mode"))
                    else chain.proceed()
                })
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
            .build()

    companion object {
        lateinit var instance: EdgeStreamApp private set
    }
}
