package com.edgestream.tv.ui.screens

import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.BuildConfigInfo
import com.edgestream.tv.data.AppSettings
import com.edgestream.tv.data.Http
import com.edgestream.tv.data.PublicApis
import com.edgestream.tv.data.SourceInputParser
import com.edgestream.tv.data.db.SourceEntity
import com.edgestream.tv.data.db.SourceType
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeLayout
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.glass
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.Screen
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.edgestream.tv.ui.reportsTextEntry
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow

private val LANGUAGES = listOf("" to "Stream default", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ar" to "Arabic",
    "hi" to "Hindi", "ru" to "Russian", "el" to "Greek", "sv" to "Swedish")

private val GROUP_SORTS = listOf("provider" to "Provider order", "az" to "A–Z", "size" to "Most channels", "watched" to "Most watched")
private val CHANNEL_SORTS = listOf("number" to "Channel number", "az" to "A–Z", "quality" to "Best quality first", "watched" to "Recently watched first")

private val USER_AGENTS = listOf(
    Http.DEFAULT_USER_AGENT,
    "VLC/3.0.21 LibVLC/3.0.21",
    "Kodi/21.0 (Linux; Android 11) Android/11 Sys_CPU/aarch64",
    "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
    "okhttp/4.12.0",
)

/** Settings categories, shown as a list on the left like the system settings on Android TV. */
private enum class SettingsPage(val title: String, val hint: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ACCOUNTS("Providers", "Your playlists and logins, media servers and free channels", Icons.Rounded.AccountCircle),
    CHANNELS("Channels & Guide", "Which channels show, their order, and the TV guide", Icons.Rounded.LiveTv),
    PLAYBACK("Playback", "Picture, delay, audio and subtitles", Icons.Rounded.PlayCircle),
    APPEARANCE("Appearance", "Theme, clock, weather and sounds", Icons.Rounded.Palette),
    PROFILES("Profiles", "Who's watching and PINs", Icons.Rounded.People),
    EXTRAS("Accounts & Keys", "Optional keys for artwork, voice, watch tracking, debrid and lightning", Icons.Rounded.VpnKey),
    REMOTE("Remote & Transfer", "Phone remote, and copying this setup to another TV", Icons.Rounded.SettingsRemote),
    SYSTEM("Shield & Device", "Memory, storage, apps and maintenance", Icons.Rounded.Speed),
}

/** Where the Settings panel is: the root list, or one EdgeStream / device page inside it. */
private sealed interface PanelPlace {
    data object Root : PanelPlace
    data class Edge(val page: SettingsPage) : PanelPlace
    data class Device(val page: DevicePage) : PanelPlace
}

/**
 * Settings the way Android TV / SHIELD shows them: one solid panel on the right with the screen
 * dimmed beside it, sectioned rows (icon, title, grey summary), a soft highlight on the focused
 * row, and pages that open inside the panel. Back steps out of a page, then out of Settings.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var place by remember { mutableStateOf<PanelPlace>(PanelPlace.Root) }
    var lastRow by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedRow by remember { mutableStateOf<StageItem?>(null) }
    var help by remember { mutableStateOf<StageItem?>(null) }
    LaunchedEffect(place) { help = null }
    androidx.activity.compose.BackHandler(enabled = place != PanelPlace.Root) { place = PanelPlace.Root }
    // The left side explains whatever is focused: the page on the list, the row inside a page.
    val stage = when (val p = place) {
        PanelPlace.Root -> focusedRow
        is PanelPlace.Edge -> help?.let { it.copy(icon = it.icon ?: p.page.icon) } ?: StageItem(p.page.icon, p.page.title, p.page.hint)
        is PanelPlace.Device -> StageItem(p.page.icon, p.page.title, p.page.hint)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingHelp provides { help = it }) {
    Box(Modifier.fillMaxSize()) {
        stage?.let { SettingsStage(it, Modifier.align(Alignment.CenterStart).padding(start = 40.dp, end = PANEL_WIDTH + 40.dp)) }
        Column(
            Modifier.align(Alignment.CenterEnd).width(PANEL_WIDTH).fillMaxHeight()
                .background(Edge.Bg.copy(alpha = 0.97f)).padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            when (val p = place) {
                PanelPlace.Root -> PanelRoot(lastRow, onFocusRow = { focusedRow = it }) { key, next -> lastRow = key; place = next }
                is PanelPlace.Edge -> SettingsPageContent(vm, p.page)
                is PanelPlace.Device -> DevicePageContent(p.page) { SystemScreen(vm, embedded = true) }
            }
        }
    }
    }
}

/** Rows report themselves here when focused, so their explanation shows large on the left. */
private val LocalSettingHelp = androidx.compose.runtime.staticCompositionLocalOf<(StageItem) -> Unit> { {} }

/** Whether the row being drawn is focused (white), so its value can switch to dark text. */
private val LocalRowFocused = androidx.compose.runtime.compositionLocalOf { false }

/** A row's current value, right-aligned in grey like tvOS. */
@Composable
private fun RowValue(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (LocalRowFocused.current) Edge.Bg.copy(alpha = 0.65f) else Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA),
        modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp))
}

private val PANEL_WIDTH = 460.dp

private data class StageItem(val icon: androidx.compose.ui.graphics.vector.ImageVector?, val title: String, val hint: String?)

/** The left of Settings: the focused section, large, straight on the picture. */
@Composable
private fun SettingsStage(item: StageItem, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item.icon?.let { Icon(it, null, Modifier.size(84.dp), tint = Edge.Accent) }
        Text(item.title, style = MaterialTheme.typography.displaySmall, color = Edge.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.hint?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PanelRoot(restore: String?, onFocusRow: (StageItem) -> Unit, onOpen: (String, PanelPlace) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(20) { delay(50); runCatching { first.requestFocus() } } }
    val edgePages = SettingsPage.entries.filter { it != SettingsPage.SYSTEM }
    val firstKey = restore ?: "edge:${edgePages.first().name}"
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Text("Settings", color = Edge.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)) }
        item { PanelSection("EdgeStream") }
        items(edgePages, key = { "edge:${it.name}" }) { p ->
            val key = "edge:${p.name}"
            PanelRow(p.icon, p.title, null, if (key == firstKey) first else null, onFocus = { onFocusRow(StageItem(p.icon, p.title, p.hint)) }) { onOpen(key, PanelPlace.Edge(p)) }
        }
        item { PanelSection("Device") }
        items(DevicePage.entries, key = { "device:${it.name}" }) { p ->
            val key = "device:${p.name}"
            PanelRow(p.icon, p.title, null, if (key == firstKey) first else null, onFocus = { onFocusRow(StageItem(p.icon, p.title, p.hint)) }) { onOpen(key, PanelPlace.Device(p)) }
        }
        item {
            Text("EdgeStream ${BuildConfigInfo.VERSION}", color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 18.dp))
        }
    }
}

@Composable
private fun PanelSection(title: String) =
    Text(title, color = Edge.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))

