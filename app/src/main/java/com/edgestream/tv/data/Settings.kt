package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val userAgent: String = Http.DEFAULT_USER_AGENT,
    /** Xtream live container: "ts" (lowest latency) or "m3u8" (better for flaky networks). */
    val liveFormat: String = "ts",
    val resumeLastChannel: Boolean = true,
    val livePreview: Boolean = true,
    val use24h: Boolean = true,
    /** Sound from the moving highlights behind Home (quiet; off by default). */
    val backdropSound: Boolean = false,
    /** Spoiler switch: every score in the app shows as "–" until turned off. */
    val hideScores: Boolean = false,
    val epgRefreshHours: Int = 12,
    /** Live target offset and read-ahead, in milliseconds (not whole seconds). */
    val bufferMs: Int = 2_000,
    val lastChannelKey: String? = null,
    /** ISO 639 code such as "en"; blank = stream default. */
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    val subtitlesOn: Boolean = false,
    /** Generate English captions on the TV from the programme's audio. */
    val autoCaptions: Boolean = false,
    /** Shifts every guide time; fixes providers whose XMLTV is in the wrong timezone. */
    val epgOffsetMinutes: Int = 0,
    /** Screen name from ui.Screen to open on launch. */
    val startScreen: String = "HOME",
    val hideAdult: Boolean = true,
    /** Live TV / Guide groups the viewer switched off (Settings › Channels › Groups). */
    val hiddenGroups: Set<String> = emptySet(),
    /** Keep only English-language live channels; the guide is then matched only for those. */
    val englishOnly: Boolean = true,
    /** Free personal key from themoviedb.org; enables film/series backdrops, cast and ratings. */
    val tmdbApiKey: String = "",
    /** The viewer's own Trakt app (trakt.tv/oauth/applications) and Simkl app (simkl.com/settings/developer). */
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val simklClientId: String = "",
    /** Xweather (Vaisala) keys for real lightning strikes; blank = derived CAPE/LI risk. Encrypted at rest. */
    val xweatherClientId: String = "",
    val xweatherSecret: String = "",
    /** TorBox API key for the debrid library; blank hides it. */
    val torboxApiKey: String = "",
    /** Stream add-ons (manifest URLs, one per line) that find TorBox versions of films and episodes. */
    val streamAddons: String = "",
    val showWeather: Boolean = true,
    /** Place picked for the weather; null means locate automatically by IP (or [weatherCity]). */
    val weatherPlace: PublicApis.Place? = null,
    /** City typed before the picker existed; still honoured until a place is picked. */
    val weatherCity: String = "",
    val fahrenheit: Boolean = false,
    /** Fill in missing channel logos from the iptv-org public database. */
    val fillLogos: Boolean = true,
    /** Tidy channel names for display (prefixes, quality suffixes, [tags]). */
    val cleanNames: Boolean = true,
    /** Extra user regex removed from every channel name. */
    val nameRegex: String = "",
    /** "az", "provider", "size" or "watched". Country-prefixed groups read best alphabetically. */
    val groupSort: String = "az",
    /** "number", "az", "quality" or "watched". */
    val channelSort: String = "number",
    /** Add free public guides (epg.pw, epgshare01, i.mjh.nz) for channels the provider's guide misses. */
    val autoGuides: Boolean = true,
    /** Hide channels a stream check found offline. */
    val hideOffline: Boolean = true,
    /** Switch the TV's refresh rate to the video's (24/25/50/60 fps): no judder, brief blank on change. */
    val matchFrameRate: Boolean = false,
    /** Hardware A/V tunnelling: smoother sync on SHIELD/Sony, but some streams misbehave. */
    val tunneling: Boolean = false,
    /** Palette id from ui.Palettes. */
    val theme: String = "primetime",
    /** Safe area for TVs that crop the edges, as a percentage of screen width/height. */
    val overscanPercent: Int = 5,
    /** Radar base: false = dark NEXRAD-style, true = Esri satellite. */
    val radarSatellite: Boolean = false,
    /** Focus effects: "auto", "full", "balanced" or "low". */
    val effects: String = "auto",
    /** Previews play silently unless this is on; full screen always has sound. */
    val previewSound: Boolean = false,
    /** How long the highlight must rest on a channel before its preview starts. */
    val previewDelayMs: Int = 1_500,
    /** Soft focus and confirm tones across the interface. */
    val uiSounds: Boolean = true,
    /** Start the next episode automatically when one ends. */
    val autoNextEpisode: Boolean = true,
    /** Use TheIntroDB community intro/recap/credits/preview timestamps when an external id is known. */
    val useIntroDb: Boolean = true,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("edgestream", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = state
    val value: AppSettings get() = state.value

    init { Http.userAgent = value.userAgent }

    private fun load() = AppSettings(
        userAgent = prefs.getString("ua", null) ?: Http.DEFAULT_USER_AGENT,
        liveFormat = prefs.getString("liveFormat", "ts") ?: "",
        resumeLastChannel = prefs.getBoolean("resumeLast", true),
        livePreview = prefs.getBoolean("preview", true),
        use24h = prefs.getBoolean("use24h", true),
        backdropSound = prefs.getBoolean("backdropSound", false),
        hideScores = prefs.getBoolean("hideScores", false),
        epgRefreshHours = prefs.getInt("epgHours", 12),
        // Migrate the old whole-second key once: 2/4/8/15 s keeps its intent, but the new
        // value is stored and edited in milliseconds.
        // Below 1.5 s the player sits too close to the live edge and IPTV servers' jitter stalls it.
        bufferMs = prefs.getInt("bufferMs", prefs.getInt("buffer", 2) * 1_000).let { if (it < 1_500) 2_000 else it },
        lastChannelKey = prefs.getString("lastChannel", null),
        audioLanguage = prefs.getString("audioLang", "") ?: "",
        subtitleLanguage = prefs.getString("subLang", "") ?: "",
        subtitlesOn = prefs.getBoolean("subsOn", false),
        autoCaptions = prefs.getBoolean("autoCaptions", false),
        epgOffsetMinutes = prefs.getInt("epgOffset", 0),
        startScreen = prefs.getString("startScreen", "HOME") ?: "",
        hideAdult = prefs.getBoolean("hideAdult", true),
        hiddenGroups = prefs.getStringSet("hiddenGroups", null)?.toSet().orEmpty(),
        englishOnly = prefs.getBoolean("englishOnly", true),
        tmdbApiKey = Secrets.openOrBlank(prefs.getString("tmdbKey", "") ?: ""),
        traktClientId = Secrets.openOrBlank(prefs.getString("traktId", "") ?: ""),
        traktClientSecret = Secrets.openOrBlank(prefs.getString("traktSecret", "") ?: ""),
        simklClientId = Secrets.openOrBlank(prefs.getString("simklId", "") ?: ""),
        xweatherClientId = Secrets.openOrBlank(prefs.getString("xweatherId", "") ?: ""),
        xweatherSecret = Secrets.openOrBlank(prefs.getString("xweatherSecret", "") ?: ""),
        torboxApiKey = Secrets.openOrBlank(prefs.getString("torboxKey", "") ?: ""),
        streamAddons = Secrets.openOrBlank(prefs.getString("streamAddons", "") ?: ""),
        showWeather = prefs.getBoolean("weather", true),
        // Typed values are sealed at rest; values saved before that pass through Secrets unchanged.
        weatherPlace = prefs.getString("weatherPlace", null)?.let(Secrets::openOrBlank)?.let(PublicApis.Place::fromJson),
        weatherCity = Secrets.openOrBlank(prefs.getString("weatherCity", "") ?: ""),
        fahrenheit = prefs.getBoolean("fahrenheit", false),
        fillLogos = prefs.getBoolean("fillLogos", true),
        cleanNames = prefs.getBoolean("cleanNames", true),
        nameRegex = Secrets.openOrBlank(prefs.getString("nameRegex", "") ?: ""),
        groupSort = prefs.getString("groupSort", "az") ?: "",
        channelSort = prefs.getString("channelSort", "number") ?: "",
        autoGuides = prefs.getBoolean("autoGuides", true),
        hideOffline = prefs.getBoolean("hideOffline", true),
        matchFrameRate = prefs.getBoolean("matchFps", false),
        tunneling = prefs.getBoolean("tunneling", false),
        theme = prefs.getString("theme", "primetime") ?: "",
        overscanPercent = prefs.getInt("overscan", 5),
        radarSatellite = prefs.getBoolean("radarSat", false),
        effects = prefs.getString("effects", "auto") ?: "auto",
        previewSound = prefs.getBoolean("previewSound", false),
        previewDelayMs = prefs.getInt("previewDelay", 1_500),
        uiSounds = prefs.getBoolean("uiSounds", true),
        autoNextEpisode = prefs.getBoolean("autoNext", true),
        useIntroDb = prefs.getBoolean("useIntroDb", true),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(state.value)
        prefs.edit()
            .putString("ua", s.userAgent)
            .putString("liveFormat", s.liveFormat)
            .putBoolean("resumeLast", s.resumeLastChannel)
            .putBoolean("preview", s.livePreview)
            .putBoolean("use24h", s.use24h)
            .putBoolean("backdropSound", s.backdropSound)
            .putBoolean("hideScores", s.hideScores)
            .putInt("epgHours", s.epgRefreshHours)
            .putInt("bufferMs", s.bufferMs)
            .putString("lastChannel", s.lastChannelKey)
            .putString("audioLang", s.audioLanguage)
            .putString("subLang", s.subtitleLanguage)
            .putBoolean("subsOn", s.subtitlesOn)
            .putBoolean("autoCaptions", s.autoCaptions)
            .putInt("epgOffset", s.epgOffsetMinutes)
            .putString("startScreen", s.startScreen)
            .putBoolean("hideAdult", s.hideAdult)
            .putStringSet("hiddenGroups", s.hiddenGroups)
            .putBoolean("englishOnly", s.englishOnly)
            .putString("tmdbKey", Secrets.seal(s.tmdbApiKey))
            .putString("traktId", Secrets.seal(s.traktClientId))
            .putString("traktSecret", Secrets.seal(s.traktClientSecret))
            .putString("simklId", Secrets.seal(s.simklClientId))
            .putString("xweatherId", Secrets.seal(s.xweatherClientId))
            .putString("xweatherSecret", Secrets.seal(s.xweatherSecret))
            .putString("torboxKey", Secrets.seal(s.torboxApiKey))
            .putString("streamAddons", Secrets.seal(s.streamAddons))
            .putBoolean("weather", s.showWeather)
            .putString("weatherPlace", s.weatherPlace?.toJson()?.let(Secrets::seal))
            .putString("weatherCity", Secrets.seal(s.weatherCity))
            .putBoolean("fahrenheit", s.fahrenheit)
            .putBoolean("fillLogos", s.fillLogos)
            .putBoolean("cleanNames", s.cleanNames)
            .putString("nameRegex", Secrets.seal(s.nameRegex))
            .putString("groupSort", s.groupSort)
            .putString("channelSort", s.channelSort)
            .putBoolean("autoGuides", s.autoGuides)
            .putBoolean("hideOffline", s.hideOffline)
            .putBoolean("matchFps", s.matchFrameRate)
            .putBoolean("tunneling", s.tunneling)
            .putString("theme", s.theme)
            .putInt("overscan", s.overscanPercent)
            .putBoolean("radarSat", s.radarSatellite)
            .putString("effects", s.effects)
            .putBoolean("previewSound", s.previewSound)
            .putInt("previewDelay", s.previewDelayMs)
            .putBoolean("uiSounds", s.uiSounds)
            .putBoolean("autoNext", s.autoNextEpisode)
            .putBoolean("useIntroDb", s.useIntroDb)
            .apply()
        Http.userAgent = s.userAgent
        state.value = s
    }
}
