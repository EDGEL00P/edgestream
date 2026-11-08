package com.edgestream.tv.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import com.edgestream.tv.ui.EdgeShapes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import com.edgestream.tv.ui.DeviceTier
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.Motion
import com.edgestream.tv.ui.FocusMemory
import com.edgestream.tv.ui.UiSounds
import com.edgestream.tv.ui.glass
import kotlinx.coroutines.delay

/** A row-level focus counter: only siblings in the same row recede when one is focused. */
class FocusDimState {
    var count by mutableIntStateOf(0)
}

val LocalFocusDim = androidx.compose.runtime.staticCompositionLocalOf<FocusDimState?> { null }

@Composable
fun FocusDimGroup(content: @Composable () -> Unit) {
    val state = remember { FocusDimState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFocusDim provides state, content = content)
}

/**
 * The building block for everything selectable with a remote: a surface that lifts, brightens
 * and gains a soft light when focused. Long-press on the D-pad centre triggers [onLongClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = EdgeShapes.Card,
    color: Color = Edge.Panel,
    focusedColor: Color = Edge.PanelHi,
    selected: Boolean = false,
    focusScale: Float = 1.03f,
    glow: Boolean = true,
    /** Optional "row|item" key for restoring the last focused card in this row. */
    memoryKey: String? = null,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val dimGroup = LocalFocusDim.current
    androidx.compose.runtime.DisposableEffect(focused) {
        if (focused) dimGroup?.count = (dimGroup?.count ?: 0) + 1
        onDispose { if (focused) dimGroup?.count = ((dimGroup?.count ?: 1) - 1).coerceAtLeast(0) }
    }
    val memFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val memRow = memoryKey?.substringBefore('|')
    val memItem = memoryKey?.substringAfter('|')
    LaunchedEffect(Unit) {
        if (memRow != null && memItem != null && FocusMemory.last(memRow) == memItem) {
            delay(70)
            runCatching { memFocus.requestFocus() }
        }
    }
    LaunchedEffect(focused) {
        if (focused && memRow != null && memItem != null) FocusMemory.remember(memRow, memItem)
    }
    val tier = DeviceTier.tier
    val anim = Motion.focusSpring<Float>()
    val scale = animateFloatAsState(
        if (pressed) focusScale - 0.02f else if (focused) focusScale else 1f,
        anim, label = "focusScale",
    )
    // Interface voice: a quiet tick on focus, a confirm thunk on select.
    LaunchedEffect(focused) { if (focused) UiSounds.focus() }
    val fill = if (focused) focusedColor else color
    // In the main window the shared beam marks focus; dialogs live in their own window and
    // draw the edge themselves.
    val beamed = FocusBeam.hostRoot != null && androidx.compose.ui.platform.LocalView.current.rootView === FocusBeam.hostRoot
    val beamToken = remember { Any() }
    val coordsRef = remember { object { var v: androidx.compose.ui.layout.LayoutCoordinates? = null } }
    androidx.compose.runtime.DisposableEffect(focused, beamed) {
        if (focused && beamed) coordsRef.v?.takeIf { it.isAttached }?.let { FocusBeam.claim(beamToken, it.boundsInRoot()) }
        onDispose { FocusBeam.release(beamToken) }
    }
    // Glass gets its sheen and edge light; transparent list rows stay flat until focused.
    val isGlass = fill.alpha > 0.02f
    val dimAlpha = when (tier) {
        DeviceTier.Tier.FULL -> 0.82f
        DeviceTier.Tier.BALANCED -> 0.87f
        DeviceTier.Tier.LOW -> 0.90f
    }
    Box(
        modifier
            .then(if (memoryKey != null) Modifier.focusRequester(memFocus) else Modifier)
            // The focused card draws over its neighbours so its scaled edge is never cut off.
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                // Neighbour dimming is opacity-only: no extra layers, no per-item blur.
                alpha = if (!focused && (dimGroup?.count ?: 0) > 0) dimAlpha else 1f
                // Only the focused card pays for a shadow. FULL gets a tinted glow; weaker
                // boxes get a single neutral lift; LOW stays flat.
                if (focused && glow && tier != DeviceTier.Tier.LOW) {
                    shadowElevation = if (tier == DeviceTier.Tier.FULL) 22.dp.toPx() else 8.dp.toPx()
                    this.shape = shape; clip = false
                    if (tier == DeviceTier.Tier.FULL) {
                        ambientShadowColor = Edge.Accent.copy(alpha = 0.38f)
                        spotShadowColor = Edge.Accent.copy(alpha = 0.24f)
                    } else {
                        ambientShadowColor = Color.Black
                        spotShadowColor = Color.Black
                    }
                } else {
                    // graphicsLayer keeps the last values; reset or the shadow stays after blur.
                    shadowElevation = 0f
                    clip = true
                }
            }
            // After the scale layer, so the beam hugs the card at its focused size.
            .onGloballyPositioned { c ->
                coordsRef.v = c
                if (focused && beamed) FocusBeam.claim(beamToken, c.boundsInRoot())
            }
            .clip(shape)
            .background(fill)
            // Outside the beam's window (dialogs): a thin accent edge and a lit leading bar.
            .then(
                if (focused && !beamed) Modifier.border(1.5.dp, Edge.Accent.copy(alpha = 0.9f), shape).drawWithContent {
                    drawContent()
                    drawRect(Edge.Accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
                } else Modifier,
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    UiSounds.click()
                    onClick()
                },
                onLongClick = onLongClick,
            ),
    ) { content(focused) }
}