/** One Android-TV-style row: icon, title, grey summary; the focused row gets a soft lighter fill. */
@Composable
private fun PanelRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String?,
    requester: FocusRequester?,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(if (summary != null) 64.dp else 52.dp).then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = RoundedCornerShape(10.dp), color = Color.Transparent, focusedColor = Edge.Text,
        focusScale = 1.03f, glow = false,
    ) { focused ->
        val ink = if (focused) Edge.Bg else Edge.Text
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = ink)
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Text(title, color = ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary != null) Text(summary, color = ink.copy(alpha = SECONDARY_TEXT_ALPHA), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = ink.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SettingsPageContent(vm: AppViewModel, page: SettingsPage) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    // Only the Channels page lists groups; other pages never start the grouping work.
    val groups = if (page == SettingsPage.CHANNELS) vm.allGroups.collectAsStateWithLifecycle().value else emptyList()
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(adding) { if (adding) { delay(48); runCatching { first.requestFocus() } } }

    fun set(t: (AppSettings) -> AppSettings) = vm.updateSettings(t)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { Text(page.title, color = Edge.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) }
        when (page) {
            SettingsPage.ACCOUNTS -> {
                items(sources.orEmpty(), key = { it.id }) { src -> SourceCard(vm, src) }
                item {
                    if (adding) AddSourceForm(vm, Modifier.padding(top = 8.dp), first, onDone = { adding = false })
                    else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingRow("Add Provider", "Add an Xtream login or an M3U playlist. The phone remote can type the details for you.", { adding = true }, icon = Icons.Rounded.Add) {}
                        SettingRow("Refresh All Providers", "Downloads every provider's channels, films and series again.", { vm.refreshAll() }, icon = Icons.Rounded.Sync) {}
                    }
                }
                item { PanelSection("Free Channels") }
                item {
                    SettingRow("Add Free Channels for ${vm.countryCode ?: "My Country"}", "Free-to-air channels for your country from the open iptv-org project.",
                        { vm.addFreeChannels(local = true) }, icon = Icons.Rounded.Add) {}
                }
                item {
                    SettingRow("Add Free Channels Worldwide", "Every free-to-air channel the open iptv-org project lists, from all countries.",
                        { vm.addFreeChannels(local = false) }, icon = Icons.Rounded.Public) {}
                }
                item { PanelSection("Media Servers") }
                item { MediaServerSettings() }
                item { PanelSection("Connection") }
                item {
                    Cycle("Live Stream Format", if (s.liveFormat == "ts") "MPEG-TS" else "HLS",
                        "Xtream providers only. MPEG-TS has the lowest delay; HLS works with more providers.", icon = Icons.Rounded.PlayCircle) {
                        set { it.copy(liveFormat = if (it.liveFormat == "ts") "m3u8" else "ts") }
                    }
                }
                item {
                    Cycle("Player Identity", s.userAgent.substringBefore(' '), "Some providers only accept certain players. Change this if a provider refuses to play.", icon = Icons.Rounded.Devices) {
                        set { it.copy(userAgent = next(USER_AGENTS, it.userAgent)) }
                    }
                }
            }

            SettingsPage.PROFILES -> item { ProfilesSettings(vm) }

            SettingsPage.CHANNELS -> {
                item {
                    Toggle("English Channels Only", "Only English channels, films and series are downloaded and shown, and the guide is matched only for them.", s.englishOnly, icon = Icons.Rounded.Language) {
                        set { it.copy(englishOnly = !it.englishOnly) }
                    }
                }
                item {
                    Cycle("Sort Groups", GROUP_SORTS.firstOrNull { it.first == s.groupSort }?.second ?: "Provider order", "The order of groups in Live TV and the Guide.", icon = Icons.Rounded.Folder) {
                        set { it.copy(groupSort = next(GROUP_SORTS.map { g -> g.first }, it.groupSort)) }
                    }
                }
                item {
                    Cycle("Sort Channels", CHANNEL_SORTS.firstOrNull { it.first == s.channelSort }?.second ?: "Channel number", "The order of channels inside each group.", icon = Icons.Rounded.Sort) {
                        set { it.copy(channelSort = next(CHANNEL_SORTS.map { c -> c.first }, it.channelSort)) }
                    }
                }
                item { Toggle("Hide Offline Channels", "Hides channels that didn't respond the last time a provider's channels were checked.", s.hideOffline, icon = Icons.Rounded.LinkOff) { set { it.copy(hideOffline = !it.hideOffline) } } }
                item { Toggle("Hide Adult Groups", "Hides groups with adult or 18+ names.", s.hideAdult, icon = Icons.Rounded.VisibilityOff) { set { it.copy(hideAdult = !it.hideAdult) } } }
                item { Toggle("Fill In Missing Logos", "Uses the open iptv-org logo database for channels without a logo.", s.fillLogos, icon = Icons.Rounded.Image) { set { it.copy(fillLogos = !it.fillLogos) } } }
                item { Toggle("Clean Up Channel Names", "Turns “UK: BBC One FHD [Geo-blocked]” into “BBC One” with a quality badge.", s.cleanNames, icon = Icons.Rounded.Brush) { set { it.copy(cleanNames = !it.cleanNames) } } }
                if (s.cleanNames) item {
                    var rx by remember(s.nameRegex) { mutableStateOf(s.nameRegex) }
                    val invalid = rx.isNotBlank() && com.edgestream.tv.data.NameCleaner(rx).custom == null
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Field("Also remove (regular expression)", rx, """e.g.  \s*\b(backup|vip|raw)\b""") {
                            rx = it
                            if (com.edgestream.tv.data.NameCleaner(it).custom != null || it.isBlank()) set { st -> st.copy(nameRegex = it) }
                        }
                        if (invalid) Text("That pattern isn't valid yet; the last valid one is still used.", style = MaterialTheme.typography.bodyMedium, color = Edge.Alert)
                    }
                }
                item { PanelSection("Guide") }
                item {
                    Cycle("Automatic Refresh", "Every ${s.epgRefreshHours} hours", "How often the guide downloads again.", icon = Icons.Rounded.Sync) {
                        set { it.copy(epgRefreshHours = next(listOf(6, 12, 24, 48), it.epgRefreshHours)) }
                    }
                }
                item {
                    Cycle(
                        "Guide Time Offset",
                        if (s.epgOffsetMinutes == 0) "None" else "%+d min".format(s.epgOffsetMinutes),
                        "Moves every programme earlier or later, for guides that run early or late. Applies at the next guide update.",
                        icon = Icons.Rounded.Schedule,
                    ) { set { it.copy(epgOffsetMinutes = next((-180..180 step 30).toList(), it.epgOffsetMinutes)) } }
                }
                items(sources.orEmpty(), key = { "g${it.id}" }) { src ->
                    SettingRow(
                        "Update Guide · ${src.name}",
                        if (src.lastEpgRefresh > 0) "Updated ${Format.ago(src.lastEpgRefresh)}. ${src.guideMatched} of ${src.channelCount} channels have a guide. Press OK to update it now."
                        else "Not updated yet. Press OK to update it now.",
                        { vm.refreshEpg(src.id) }, icon = Icons.Rounded.CalendarViewWeek,
                    ) {
                        if (src.lastEpgRefresh > 0) RowValue("${src.guideMatched} / ${src.channelCount}")
                    }
                }
                // Every group Live TV and the Guide show; switching one off hides it in both.
                item { PanelSection("Groups") }
                if (groups.isEmpty()) item { Text("Groups appear here once your channels have loaded.", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(horizontal = 12.dp)) }
                groups.forEach { (name, count) ->
                    item(key = "group:$name") {
                        val shown = name !in s.hiddenGroups
                        Toggle(name, "$count channels" + if (shown) "" else ", hidden from Live TV and the Guide", shown) { vm.setGroupHidden(name, shown) }
                    }
                }
            }

            SettingsPage.PLAYBACK -> {
                item {
                    val mode = when { !s.livePreview -> "Off"; s.previewSound -> "With sound"; else -> "Silent" }
                    Cycle("Channel Preview", mode, "Plays the highlighted channel in the preview window while you browse.", icon = Icons.Rounded.LiveTv) {
                        set {
                            when { !it.livePreview -> it.copy(livePreview = true, previewSound = false)
                                !it.previewSound -> it.copy(previewSound = true)
                                else -> it.copy(livePreview = false, previewSound = false) }
                        }
                    }
                }
                if (s.livePreview) item {
                    Cycle("Preview Starts After", "%.1f seconds".format(s.previewDelayMs / 1000f), "Longer means scrolling past channels never starts a preview.", icon = Icons.Rounded.Timer) {
                        set { it.copy(previewDelayMs = next(listOf(800, 1_500, 2_500, 4_000), it.previewDelayMs)) }
                    }
                }
                item { Toggle("Preview Last Channel", "Plays the last channel silently on Home when EdgeStream opens.", s.resumeLastChannel, icon = Icons.Rounded.Home) { set { it.copy(resumeLastChannel = !it.resumeLastChannel) } } }
                item {
                    Cycle("Live Buffer", "%.1f seconds".format(s.bufferMs / 1000f), "Live TV starts once this much is buffered, and keeps that much in hand the whole time you watch. Raise it if channels stutter; lower it to sit closer to real time.", icon = Icons.Rounded.Speed) {
                        set { it.copy(bufferMs = next(listOf(1_500, 3_000, 4_000, 6_000, 8_000, 12_000, 15_000), it.bufferMs)) }
                    }
                }
                item { Toggle("Match Frame Rate", "Switches the TV to 24, 25, 50 or 60 Hz to match the video. Removes judder; the screen blanks briefly on a change.", s.matchFrameRate, icon = Icons.Rounded.SlowMotionVideo) { set { it.copy(matchFrameRate = !it.matchFrameRate) } } }
                item { Toggle("Tunnelled Playback", "Hardware audio and video sync. Smoother on SHIELD; turn it off if a stream stutters or has no sound.", s.tunneling, icon = Icons.Rounded.Cable) { set { it.copy(tunneling = !it.tunneling) } } }
                item { Toggle("Strip Dolby Vision", "Skip Dolby Vision tracks and play the HEVC HDR10 base layer. DV freezes some decoders — turn this off only if you want the DV layer.", s.stripDolbyVision, icon = Icons.Rounded.MovieFilter) { set { it.copy(stripDolbyVision = !it.stripDolbyVision) } } }
                item {
                    Cycle("Audio Delay", if (s.audioDelayMs == 0) "Off" else "${s.audioDelayMs} ms", "Lip-sync correction for soundbars and receivers whose sound arrives before the picture. Applies from the next thing you play.", icon = Icons.Rounded.Schedule) {
                        set { it.copy(audioDelayMs = next(listOf(0, 40, 80, 120, 160, 200, 260, 320, 400), it.audioDelayMs)) }
                    }
                }
                item { Toggle("Play Next Episode", "When an episode ends, the next one starts on its own.", s.autoNextEpisode, icon = Icons.Rounded.SkipNext) { set { it.copy(autoNextEpisode = !it.autoNextEpisode) } } }
                item { Toggle("Skip Intros and Credits", "Uses TheIntroDB's community timestamps. Sends the title's IMDb or TMDB id to theintrodb.org; no account needed.", s.useIntroDb, icon = Icons.Rounded.MovieFilter) { set { it.copy(useIntroDb = !it.useIntroDb) } } }
                item { PanelSection("Audio & Subtitles") }
                item {
                    Cycle("Audio Language", LANGUAGES.firstOrNull { it.first == s.audioLanguage }?.second ?: s.audioLanguage, "Picks this language when a stream has more than one.", icon = Icons.Rounded.VolumeUp) {
                        set { it.copy(audioLanguage = next(LANGUAGES.map { l -> l.first }, it.audioLanguage)) }
                    }
                }
                item { Toggle("Subtitles On", "Turns on the stream's own subtitles whenever it has them.", s.subtitlesOn, icon = Icons.Rounded.Subtitles) { set { it.copy(subtitlesOn = !it.subtitlesOn) } } }
                item {
                    Cycle("Subtitle Language", LANGUAGES.firstOrNull { it.first == s.subtitleLanguage }?.second ?: s.subtitleLanguage, "Picks this language when a stream has more than one subtitle track.", icon = Icons.Rounded.ClosedCaption) {
                        set { it.copy(subtitleLanguage = next(LANGUAGES.map { l -> l.first }, it.subtitleLanguage)) }
                    }
                }
                item {
                    Toggle("Auto Captions", "English captions made on the TV from the programme's sound, for streams without subtitles. Downloads a 40 MB speech model the first time.", s.autoCaptions, icon = Icons.Rounded.Mic) {
                        set { it.copy(autoCaptions = !it.autoCaptions) }
                    }
                }
            }

            SettingsPage.APPEARANCE -> {
                item { PanelSection("Theme") }
                item { ThemePicker(s.theme) { id -> set { it.copy(theme = id) } } }
                item { PanelSection("Home & Screen") }
                item {
                    Cycle("Open On", Screen.entries.firstOrNull { it.name == s.startScreen }?.label ?: "Home", "The screen EdgeStream opens on.", icon = Icons.Rounded.Home) {
                        set { it.copy(startScreen = next(listOf("HOME", "LIVE", "MOVIES", "SERIES"), it.startScreen)) }
                    }
                }
                item { Toggle("24-Hour Clock", "Shows times as 18:30 instead of 6:30 pm.", s.use24h, icon = Icons.Rounded.Schedule) { set { it.copy(use24h = !it.use24h) } } }
                item { Toggle("Hide Scores", "No spoilers: scores, box scores and play-by-play stay hidden everywhere.", s.hideScores, icon = Icons.Rounded.VisibilityOff) { set { it.copy(hideScores = !it.hideScores) } } }
                item { Toggle("Background Sound", "Plays the highlights' sound quietly behind Home.", s.backdropSound, icon = Icons.Rounded.VolumeUp) { set { it.copy(backdropSound = !it.backdropSound) } } }
                item { Toggle("Interface Sounds", "Soft focus ticks and a confirm tone.", s.uiSounds, icon = Icons.Rounded.GraphicEq) { set { it.copy(uiSounds = !it.uiSounds) } } }
                item {
                    Cycle("Screen Fit", if (s.overscanPercent == 0) "Edge to edge" else "${s.overscanPercent}% margin",
                        "Keeps the clock, menus and text clear of TVs that crop the picture. 5% matches Android TV guidance.", icon = Icons.Rounded.AspectRatio) {
                        set { it.copy(overscanPercent = next(listOf(0, 2, 3, 4, 5, 6, 8, 10), it.overscanPercent)) }
                    }
                }
                item {
                    Cycle(
                        "Motion & Effects",
                        when (s.effects) { "full" -> "Full"; "balanced" -> "Balanced"; "low" -> "Low"; else -> "Auto · ${com.edgestream.tv.ui.DeviceTier.tier.name.lowercase()}" },
                        "Lower it on slower TV boxes: less glow and animation, faster menus.",
                        icon = Icons.Rounded.Animation,
                    ) { set { it.copy(effects = next(listOf("auto", "full", "balanced", "low"), it.effects)) } }
                }
                item { PanelSection("Weather") }
                item { Toggle("Weather on Home", "Current conditions from Open-Meteo beside the clock.", s.showWeather, icon = Icons.Rounded.WbSunny) { set { it.copy(showWeather = !it.showWeather) } } }
                if (s.showWeather) item {
                    WeatherPlacePicker(vm, s.weatherPlace, manual = s.weatherPlace != null || s.weatherCity.isNotBlank(),
                        onPick = { p -> set { it.copy(weatherPlace = p, weatherCity = "") } },
                        onAutomatic = { set { it.copy(weatherPlace = null, weatherCity = "") } })
                }
                if (s.showWeather) item { Toggle("Fahrenheit", "Shows temperatures in °F instead of °C.", s.fahrenheit, icon = Icons.Rounded.Thermostat) { set { it.copy(fahrenheit = !it.fahrenheit) } } }
                item {
                    Cycle("Radar Map", if (s.radarSatellite) "Satellite" else "Standard", "Standard is a light street map; Satellite shows terrain and coastlines.", icon = Icons.Rounded.Radar) {
                        set { it.copy(radarSatellite = !it.radarSatellite) }
                    }
                }
            }

            SettingsPage.EXTRAS -> {
                // Keys grouped by what they feed, so the page reads as a list of services
                // instead of one long wall of password boxes.
                val regions = listOf("", "AU", "US", "GB", "IN", "JP", "KR", "FR", "DE", "BR", "ES", "IT", "MX")
                val languages = listOf("", "en", "fr", "de", "es", "ja", "ko", "hi", "pt", "it", "zh", "ar", "tr")
                val certs = listOf("", "G", "PG", "M", "MA15+", "R18+")
                item { PanelSection("Metadata") }
                item {
                    SecureApiKeyField("TMDB API Key", "v3 key from themoviedb.org; posters, backdrops, cast and ratings.", s.tmdbApiKey) { key -> set { it.copy(tmdbApiKey = key) } }
                }
                item {
                    SecureApiKeyField("TMDB Read Token", "v4 API Read Access Token. Used as a Bearer token when set; otherwise the v3 key.", s.tmdbReadToken) { key -> set { it.copy(tmdbReadToken = key) } }
                }
                item {
                    SecureApiKeyField("Metadata Addon", "Stremio metadata addon manifest URL (e.g. AIOMetadata). Stored encrypted on this TV.", s.metadataAddon) { key -> set { it.copy(metadataAddon = key) } }
                }

                item { PanelSection("Streams & Add-ons") }
                item { SecureApiKeyField("Debrid Service", "Your debrid cloud: cached films and episodes. Key at torbox.app › Settings › API.", s.torboxApiKey) { key -> set { it.copy(torboxApiKey = key) } } }
                item { StreamAddonsCard(vm, s.streamAddons) { list -> set { st -> st.copy(streamAddons = list) } } }

                item { PanelSection("Watch Tracking") }
                item {
                    SecureApiKeyField("Trakt Client ID", "Create an app at trakt.tv/oauth/applications (redirect URI urn:ietf:wg:oauth:2.0:oob) and paste its Client ID.", s.traktClientId) { key -> set { it.copy(traktClientId = key) } }
                }
                item { SecureApiKeyField("Trakt Client Secret", "The same Trakt app's Client Secret.", s.traktClientSecret) { key -> set { it.copy(traktClientSecret = key) } } }
                item { SecureApiKeyField("Simkl Client ID", "Create an app at simkl.com/settings/developer and paste its Client ID.", s.simklClientId) { key -> set { it.copy(simklClientId = key) } } }
                item { TrackingLinks(s) }

                item { PanelSection("Voice & AI") }
                item {
                    SecureApiKeyField("Gemini", "Understands voice and typed commands the built-in rules can't, like “put the Chiefs game on”. Free key at aistudio.google.com. Only the command is sent.", s.geminiApiKey) { key -> set { it.copy(geminiApiKey = key) } }
                }

                item { PanelSection("Lightning") }
                item {
                    SecureApiKeyField("Xweather Client ID", "Measured lightning strikes on Radar (15,000 a month free at xweather.com). Without it, Radar shows storm risk from the forecast.", s.xweatherClientId) { key -> set { it.copy(xweatherClientId = key) } }
                }
                item {
                    SecureApiKeyField("Xweather Secret", "Paired with the Client ID. Both are at data.portal.xweather.com/account/keys; the namespace must be com.edgestream.tv.", s.xweatherSecret) { key -> set { it.copy(xweatherSecret = key) } }
                }

                item { PanelSection("Badges & Region") }
                item {
                    Cycle("Badge Pack", com.edgestream.tv.data.BadgePacks.label(s.badgePack),
                        "Gold, Elite, your Nuvio set, or off. Shown in the player and the source list.") {
                        set { it.copy(badgePack = next(com.edgestream.tv.data.BadgePacks.choices.map { c -> c.first }, it.badgePack)) }
                    }
                }
                item {
                    Cycle("Region", s.contentRegion.ifBlank { vm.countryCode ?: "Auto" }.uppercase(),
                        "ISO 3166-1 region for TMDB discovery and the region badge; Auto uses the device country.") {
                        set { it.copy(contentRegion = next(regions, it.contentRegion)) }
                    }
                }
                item {
                    Cycle("Language", s.contentLanguage.ifBlank { s.audioLanguage.ifBlank { "en" } }.uppercase(),
                        "ISO 639-1 language for discovery and the language badge.") {
                        set { it.copy(contentLanguage = next(languages, it.contentLanguage)) }
                    }
                }
                item {
                    Cycle("Certification", s.contentCertification.ifBlank { "Off" },
                        "Ceiling for regional discovery and a badge on every stream (AU classifications).") {
                        set { it.copy(contentCertification = next(certs, it.contentCertification)) }
                    }
                }
            }

            SettingsPage.REMOTE -> {
                item { PhoneRemoteCard() }
                item { PanelSection("Transfer Setup") }
                item { TransferSettings(vm) }
                item {
                    Text(
                        "EdgeStream ${BuildConfigInfo.VERSION}  ·  Bring your own playlist: EdgeStream doesn't provide any channels or content.",
                        style = MaterialTheme.typography.labelMedium, color = Edge.Faint, modifier = Modifier.padding(top = 24.dp, start = 12.dp),
                    )
                }
            }

            SettingsPage.SYSTEM -> {
                item { SystemScreen(vm, embedded = true) }
            }
        }
    }
}

