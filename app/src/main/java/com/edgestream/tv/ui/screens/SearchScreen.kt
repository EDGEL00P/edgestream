package com.edgestream.tv.ui.screens

import com.edgestream.tv.ui.reportsTextEntry
import androidx.compose.ui.focus.focusRestorer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.NameCleaner
import com.edgestream.tv.data.SearchKit
import com.edgestream.tv.data.SearchSuggestion
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.data.db.VodEntity
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.glass
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Logo
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.PosterCard
import com.edgestream.tv.ui.components.SectionTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

private data class Results(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<Pair<ProgrammeEntity, ChannelEntity>> = emptyList(),
    /** The universal catalogue: anything by name, playable through the debrid service. */
    val anywhereMovies: List<com.edgestream.tv.ui.BrowseItem> = emptyList(),
    val anywhereShows: List<com.edgestream.tv.ui.BrowseItem> = emptyList(),
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf(Results()) }
    // True from the first keystroke until the universal catalogue has answered: "no matches"
    // must never appear while results are still on their way.
    var searching by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val allChannels by vm.channels.collectAsStateWithLifecycle()
    val recent by SearchKit.recent.collectAsStateWithLifecycle()
    val games by vm.sportsGames.collectAsStateWithLifecycle()
    val nfl by vm.nflBoard.collectAsStateWithLifecycle()
    val lookup by vm.sportsLookup.collectAsStateWithLifecycle()
    // Ask: direct answers (live games, picks, props) ahead of the ordinary results.
    val answers = remember(query, games, nfl) { com.edgestream.tv.data.Ask.answer(query, games, nfl) }
    val groups = remember(allChannels) {
        allChannels.asSequence()
            .map { NameCleaner.tidyGroup(it.groupName) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }
    val suggestions = remember(query, allChannels, groups, recent) {
        SearchKit.suggestions(query, allChannels, groups)
    }
    val tune: (SearchSuggestion) -> Unit = { suggestion ->
        SearchKit.remember(suggestion.query)
        val channel = suggestion.channelKey?.let { key -> allChannels.firstOrNull { it.key == key } }
        if (channel != null) {
            vm.playChannel(channel, allChannels)
        } else {
            vm.searchQuery.value = suggestion.query
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = Results(); searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        val byEpg = allChannels.filter { it.epgId != null }.groupBy { it.epgId.orEmpty() }
        val local = Results(
            channels = vm.display(vm.repo.searchChannels(q)),
            programmes = vm.repo.searchProgrammes(q).mapNotNull { p -> byEpg[p.epgId]?.firstOrNull()?.let { p to it } },
        )
        results = local
        // Channels and programmes paint first; the universal catalogue follows.
        fun items(list: List<com.edgestream.tv.data.Catalog.Title>) = list.map { t ->
            com.edgestream.tv.ui.BrowseItem(t.id, t.title, t.year, t.poster, t.backdrop, t.overview, t.rating, t.releaseDate, null, t.imdbId)
        }
        val movies = kotlinx.coroutines.coroutineScope {
            val m = async { com.edgestream.tv.data.Catalog.search(q, movies = true) }
            val s = async { com.edgestream.tv.data.Catalog.search(q, movies = false) }
            m.await() to s.await()
        }
        results = local.copy(anywhereMovies = items(movies.first), anywhereShows = items(movies.second))
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 28.dp, end = 42.dp, bottom = 36.dp)) {
        val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { vm.searchQuery.value = it }
        }
        SearchField(
            query,
            onVoice = {
                runCatching {
                    voiceLauncher.launch(
                        Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            ),
                    )
                }
            },
            onSubmit = { text ->
                SearchKit.remember(text)
                // A single exact channel hit tunes straight away: no second OK needed.
                val exact = suggestions.firstOrNull { it.channelKey != null && it.label.equals(text.trim(), ignoreCase = true) }
                val channel = exact?.channelKey?.let { key -> allChannels.firstOrNull { it.key == key } }
                if (channel != null) vm.playChannel(channel, allChannels)
            },
        ) { vm.searchQuery.value = it }
        if (suggestions.isNotEmpty()) {
            SuggestionRail(
                suggestions,
                showingRecent = query.isBlank(),
                onPick = tune,
                onClearRecent = { SearchKit.clearRecent() },
            )
        }
        val empty = answers.isEmpty() && results.channels.isEmpty() && results.programmes.isEmpty() &&
            results.anywhereMovies.isEmpty() && results.anywhereShows.isEmpty()
        if (empty && searching && query.trim().length >= 2) {
            // Still looking (the universal catalogue answers after the provider's own rows).
            Row(Modifier.padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Edge.Accent, strokeWidth = 3.dp)
                Text("  Searching everywhere for “${query.trim()}”…", style = MaterialTheme.typography.titleMedium, color = Edge.Dim)
            }
            return@Column
        }
        if (empty) {
            EmptyState(
                if (query.trim().length < 2) "Find anything" else "No matches for “${query.trim()}”",
                "Search channels, the guide, movies and series. Ask for a team, \"live\", \"locks\" or \"props\".",
                Modifier.fillMaxSize(),
                icon = Icons.Rounded.Search,
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (answers.isNotEmpty()) item {
                com.edgestream.tv.ui.components.Shelf("Answers · ${answers.size}") {
                    items(answers, key = { a -> a.title + a.detail }) { a ->
                        AnswerCard(a) {
                            when (a) {
                                is com.edgestream.tv.data.Ask.Answer.Live -> lookup.find(a.game)?.let { ch -> vm.playChannel(ch, vm.zapListFor(ch)) }
                                    ?: vm.showNotice("No channel in your lineup is carrying ${a.game.away.abbr} v ${a.game.home.abbr}")
                                is com.edgestream.tv.data.Ask.Answer.Pick -> vm.openNflPick(a.pick.espnId)
                                is com.edgestream.tv.data.Ask.Answer.Prop -> vm.openNflPick(a.pick.espnId)
                            }
                        }
                    }
                }
            }
            if (results.channels.isNotEmpty()) item {
                com.edgestream.tv.ui.components.Shelf("Channels \u00b7 ${results.channels.size}") {
                    items(results.channels, key = { it.key }) { ch ->
                        FocusSurface(onClick = { vm.playChannel(ch, results.channels) }, onLongClick = { vm.toggleFavorite(ch.key) }, modifier = Modifier.width(216.dp).height(108.dp)) {
                            Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Logo(ch.logo, ch.name, Modifier.size(64.dp, 46.dp))
                                Column(Modifier.padding(start = 10.dp)) {
                                    OneLine(ch.name, style = MaterialTheme.typography.titleMedium)
                                    OneLine(vm.epgFor(ch)?.first?.title ?: com.edgestream.tv.data.NameCleaner.tidyGroup(ch.groupName), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            if (results.programmes.isNotEmpty()) item {
                SectionTitle("In the guide · ${results.programmes.size}")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.programmes.take(30).forEach { (p, ch) ->
                        val onNow = p.start <= clock && p.stop > clock
                        FocusSurface(
                            onClick = { vm.playChannel(ch, listOf(ch)) },
                            modifier = Modifier.fillMaxWidth().height(70.dp), focusScale = 1.02f,
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (onNow) Pill("On now", color = Edge.Live) else Pill(Format.day(p.start), color = Edge.Gold)
                                Text("  ${Format.range(p.start, p.stop, settings.use24h)}", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.width(150.dp))
                                OneLine(p.title, Modifier.weight(1f), MaterialTheme.typography.titleMedium)
                                Logo(ch.logo, ch.name, Modifier.padding(start = 12.dp).size(44.dp, 32.dp))
                                OneLine("  ${ch.name}", Modifier.width(160.dp), MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            listOf(
                Triple("Movies from anywhere", results.anywhereMovies, true),
                Triple("Shows from anywhere", results.anywhereShows, false),
            ).filter { it.second.isNotEmpty() }.forEach { (title, list, movies) ->
                item(key = title) {
                    com.edgestream.tv.ui.components.Shelf("$title · ${list.size}") {
                        items(list, key = { it.id }) { t ->
                            com.edgestream.tv.ui.components.TitleTile(
                                t.title, t.poster,
                                meta = listOfNotNull(t.year, t.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                            ) { vm.openUniversal(t, movies) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRail(
    suggestions: List<SearchSuggestion>,
    showingRecent: Boolean,
    onPick: (SearchSuggestion) -> Unit,
    onClearRecent: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f).drawWithContent {
                drawContent()
                val fade = 48.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Edge.Bg),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                )
            },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(suggestions, key = { "${it.channelKey ?: "q"}:${it.query}" }) { s ->
                FocusSurface(
                    onClick = { onPick(s) },
                    shape = RoundedCornerShape(14.dp),
                    glow = false,
                    focusScale = 1.03f,
                    color = Edge.Panel,
                ) { focused ->
                    Row(
                        Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            when {
                                s.isRecent -> Icons.Rounded.History
                                s.channelKey != null -> Icons.Rounded.Tv
                                else -> Icons.Rounded.Folder
                            },
                            null,
                            Modifier.size(18.dp),
                            tint = if (focused) Edge.Accent else Edge.Faint,
                        )
                        Column(Modifier.padding(start = 9.dp)) {
                            OneLine(s.label, style = MaterialTheme.typography.titleSmall)
                            OneLine(s.detail, style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
                        }
                    }
                }
            }
        }
        if (showingRecent) {
            FocusSurface(
                onClick = onClearRecent,
                shape = RoundedCornerShape(50),
                glow = false,
                focusScale = 1.03f,
                color = Color.Transparent,
            ) { f ->
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (f) Edge.Accent else Edge.Faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A text field that behaves on a remote: OK opens the keyboard, focus is clearly visible. */
/** One Ask answer: what it is, the answer itself, and the detail that backs it. */
@Composable
private fun AnswerCard(a: com.edgestream.tv.data.Ask.Answer, onClick: () -> Unit) {
    val kind = when (a) {
        is com.edgestream.tv.data.Ask.Answer.Live -> "LIVE"
        is com.edgestream.tv.data.Ask.Answer.Pick -> "NFL PICK"
        is com.edgestream.tv.data.Ask.Answer.Prop -> "PROP"
    }
    FocusSurface(onClick = onClick, modifier = Modifier.width(380.dp).height(112.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kind, style = MaterialTheme.typography.labelSmall, color = Edge.Accent)
            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(a.detail, style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SearchField(
    value: String,
    placeholder: String = "Search channels, programmes, movies…",
    onVoice: (() -> Unit)? = null,
    onSubmit: (String) -> Unit = {},
    onChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    com.edgestream.tv.ui.PhoneFillable(focused, "Search", value, secret = false, onChange)
    // Retry for a full second: during the screen crossfade the field is not attached yet.
    LaunchedEffect(Unit) { repeat(20) { delay(50); runCatching { focus.requestFocus() } } }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        cursorBrush = SolidColor(Edge.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit(value) }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .reportsTextEntry(com.edgestream.tv.ui.LocalTextEntry.current)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                when {
                    e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter) -> { keyboard?.show(); true }
                    // A single-line field eats Down as "cursor to end"; on a remote it must go to the results.
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> { keyboard?.hide(); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(68.dp).glass(RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(Icons.Rounded.Search, null, Modifier.padding(end = 12.dp).size(28.dp), tint = Edge.Accent)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleLarge, color = Edge.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (onVoice != null) {
                    FocusSurface(
                        onClick = onVoice,
                        modifier = Modifier.padding(start = 10.dp).size(44.dp),
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent,
                        focusedColor = Edge.Accent,
                        focusScale = 1.08f,
                        glow = false,
                    ) { f ->
                        androidx.compose.material3.Icon(
                            Icons.Rounded.Mic, "Voice search", Modifier.align(Alignment.Center).size(24.dp),
                            tint = if (f) Edge.OnAccent else Edge.Dim,
                        )
                    }
                }
            }
        },
    )
}