/**
 * Real artwork only: remote provider, ESPN, TMDB or similar real artwork. If the URL is blank
 * or fails, the fallback is the item's initials — never a generated placeholder or AI image.
 */
@Composable
fun Logo(url: String?, name: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val sizeResolver = rememberConstraintsSizeResolver()
    Box(modifier.clip(EdgeShapes.Poster).background(Edge.PanelHi), contentAlignment = Alignment.Center) {
        val fallback = @Composable {
            Text(
                initials(name), color = Edge.Dim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            )
        }
        if (url.isNullOrBlank()) fallback()
        else SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current).data(url).size(sizeResolver).build(),
            contentDescription = null, contentScale = contentScale,
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(if (contentScale == ContentScale.Fit) 4.dp else 0.dp),
            loading = { fallback() }, error = { fallback() },
        )
    }
}

private fun initials(name: String): String =
    name.split(' ', '-', '|', ':').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        .take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Edge.Line) {
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(50)).background(Edge.AccentGradient),
        )
    }
}

@Composable
/**
 * A status label. Only three looks exist, so screens never turn into a rainbow: the theme accent
 * (live, selected), red for alerts, and a neutral chip for everything else — whatever [color] a
 * caller passes, other colours render neutral.
 */
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Edge.Accent) {
    val (fill, ink) = when (color) {
        Edge.Accent -> Edge.Accent to Edge.OnAccent
        Edge.Alert -> Edge.Alert to Color.White
        else -> Edge.Text.copy(alpha = 0.14f) to Edge.Text
    }
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = ink,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp)).background(fill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    // Compact section head for panels/lists: sentence-case label over a hairline.
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.weight(1f).padding(start = 12.dp).height(1.dp)
                .background(Edge.Line.copy(alpha = 0.6f)),
        )
    }
}

/**
 * The one rail header + row every tab uses, so Sports, Movies, TorBox, Home and Search
 * read as the same product: a sentence-case title over a hairline, then rows spaced by the
 * shared card gap with focus restoration.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    /** When set, the viewport snaps to whole cards so the row never parks a half-poster at the edge. */
    itemWidth: Dp? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.weight(1f).padding(start = 16.dp).height(1.dp).background(Edge.Line))
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = CardSize.RowGap
            val rowWidth = itemWidth?.let { width ->
                val unit = width + gap
                val fit = ((maxWidth + gap) / unit).toInt().coerceAtLeast(1)
                (unit * fit - gap).coerceAtMost(maxWidth)
            }
            val state = androidx.compose.foundation.lazy.rememberLazyListState()
            Box {
                FocusDimGroup {
                    LazyRow(
                        Modifier.then(if (rowWidth != null) Modifier.width(rowWidth) else Modifier.fillMaxWidth()).focusRestorer(),
                        state = state,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        contentPadding = PaddingValues(end = 64.dp, top = 16.dp, bottom = 16.dp),
                        content = content,
                    )
                }
                if (state.canScrollBackward) {
                    Box(
                        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(28.dp)
                            .background(Brush.horizontalGradient(listOf(Edge.Bg.copy(alpha = 0.92f), Color.Transparent))),
                    )
                }
                if (state.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, Edge.Bg.copy(alpha = 0.92f)))),
                    )
                }
            }
        }
    }
}

/**
 * The one filter chip every tab uses (Guide groups, Sports country/league, season tabs):
 * same padding, same selected wash, same label type, so tabs never drift apart.
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    memoryKey: String? = null,
    content: @Composable () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) Edge.Accent.copy(alpha = 0.18f) else Edge.Panel,
        selected = selected,
        focusScale = 1.02f,
        glow = false,
        memoryKey = memoryKey,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            leading?.invoke()
            content()
        }
    }
}

/** A filter chip with a plain text label. */
@Composable
fun FilterChipText(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(selected, onClick, modifier) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = Edge.Text, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one page title every tab uses: headline weight, same baseline, with an optional
 * right-aligned status (Updating…, counts, clock) so headers align across tabs.
 */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, status: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status != null) {
            Spacer(Modifier.weight(1f))
            status()
        }
    }
}