/** Swatch cards that show each preset's real surfaces, text and accent, not just a name. */
@Composable
private fun ThemePicker(current: String, onPick: (String) -> Unit) {
    val active = com.edgestream.tv.ui.Palettes.byId(current).id
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        items(com.edgestream.tv.ui.Palettes.all, key = { it.id }) { p ->
            val selected = p.id == active
            FocusSurface(onClick = { onPick(p.id) }, modifier = Modifier.width(184.dp).height(120.dp), color = p.bg, focusedColor = p.bg, selected = selected) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(width = 34.dp, height = 22.dp).clip(RoundedCornerShape(5.dp)).background(p.panel))
                        Box(Modifier.size(width = 34.dp, height = 22.dp).clip(RoundedCornerShape(5.dp)).background(p.panelHi))
                        Box(
                            Modifier.size(width = 22.dp, height = 22.dp).clip(RoundedCornerShape(5.dp))
                                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(p.accent, p.secondary))),
                        )
                    }
                    Box(Modifier.padding(top = 10.dp).size(width = 90.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.line)) {
                        Box(
                            Modifier.size(width = 52.dp, height = 4.dp)
                                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(p.accent, p.secondary))),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(p.name, color = p.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (selected) "In use" else "Select", color = if (selected) p.accent else p.dim, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]

/**
 * One provider, TiviMate style: a single row (name, channel count); OK opens a dialog with
 * everything you can do to it. Removing asks once more.
 */
@Composable
private fun SourceCard(vm: AppViewModel, src: SourceEntity) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editingGuide by remember { mutableStateOf(false) }
    var guideUrl by remember(src.id, src.epgUrl) { mutableStateOf(src.epgUrl) }
    val status = buildString {
        append(if (src.type == SourceType.XTREAM) "Xtream" else "M3U playlist")
        append(". ${src.channelCount} channels")
        if (src.vodCount > 0) append(", ${src.vodCount} films and series")
        append(". Updated ${Format.ago(src.lastRefresh)}.")
        src.accountStatus?.let { append(" Account $it.") }
        src.expiresAt?.let { append(if (it < System.currentTimeMillis()) " Expired ${Format.day(it)}." else " Expires ${Format.day(it)}.") }
        src.maxConnections?.let { append(" ${src.activeConnections ?: 0} of $it connections in use.") }
        src.lastError?.let { append(" Problem: $it") }
        append(" Press OK for options.")
    }
    Column {
        SettingRow(src.name, status, { menu = true }, icon = if (src.lastError != null) Icons.Rounded.ErrorOutline else Icons.Rounded.AccountCircle) {
            RowValue(if (src.lastError != null) "Problem" else if (src.isTrial) "Trial" else "${src.channelCount} channels")
        }
        if (editingGuide) Column(Modifier.padding(start = 56.dp, end = 12.dp, bottom = 8.dp)) {
            Field(label = "Extra TV guide (XMLTV URL)", value = guideUrl, hint = "Leave blank for none",
                supportingText = "Used for channels the other guides don't cover.", keyboard = KeyboardType.Uri) { guideUrl = it }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EdgeButton("Save", { vm.updateGuideUrl(src.id, guideUrl) { }; editingGuide = false }, primary = true, icon = Icons.Rounded.Check)
                EdgeButton("Cancel", { guideUrl = src.epgUrl; editingGuide = false }, icon = Icons.Rounded.Close)
            }
        }
    }
    if (menu) com.edgestream.tv.ui.components.TvAlert(
        title = src.name,
        message = "${src.channelCount} channels · updated ${Format.ago(src.lastRefresh)}",
        actions = listOfNotNull(
            com.edgestream.tv.ui.components.AlertAction("Update Playlist") { menu = false; vm.refreshSource(src.id) },
            com.edgestream.tv.ui.components.AlertAction("Update Guide") { menu = false; vm.refreshEpg(src.id) },
            if (src.type == SourceType.M3U) com.edgestream.tv.ui.components.AlertAction("Check Channels") { menu = false; vm.checkStreams(src.id) } else null,
            com.edgestream.tv.ui.components.AlertAction(if (src.epgUrl.isBlank()) "Add Extra Guide" else "Change Extra Guide") { menu = false; editingGuide = true },
            com.edgestream.tv.ui.components.AlertAction("Remove Provider", destructive = true) { menu = false; confirmRemove = true },
            com.edgestream.tv.ui.components.AlertAction("Cancel") { menu = false },
        ),
        onDismiss = { menu = false },
    )
    if (confirmRemove) com.edgestream.tv.ui.components.TvAlert(
        title = "Remove ${src.name}?",
        message = "Its channels, films, series and guide are removed from this TV. You can add it again later.",
        actions = listOf(
            com.edgestream.tv.ui.components.AlertAction("Cancel") { confirmRemove = false },
            com.edgestream.tv.ui.components.AlertAction("Remove", destructive = true) { confirmRemove = false; vm.deleteSource(src.id) },
        ),
        onDismiss = { confirmRemove = false },
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    SettingRow(title, subtitle, onClick, icon = icon) { RowValue(if (on) "On" else "Off") }
}