@Composable
fun EdgeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, fill: Boolean = false) {
    FocusSurface(
        onClick = onClick, modifier = modifier.height(52.dp), shape = EdgeShapes.Chip,
        color = if (primary) Edge.Accent.copy(alpha = 0.30f) else Edge.Panel,
        focusedColor = if (primary) Edge.Accent else Edge.Text, focusScale = 1.06f,
    ) { focused ->
        // One flat fill: accent (primary) or white when focused, a quiet tint when not.
        val background = when {
            primary -> if (focused) Edge.Accent else Edge.Accent.copy(alpha = 0.22f)
            else -> if (focused) Edge.Text else Edge.Text.copy(alpha = 0.10f)
        }
        // Deliberately no fill modifiers: this content must wrap to its intrinsic size, because
        // callers may place the button under unbounded constraints (weight(1f, fill = false)).
        // A fill here collapses the button to its padding and breaks D-pad focus geometry.
        Row(
            Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .background(background).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val onFocused = if (primary) Edge.OnAccent else Edge.Bg
            if (icon != null) androidx.compose.material3.Icon(icon, null, Modifier.padding(end = 8.dp).size(20.dp), tint = if (focused) onFocused else Edge.Dim)
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (focused) onFocused else Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.LiveTv) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).glass(EdgeShapes.Panel, Edge.PanelHi),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(icon, null, Modifier.size(32.dp), tint = Edge.Accent)
            }
            Text(
                title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
            )
            Text(
                body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

@Composable
fun OneLine(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified) =
    Text(text, modifier = modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

/**
 * A soft breathing alpha for loading placeholders — the premium way to say "content is coming"
 * without spinners. Draw-phase only, so it costs nothing on the composition side.
 */
@Composable
fun Modifier.loadingPulse(): Modifier {
    val alpha by androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
        .animateFloat(
            0.45f, 0.85f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    return this.graphicsLayer { this.alpha = alpha }
}

/** A group name as shown in lists: the built-in groups get an icon instead of a text glyph. */
@Composable
fun GroupLabel(name: String, color: Color, modifier: Modifier = Modifier) {
    val (icon, text) = when (name) {
        com.edgestream.tv.ui.AppViewModel.FAVOURITES -> Icons.Rounded.Star to "Favourites"
        com.edgestream.tv.ui.AppViewModel.RECENT -> Icons.Rounded.History to "Recently watched"
        com.edgestream.tv.ui.AppViewModel.ALL -> Icons.Rounded.GridView to "All channels"
        else -> null to name
    }
    // ChannelTaxonomy keys are "Country · Genre". Keep the country visible: tidying it away
    // turns every country's "Sports" into the same-looking row.
    val label = remember(name, Edge.Faint) {
        val split = text.indexOf(" · ")
        if (split <= 0) AnnotatedString(com.edgestream.tv.data.NameCleaner.tidyGroup(text))
        else buildAnnotatedString {
            withStyle(SpanStyle(color = Edge.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)) {
                append(text.substring(0, split).uppercase())
            }
            withStyle(SpanStyle(color = Edge.Faint)) { append("  ·  ") }
            append(text.substring(split + 3))
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) androidx.compose.material3.Icon(icon, null, Modifier.padding(end = 8.dp).size(18.dp), tint = Edge.Accent)
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * How every list in the app follows D-pad focus. The TV default pivots the focused item towards
 * the middle of the list on every press, so the page lurches even when the item was already on
 * screen. This scrolls only when the item would be cut off, and by just enough to show it plus
 * [margin] (room for row titles above and the next item's edge below).
 */
@OptIn(ExperimentalFoundationApi::class)
class MinimalScroll(private val margin: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin
        val end = offset + size + margin
        return when {
            start >= 0 && end <= containerSize -> 0f
            size + 2 * margin > containerSize || start < 0 -> start
            else -> end - containerSize
        }
    }
}

/** A VOD poster card with rating pill and a title reveal on focus. */
@Composable
fun PosterCard(v: com.edgestream.tv.data.db.VodEntity, modifier: Modifier = Modifier, memoryKey: String? = null, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.width(CardSize.Poster).aspectRatio(2f / 3f), shape = EdgeShapes.Poster, focusScale = 1.08f, memoryKey = memoryKey) { f ->
        Logo(v.poster, v.name, Modifier.fillMaxSize(), ContentScale.Crop)
        v.rating?.let { r -> Pill("★ " + (r.toDoubleOrNull()?.let { "%.1f".format(it) } ?: r), Modifier.align(Alignment.TopEnd).padding(8.dp), Edge.Gold) }
        if (f || v.poster == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(com.edgestream.tv.data.NameCleaner.tidyTitle(v.name), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = v.year.orEmpty()
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