@Composable
private fun Cycle(title: String, value: String, subtitle: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    SettingRow(title, subtitle, onClick, icon = icon) { RowValue(value) }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: @Composable () -> Unit,
) {
    val report = LocalSettingHelp.current
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp).onFocusChanged { if (it.isFocused) report(StageItem(icon, title, subtitle)) },
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        focusedColor = Edge.Text,
        focusScale = 1.03f,
        glow = false,
    ) { focused ->
        val ink = if (focused) Edge.Bg else Edge.Text
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                androidx.compose.material3.Icon(icon, null, Modifier.size(24.dp), tint = ink)
                Spacer(Modifier.width(18.dp))
            }
            OneLine(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = ink)
            androidx.compose.runtime.CompositionLocalProvider(LocalRowFocused provides focused) { trailing() }
        }
    }
}

/**
 * Weather location: type a few letters, then pick the exact town from the matches with the
 * D-pad. The pick is saved with its coordinates, so the weather never lands on the wrong
 * Springfield. "Use automatic" goes back to locating by the internet connection.
 */
@Composable
private fun WeatherPlacePicker(
    vm: AppViewModel,
    picked: PublicApis.Place?,
    manual: Boolean,
    onPick: (PublicApis.Place) -> Unit,
    onAutomatic: () -> Unit,
) {
    val resolved by vm.place.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<PublicApis.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val q = query.trim()
        matches = emptyList()
        if (q.length < 2) { searching = false; return@LaunchedEffect }
        searching = true
        delay(300) // typing pause: one request per word, not per letter
        matches = withContext(Dispatchers.IO) { PublicApis.searchPlaces(q) }
        searching = false
    }
    val current = when {
        picked != null -> "${picked.name}, ${picked.detail}"
        manual -> resolved?.city ?: "Finding your city…"
        else -> "Automatic" + (resolved?.city?.let { " · $it" } ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow("Weather Location", current, onClick = { if (manual) onAutomatic() }, icon = Icons.Rounded.Place) {
            RowValue(if (manual) "Use automatic" else "Automatic")
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            Field(
                "Change location", query, "Start typing a town or city, e.g. Bris",
                supportingText = when {
                    searching -> "Searching…"
                    query.trim().length >= 2 && matches.isEmpty() -> null
                    matches.isNotEmpty() -> "Press down to choose"
                    else -> null
                },
                errorText = if (!searching && query.trim().length >= 2 && matches.isEmpty()) "No places match \"${query.trim()}\"" else null,
            ) { query = it }
        }
        matches.forEach { place ->
            SettingRow(place.name, place.detail, onClick = { onPick(place); query = "" }, icon = Icons.Rounded.LocationOn) {
                if (place == picked) androidx.compose.material3.Icon(Icons.Rounded.Check, "Current", Modifier.size(22.dp), tint = Edge.Accent)
            }
        }
    }
}

/**
 * QR code + address for the phone companion. Scanning opens a page on the phone that works as a
 * remote, a channel picker and a keyboard for entering provider details.
 */
@Composable
fun PhoneRemoteCard(compact: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val running by com.edgestream.tv.remote.PhoneLink.running.collectAsStateWithLifecycle()
    val lastSeen by com.edgestream.tv.remote.PhoneLink.lastSeen.collectAsStateWithLifecycle()
    var nonce by remember { mutableStateOf(0) }
    val url = remember(running, nonce) { com.edgestream.tv.remote.PhoneLink.url(context) }
    val code = remember(nonce) { com.edgestream.tv.remote.PhoneLink.token(context) }
    val connected = System.currentTimeMillis() - lastSeen < 120_000

    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (url != null && running) com.edgestream.tv.ui.components.QrCode(url, Modifier.size(if (compact) 132.dp else 156.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.padding(start = 20.dp).weight(1f)) {
            Text(if (compact) "Use your phone instead" else "Control EdgeStream from your phone", style = MaterialTheme.typography.titleLarge)
            Text(
                "Scan with your phone camera to get a remote, a channel list, search, and a proper keyboard for logins.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
            when {
                url == null -> Text("Connect the TV to your home network to use this.", style = MaterialTheme.typography.bodyMedium, color = Edge.Alert, modifier = Modifier.padding(top = 10.dp))
                !running -> Text("Starting…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                else -> {
                    Text(url.substringBefore("/?"), style = MaterialTheme.typography.titleMedium, color = Edge.Accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text("Pairing code  $code", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        if (connected) "● Phone connected" else "Phone and TV must be on the same Wi-Fi.",
                        style = MaterialTheme.typography.labelMedium, color = if (connected) Edge.Accent else Edge.Dim,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (!compact && running) EdgeButton("New Pairing Code", {
                com.edgestream.tv.remote.PhoneLink.resetToken(context); nonce++
            }, Modifier.padding(top = 12.dp), icon = Icons.Rounded.Refresh)
        }
    }
}

/**
 * First run with no provider: pair a phone and enter the login there, so nobody types a server
 * address with a D-pad. The phone's "Add to TV" inserts the source; the non-empty source list
 * then dismisses this screen by itself. Typing on the TV stays available as a fallback.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel, onSkip: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val running by com.edgestream.tv.remote.PhoneLink.running.collectAsStateWithLifecycle()
    val lastSeen by com.edgestream.tv.remote.PhoneLink.lastSeen.collectAsStateWithLifecycle()
    val url = remember(running) { com.edgestream.tv.remote.PhoneLink.url(context) }
    val code = remember { com.edgestream.tv.remote.PhoneLink.token(context) }
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { delay(5_000); value = System.currentTimeMillis() }
    }
    val connected = now - lastSeen < 120_000
    var typing by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(typing) { repeat(10) { delay(60); runCatching { first.requestFocus() } } }
    androidx.activity.compose.BackHandler { if (typing) typing = false else onSkip() }

    // The caller already insets for overscan; this only keeps the block off the safe-area edge.
    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 64.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.edgestream.tv.ui.components.EdgeMark(Modifier.size(64.dp))
                    Text("EdgeStream", style = MaterialTheme.typography.displaySmall, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
                }
                Text(
                    if (typing) "Add your provider" else "Set up with your phone",
                    style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                    modifier = Modifier.padding(top = 36.dp),
                )
                if (typing) {
                    Text(
                        "Enter your Xtream Codes login or M3U playlist address. Press OK on a box to bring up the keyboard.",
                        style = MaterialTheme.typography.bodyLarge, color = Edge.Dim,
                        modifier = Modifier.padding(top = 14.dp).widthIn(max = 520.dp),
                    )
                } else {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SetupStep("1", "Scan the code with your phone camera")
                        SetupStep("2", "Enter your IPTV login on the phone")
                        SetupStep("3", "Tap Add to TV and channels load here")
                    }
                    Text(
                        "Your login goes straight to this TV over your home Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium, color = Edge.Dim,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
                Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (typing) EdgeButton("Use Phone Instead", { typing = false }, icon = Icons.Rounded.PhoneAndroid)
                    else EdgeButton("Enter on TV", { typing = true }, Modifier.focusRequester(first), icon = Icons.Rounded.Keyboard)
                    EdgeButton("Free Channels", { vm.addFreeChannels(local = true) }, icon = Icons.Rounded.Add)
                    EdgeButton("Skip for Now", onSkip)
                }
            }
            if (typing) {
                AddSourceForm(vm, Modifier.width(520.dp), first, onDone = {}, cancellable = false, scrollable = true)
            } else {
                Column(Modifier.width(272.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(272.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (url != null && running) com.edgestream.tv.ui.components.QrCode(url, Modifier.fillMaxSize())
                        else Text(
                            "Connect the TV to your home network to pair a phone.",
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFF303030),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    com.edgestream.tv.ui.components.Pill(
                        if (connected) "Phone connected" else "Ready to pair",
                        Modifier.padding(top = 18.dp), color = if (connected) Edge.Accent else Edge.Accent2,
                    )
                    if (url != null) {
                        Text(
                            url.substringBefore("/#").substringAfter("://"), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Text("Pairing code  $code", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 1)
                }
            }
    }
}

/** Drawn edge to edge behind [OnboardingScreen]; never under 0.72, the house minimum behind text. */
val OnboardingShade = androidx.compose.ui.graphics.Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.86f), 0.55f to Color.Black.copy(alpha = 0.76f), 1f to Color.Black.copy(alpha = 0.72f),
)

@Composable
private fun SetupStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Edge.Accent.copy(alpha = 0.18f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.labelLarge, color = Edge.Accent)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
fun AddSourceForm(
    vm: AppViewModel, modifier: Modifier, firstFocus: FocusRequester, onDone: () -> Unit,
    cancellable: Boolean = true,
    /** Only when the form stands alone; inside Settings' list it must not scroll itself (nested scrolling crashes). */
    scrollable: Boolean = false,
) {
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // URL intelligence: a pasted Xtream link fills server, username and password by itself.
    LaunchedEffect(url) {
        val parsed = SourceInputParser.parse(url) ?: return@LaunchedEffect
        if (parsed.type != type) type = parsed.type
        if (parsed.type == SourceType.XTREAM) {
            if (parsed.server.isNotBlank() && parsed.server != url) url = parsed.server
            if (parsed.user.isNotBlank() && parsed.user != user) user = parsed.user
            if (parsed.pass.isNotBlank() && parsed.pass != pass) pass = parsed.pass
        }
        if (name.isBlank() && url.isNotBlank()) name = SourceInputParser.nameFor(url)
    }

    Column(
        modifier.widthIn(max = 640.dp).glass(RoundedCornerShape(18.dp))
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(22.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(SourceType.XTREAM to "Xtream Codes login", SourceType.M3U to "M3U playlist URL").forEachIndexed { i, (t, label) ->
                FocusSurface(
                    onClick = { type = t }, shape = RoundedCornerShape(50), selected = type == t,
                    color = if (type == t) Edge.Accent.copy(alpha = 0.2f) else Edge.PanelHi,
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                ) { f ->
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (f || type == t) Edge.Text else Edge.Dim, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Field("Name", name, "My provider") { name = it }
        if (type == SourceType.XTREAM) {
            Field("Server", url, "http://example.com:8080", keyboard = KeyboardType.Uri) { url = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Field("Username", user, "") { user = it } }
                Box(Modifier.weight(1f)) { Field("Password", pass, "", password = true) { pass = it } }
            }
        } else {
            Field("Playlist URL", url, "http://…/playlist.m3u", keyboard = KeyboardType.Uri) { url = it }
        }
        val detected = remember(url) { SourceInputParser.parse(url) }
        if (detected?.type == SourceType.XTREAM && detected.user.isNotBlank()) {
            Text(
                "Xtream login detected — server, username and password filled in.",
                style = MaterialTheme.typography.labelSmall, color = Edge.Accent2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Field("TV guide URL (optional)", epg, if (type == SourceType.XTREAM) "Uses your provider's guide" else "Uses the playlist's url-tvg", keyboard = KeyboardType.Uri) { epg = it }

        error?.let { Text(it, color = Edge.Alert, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EdgeButton(if (busy) "Checking login…" else "Add & load channels", {
                if (busy) return@EdgeButton
                val cleanUrl = SourceInputParser.playableUrl(url)
                val cleanEpg = if (epg.isBlank()) "" else SourceInputParser.playableUrl(epg)
                error = when {
                    url.isBlank() -> "Enter the ${if (type == SourceType.XTREAM) "server address" else "playlist URL"}."
                    cleanUrl == null -> "That address isn't valid. It should look like http://example.com:8080."
                    type == SourceType.XTREAM && (user.isBlank() || pass.isBlank()) -> "Enter your username and password."
                    cleanEpg == null -> "The guide address isn't valid. It should start with http:// or https://."
                    else -> null
                }
                if (error != null || cleanUrl == null || cleanEpg == null) return@EdgeButton
                busy = true
                val source = SourceEntity(
                    name = name.trim().ifEmpty { if (type == SourceType.XTREAM) cleanUrl.substringAfter("://").substringBefore(':').substringBefore('/') else "Playlist" },
                    type = type, url = cleanUrl,
                    username = user.trim(), password = pass.trim(), epgUrl = cleanEpg,
                )
                vm.addSource(source) { problem -> busy = false; error = problem; if (problem == null) onDone() }
            }, primary = true, icon = Icons.Rounded.Check)
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Edge.Accent, strokeWidth = 3.dp)
            if (cancellable) EdgeButton("Cancel", onDone)
        }
    }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    supportingText: String? = null,
    errorText: String? = null,
    onChange: (String) -> Unit,
) {
    val kb = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // The keyboard opens on OK only: focus passing through a field (D-pad) must not raise it,
    // or the keyboard swallows the remote and the viewer is stuck in the box.
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(focused, editing) {
        if (!focused) editing = false
        else if (!editing) { kb?.hide(); delay(120); kb?.hide() }
    }
    var visible by remember { mutableStateOf(false) }
    val secure = password
    val shown = value.isNotBlank()
    com.edgestream.tv.ui.PhoneFillable(focused, label, value, secure, onChange)
    Column(modifier = modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            if (secure) {
                Text(
                    if (shown) "Encrypted at rest" else "Not saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (shown) Edge.Accent2 else Edge.Faint,
                    maxLines = 1,
                )
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            cursorBrush = SolidColor(Edge.Accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secure) KeyboardType.Password else keyboard,
                capitalization = if (secure || keyboard == KeyboardType.Uri) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            visualTransformation = if (secure && !visible) PasswordVisualTransformation('*') else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .reportsTextEntry(com.edgestream.tv.ui.LocalTextEntry.current)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter -> { editing = true; kb?.show(); true }
                        // A one-line field has nowhere to go up or down: the remote moves on, as on
                        // any TV app, instead of being trapped in the box.
                        Key.DirectionDown -> { editing = false; kb?.hide(); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                        Key.DirectionUp -> { editing = false; kb?.hide(); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(EdgeLayout.FieldHeight).clip(RoundedCornerShape(14.dp))
                        .background(if (errorText != null) Edge.Alert.copy(alpha = 0.12f) else Edge.Bg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty()) {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Edge.Faint,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                        if (secure && shown) {
                            FocusSurface(
                                onClick = { visible = !visible },
                                modifier = Modifier.size(38.dp),
                                shape = EdgeShapes.Chip,
                                color = Color.Transparent,
                                focusedColor = Edge.Accent.copy(alpha = 0.16f),
                                selected = visible,
                                focusScale = 1.05f,
                                glow = false,
                            ) {
                                androidx.compose.material3.Icon(
                                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (visible) "Hide" else "Show",
                                    Modifier.align(Alignment.Center).size(20.dp),
                                    tint = if (it) Edge.Text else Edge.Dim,
                                )
                            }
                        }
                    }
                }
            },
        )
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = Edge.Dim,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp, start = 2.dp),
            )
        }
        errorText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = Edge.Alert,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun SecureApiKeyField(
    title: String,
    description: String,
    savedValue: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(savedValue) { mutableStateOf(savedValue) }
    var notice by remember(savedValue) { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    Column {
        SettingRow(title, description, { open = !open }, icon = Icons.Rounded.VpnKey) {
            RowValue(notice ?: if (savedValue.isBlank()) "Not set" else "Saved")
        }
        if (open) Column(Modifier.padding(start = 56.dp, end = 12.dp, bottom = 8.dp)) {
            Field(label = "Key", value = draft, hint = "Paste your key", password = true,
                supportingText = "Stored encrypted on this TV.") { draft = it }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (draft != savedValue) {
                    EdgeButton("Save", {
                        val value = draft.trim()
                        onSave(value)
                        notice = if (value.isEmpty()) "Removed" else "Saved"
                        open = false
                    }, primary = true, icon = Icons.Rounded.Check)
                    EdgeButton("Cancel", { draft = savedValue; open = false }, icon = Icons.Rounded.Close)
                } else if (savedValue.isNotBlank()) {
                    EdgeButton("Remove Key", { onSave(""); notice = "Removed"; open = false }, icon = Icons.Rounded.Delete)
                }
            }
        }
    }
}

/**
 * Stream add-ons: catalog/stream add-ons that the
 * user has set up with their TorBox key. They find torrent and Usenet versions of films and
 * episodes, which Play then prefers over the provider's own stream.
 */
@Composable
private fun StreamAddonsCard(vm: AppViewModel, stored: String, save: (String) -> Unit) {
    val urls = stored.lines().map { it.trim() }.filter { it.isNotEmpty() }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            "Stream add-ons find debrid copies of films and episodes. Set one up (AIOStreams, Comet or MediaFusion) with your debrid key, then paste its manifest URL.",
            style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(bottom = 6.dp),
        )
        urls.forEachIndexed { i, url ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Edge.Dim, modifier = Modifier.width(24.dp))
                // Shows the host only: the rest of the URL carries the user's keys.
                OneLine(com.edgestream.tv.data.StreamAddons.base(url).substringAfter("://").substringBefore('/'), Modifier.weight(1f))
                EdgeButton("Remove", { save(urls.filterIndexed { j, _ -> j != i }.joinToString("\n")) }, icon = Icons.Rounded.Delete)
            }
        }
        Field("Addon manifest URL", draft, "e.g. https://…/manifest.json", password = true) { draft = it.trim(); status = null }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            EdgeButton(if (checking) "Checking…" else "Add addon", {
                val url = draft
                if (url.isBlank() || checking) return@EdgeButton
                checking = true
                scope.launch {
                    val res = runCatching { com.edgestream.tv.data.StreamAddons.manifest(url) }
                    checking = false
                    res.onSuccess { m ->
                        if (urls.none { com.edgestream.tv.data.StreamAddons.base(it) == m.url }) save((urls + url).joinToString("\n"))
                        draft = ""; status = "Added ${m.name}"
                    }.onFailure { status = "Couldn't add it: ${it.message ?: "not a stream add-on"}" }
                }
            }, primary = true, icon = Icons.Rounded.Add)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        }
    }
}

/**
 * Transfer Setup: this TV either waits for another one (showing a code) or sends its whole
 * setup to one it finds on the network, after the code from that TV is entered here.
 */
@Composable
private fun TransferSettings(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val receiving by com.edgestream.tv.remote.SetupReceiver.status.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<com.edgestream.tv.remote.SetupSender.Peer?>(null) }
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (com.edgestream.tv.remote.SetupReceiver.status.value is com.edgestream.tv.remote.SetupReceiver.Status.Waiting) com.edgestream.tv.remote.SetupReceiver.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Moves providers, settings, profiles, My List, favourites, followed teams, channel picks, the calendar and progress. " +
                "The other TV's own setup is replaced. Both TVs must be on the same network.",
            style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        when (val r = receiving) {
            is com.edgestream.tv.remote.SetupReceiver.Status.Waiting -> {
                Text("Code", style = MaterialTheme.typography.labelLarge, color = Edge.Accent, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                Text(r.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium, color = Edge.Text, modifier = Modifier.padding(start = 4.dp))
                Text("On the other TV, open Settings › Transfer Setup › Send Setup, choose ${android.os.Build.MODEL} and enter this code.", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                SettingRow("Stop Waiting", null, { com.edgestream.tv.remote.SetupReceiver.stop() }, icon = Icons.Rounded.Close) {}
                return@Column
            }
            com.edgestream.tv.remote.SetupReceiver.Status.Importing -> { Text("Applying the setup…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp)); return@Column }
            com.edgestream.tv.remote.SetupReceiver.Status.Done -> { Text("Setup received. EdgeStream is restarting…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp)); return@Column }
            is com.edgestream.tv.remote.SetupReceiver.Status.Failed -> Text(r.message, style = MaterialTheme.typography.bodyMedium, color = Edge.Alert, modifier = Modifier.padding(4.dp))
            com.edgestream.tv.remote.SetupReceiver.Status.Idle -> Unit
        }
        if (!sending) {
            SettingRow("Receive Setup", "Replace this TV's setup with another TV's", { com.edgestream.tv.remote.SetupReceiver.start() }, icon = Icons.Rounded.Download) {}
            SettingRow("Send Setup", "Copy this TV's setup to another TV", { sending = true; result = null }, icon = Icons.Rounded.Upload) {}
            return@Column
        }
        val peers by remember { com.edgestream.tv.remote.SetupSender.discover(context) }.collectAsStateWithLifecycle(emptyList())
        val chosen = target
        if (chosen == null) {
            Text(if (peers.isEmpty()) "Looking for other TVs with EdgeStream open…" else "Choose the TV to send to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp))
            peers.forEach { p -> SettingRow(p.name, p.host, { target = p; code = ""; result = null }, icon = Icons.Rounded.Tv) {} }
            SettingRow("Cancel", null, { sending = false }, icon = Icons.Rounded.Close) {}
            return@Column
        }
        Text("Send to ${chosen.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp))
        Field("Code shown on ${chosen.name}", code, "6 digits", keyboard = KeyboardType.NumberPassword) { code = it.filter(Char::isDigit).take(6) }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it == SENT) Edge.Accent else Edge.Alert, modifier = Modifier.padding(4.dp)) }
        SettingRow(if (busy) "Sending…" else "Send", null, {
            if (busy || code.length != 6) return@SettingRow
            busy = true
            scope.launch {
                result = com.edgestream.tv.remote.SetupSender.send(context, vm.repo, chosen, code) ?: SENT
                busy = false
            }
        }, icon = Icons.Rounded.Send) {}
        SettingRow("Back", null, { target = null }, icon = Icons.Rounded.Close) {}
    }
}

private const val SENT = "Sent. The other TV restarts with this setup."

/**
 * Jellyfin, Emby and Plex servers: listed with a remove action, plus sign-in for each kind.
 * Plex links with a code entered at plex.tv/link, so no password is typed on the TV.
 */
@Composable
private fun MediaServerSettings() {
    val servers by com.edgestream.tv.data.MediaServers.servers.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf<com.edgestream.tv.data.MediaServers.Kind?>(null) }
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var plexCode by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        servers.forEach { srv ->
            SettingRow(srv.name, "${srv.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${srv.url} · press to remove", { com.edgestream.tv.data.MediaServers.remove(srv.id) }, icon = Icons.Rounded.Dns) {}
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(4.dp)) }
        val kind = form
        when {
            plexCode != null -> {
                Text("Go to plex.tv/link on your phone or computer and enter", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(start = 4.dp))
                Text(plexCode.orEmpty(), style = MaterialTheme.typography.displayMedium, color = Edge.Text, modifier = Modifier.padding(start = 4.dp))
                SettingRow("Cancel", null, { plexCode = null }, icon = Icons.Rounded.Close) {}
            }
            kind != null -> {
                Field("Server address", address, "e.g. 192.168.1.20:8096") { address = it }
                Field("Username", user, "") { user = it }
                Field("Password", pass, "", password = true) { pass = it }
                SettingRow(if (busy) "Signing In…" else "Sign In", null, {
                    if (busy || address.isBlank()) return@SettingRow
                    busy = true
                    scope.launch {
                        val error = com.edgestream.tv.data.MediaServers.signIn(kind, address, user, pass)
                        busy = false
                        if (error == null) { form = null; pass = ""; message = "Signed in. Its films and shows now appear in On Demand." } else message = error
                    }
                }, icon = Icons.Rounded.Login) {}
                SettingRow("Cancel", null, { form = null; message = null }, icon = Icons.Rounded.Close) {}
            }
            else -> {
                SettingRow("Add Jellyfin Server", "Sign in with your server address and account", { form = com.edgestream.tv.data.MediaServers.Kind.JELLYFIN; message = null }, icon = Icons.Rounded.Add) {}
                SettingRow("Add Emby Server", "Sign in with your server address and account", { form = com.edgestream.tv.data.MediaServers.Kind.EMBY; message = null }, icon = Icons.Rounded.Add) {}
                SettingRow("Link Plex", "Enter a code at plex.tv/link", {
                    message = null
                    scope.launch {
                        val pin = runCatching { com.edgestream.tv.data.MediaServers.plexPin() }.getOrElse { message = "Couldn't reach plex.tv"; return@launch }
                        plexCode = pin.code
                        // Plex codes last 15 minutes; check every 3 seconds until approved or cancelled.
                        repeat(300) {
                            delay(3_000)
                            if (plexCode != pin.code) return@launch
                            val added = runCatching { com.edgestream.tv.data.MediaServers.plexClaim(pin) }.getOrNull()
                            if (added != null) {
                                plexCode = null
                                message = if (added == 0) "Linked, but this Plex account has no servers" else "Linked $added Plex server${if (added == 1) "" else "s"}"
                                return@launch
                            }
                        }
                        plexCode = null; message = "The Plex code expired. Try again."
                    }
                }, icon = Icons.Rounded.Link) {}
            }
        }
    }
}

/** Link or unlink Trakt and Simkl: a code to enter on their website, checked until approved. */
@Composable
private fun TrackingLinks(s: AppSettings) {
    val linked by com.edgestream.tv.data.WatchTracking.linked.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pair<String, com.edgestream.tv.data.WatchTracking.LinkCode>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun link(service: String, start: suspend () -> com.edgestream.tv.data.WatchTracking.LinkCode, claim: suspend (com.edgestream.tv.data.WatchTracking.LinkCode) -> Boolean) {
        message = null
        scope.launch {
            val code = runCatching { start() }.getOrElse { message = it.message ?: "Couldn't reach $service"; return@launch }
            pending = service to code
            val deadline = System.currentTimeMillis() + code.expiresSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(code.intervalSec.coerceAtLeast(3) * 1000L)
                if (pending?.second != code) return@launch
                if (runCatching { claim(code) }.getOrDefault(false)) { pending = null; message = "$service linked. Films and episodes you watch are tracked."; return@launch }
            }
            pending = null; message = "The $service code expired. Try again."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(4.dp)) }
        val p = pending
        if (p != null) {
            Text("Go to ${p.second.url} and enter", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(start = 4.dp))
            Text(p.second.code, style = MaterialTheme.typography.displayMedium, color = Edge.Text, modifier = Modifier.padding(start = 4.dp))
            SettingRow("Cancel", null, { pending = null }, icon = Icons.Rounded.Close) {}
            return@Column
        }
        if (linked.trakt) SettingRow("Unlink Trakt", "Watching is scrobbled to Trakt", { com.edgestream.tv.data.WatchTracking.unlinkTrakt() }, icon = Icons.Rounded.LinkOff) {}
        else SettingRow("Link Trakt", if (s.traktClientId.isBlank() || s.traktClientSecret.isBlank()) "Add the Trakt Client ID and Secret above first" else "Enter a code at trakt.tv/activate", {
            if (s.traktClientId.isNotBlank() && s.traktClientSecret.isNotBlank()) link("Trakt", { com.edgestream.tv.data.WatchTracking.traktCode(s.traktClientId) }) { com.edgestream.tv.data.WatchTracking.traktClaim(it, s.traktClientId, s.traktClientSecret) }
        }, icon = Icons.Rounded.Link) {}
        if (linked.simkl) SettingRow("Unlink Simkl", "Finished titles are added to Simkl", { com.edgestream.tv.data.WatchTracking.unlinkSimkl() }, icon = Icons.Rounded.LinkOff) {}
        else SettingRow("Link Simkl", if (s.simklClientId.isBlank()) "Add the Simkl Client ID above first" else "Enter a code at simkl.com/pin", {
            if (s.simklClientId.isNotBlank()) link("Simkl", { com.edgestream.tv.data.WatchTracking.simklCode(s.simklClientId) }) { com.edgestream.tv.data.WatchTracking.simklClaim(it, s.simklClientId) }
        }, icon = Icons.Rounded.Link) {}
    }
}
