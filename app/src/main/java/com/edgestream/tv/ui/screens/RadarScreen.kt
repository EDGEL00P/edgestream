package com.edgestream.tv.ui.screens

import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.glass.SHADE_TEXT
import com.edgestream.tv.ui.glass.SHADE_STRIP
import com.edgestream.tv.ui.components.requestFocusWithRetry
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.PublicApis
import com.edgestream.tv.data.Radar
import com.edgestream.tv.data.Storm
import com.edgestream.tv.data.Trackers
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.components.WeatherGlyph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Professional weather radar: live satellite, place labels, rain analysis and station data. */
@Composable
fun RadarScreen(vm: AppViewModel) {
    val place by vm.place.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<Radar.Data?>(null) }
    var loading by remember { mutableStateOf(true) }
    var zoom by remember { mutableIntStateOf(10) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }

    val detail by produceState<PublicApis.WeatherDetail?>(null, place?.lat, place?.lon) {
        val p = place ?: return@produceState
        while (true) {
            val fresh = withContext(Dispatchers.IO) { PublicApis.weatherDetail(p) }
            if (fresh != null) { value = fresh; break }
            delay(15_000)
        }
    }
    val simple by vm.weather.collectAsStateWithLifecycle()
    val nearby by produceState<List<PublicApis.NearbyPlace>>(emptyList(), place?.lat, place?.lon) {
        val p = place ?: return@produceState
        value = withContext(Dispatchers.IO) { PublicApis.nearby(p.lat, p.lon) }
    }
    val warnings by produceState<List<Trackers.Warning>>(emptyList(), place?.lat, place?.lon) {
        val p = place ?: return@produceState
        value = Trackers.warnings(p.countryCode, p.lat, p.lon)
    }
    val cyclones by produceState<List<Trackers.Cyclone>>(emptyList(), place?.lat, place?.lon) {
        val p = place ?: return@produceState
        value = Trackers.cyclones(p.lat, p.lon)
    }
    var detailsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(data, playing) {
        val frames = data?.frames.orEmpty()
        if (!playing || frames.size < 2) return@LaunchedEffect
        if (index > frames.lastIndex) index = 0
        while (true) {
            // Hold the newest frame so the loop has a clear end, then a shorter beat on the
            // oldest before it restarts. Without this the animation snaps backwards.
            val hold = when {
                index >= frames.lastIndex -> 1_800L
                index == 0 -> 900L
                else -> 650L
            }
            delay(hold)
            index = if (index >= frames.lastIndex) 0 else index + 1
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Full-scale radar: map fills the whole screen; info is a floating glass box inside it.
        val mapW = maxWidth
        val mapH = maxHeight
        val aspect = (mapW.value / mapH.value).coerceIn(1.05f, 2.8f)
        val radarW = 1600
        val radarH = (radarW / aspect).toInt().coerceIn(600, 1000)

        LaunchedEffect(place?.lat, place?.lon, zoom, settings.radarSatellite, radarW, radarH) {
            val p = place ?: return@LaunchedEffect
            var first = true
            while (true) {
                Radar.stream(p, zoom, width = radarW, height = radarH, satellite = settings.radarSatellite).collect { fresh ->
                    data = fresh
                    loading = false
                    if (fresh.frames.isNotEmpty() && first) {
                        first = false
                        index = 0
                        playing = true
                        focus.requestFocusWithRetry(tries = 20, gapMs = 50)
                    }
                }
                delay(120_000)
            }
        }

        // The map is the full-screen background. Conditions sit on it over a soft shade — no
        // boxed panel, no solid strip — and the controls are real focusable buttons.
        Box(Modifier.fillMaxSize()) {
            RadarMap(
                data = data,
                analysis = data?.analysis,
                zoom = zoom,
                index = index,
                playing = playing,
                use24h = settings.use24h,
                satellite = settings.radarSatellite,
                focus = focus,
                downFocus = controlsFocus,
                loading = loading,
                onIndex = { playing = false; index = it },
                onTogglePlay = { if (data?.frames.orEmpty().isNotEmpty()) playing = !playing },
                onLatest = {
                    data?.frames?.lastIndex?.takeIf { it >= 0 }?.let { playing = false; index = it }
                },
                onDetails = { detailsOpen = true },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.BottomStart).width(620.dp).height(390.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f to Edge.Bg.copy(alpha = SHADE_STRIP),
                            0.55f to Edge.Bg.copy(alpha = SHADE_TEXT),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            RadarHud(
                detail = detail,
                simple = simple,
                analysis = data?.analysis,
                frameTime = data?.frames?.getOrNull(index)?.time,
                rangeKm = Radar.rangeKm(zoom),
                warnings = warnings,
                use24h = settings.use24h,
                fahrenheit = settings.fahrenheit,
                modifier = Modifier.align(Alignment.BottomStart).width(520.dp)
                    .padding(start = 28.dp, bottom = 148.dp),
            )
            RadarControls(
                mapFocus = focus,
                firstFocus = controlsFocus,
                playing = playing,
                hasFrames = data?.frames.orEmpty().isNotEmpty(),
                zoom = zoom,
                onTogglePlay = { if (data?.frames.orEmpty().isNotEmpty()) playing = !playing },
                onStep = { delta ->
                    Radar.stepFrame(index, delta, data?.frames.orEmpty().lastIndex)?.let {
                        playing = false; index = it
                    }
                },
                onZoom = { zoom = it },
                onLatest = {
                    data?.frames?.lastIndex?.takeIf { it >= 0 }?.let { playing = false; index = it }
                },
                onDetails = { detailsOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 22.dp),
            )
        }
    }
    if (detailsOpen) {
        RadarDetailsDialog(
            detail = detail,
            nearby = nearby,
            warnings = warnings,
            cyclones = cyclones,
            analysis = data?.analysis,
            frameTime = data?.frames?.getOrNull(index)?.time,
            rangeKm = Radar.rangeKm(zoom),
            use24h = settings.use24h,
            fahrenheit = settings.fahrenheit,
            onClose = { detailsOpen = false },
        )
    }
}

@Composable
private fun RadarMap(
    data: Radar.Data?,
    analysis: Radar.Analysis?,
    zoom: Int,
    index: Int,
    playing: Boolean,
    use24h: Boolean,
    satellite: Boolean,
    focus: FocusRequester,
    downFocus: FocusRequester,
    loading: Boolean,
    onIndex: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onLatest: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frames = data?.frames.orEmpty()
    val frameImages = remember(frames) { frames.map { it.bitmap.asImageBitmap() } }
    val baseImage = remember(data?.base) { data?.base?.asImageBitmap() }
    val labelImage = remember(data?.labels) { data?.labels?.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    var mapFocused by remember { mutableStateOf(false) }
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        .animateFloat(
            initialValue = 0.55f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1600),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ), label = "pulse",
        )
    Box(
        modifier.background(Edge.Bg)
            .onFocusChanged { mapFocused = it.isFocused }
            .onPreviewKeyEvent { e ->
                // Only steer the map while the map itself holds focus; otherwise a preview handler
                // here would swallow the arrow keys meant for the control buttons beside it.
                if (e.type != KeyEventType.KeyDown || !mapFocused) return@onPreviewKeyEvent false
                when (e.key) {
                    // At the ends the key is not consumed: focus can leave the map, so the
                    // remote is never trapped inside the radar.
                    Key.DirectionLeft -> if (frames.isEmpty() || index == 0) false else {
                        onIndex(index - 1); true
                    }
                    Key.DirectionRight -> if (frames.isEmpty() || index == frames.lastIndex) false else {
                        onIndex(index + 1); true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onTogglePlay(); true }
                    // Up/Down are left unconsumed so D-pad focus can travel to the controls.
                    Key.Menu -> { onLatest(); frames.isNotEmpty() }
                    Key.Info -> { onDetails(); true }
                    else -> false
                }
            }
            .focusProperties { down = downFocus }
            .focusRequester(focus).focusable(),
    ) {
        if (data != null) {
            baseImage?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            // Satellite is bright; a graded smoke wash keeps radar colours and chrome legible.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Edge.Bg.copy(alpha = if (satellite) 0.30f else 0.10f),
                        0.5f to Edge.Bg.copy(alpha = if (satellite) 0.16f else 0.04f),
                        1f to Edge.Bg.copy(alpha = if (satellite) 0.45f else 0.30f),
                    ),
                ),
            )
            Crossfade(targetState = index, animationSpec = tween(110), label = "radarFrame") { i ->
                frameImages.getOrNull(i)?.let {
                    Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            labelImage?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.92f)
            }
            Canvas(Modifier.fillMaxSize()) {
                val kmPerPx = 156.543 * cos(Math.toRadians(data.lat)) / (1 shl data.zoom)
                val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 8f))
                val style = TextStyle(
                    color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    shadow = Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 4f),
                )
                Radar.rings(data.zoom).forEach { km ->
                    val radius = (km / kmPerPx).toFloat()
                    drawCircle(Edge.Text.copy(alpha = 0.16f), radius = radius, center = center, style = Stroke(width = 1.dp.toPx(), pathEffect = dash))
                    val at = androidx.compose.ui.geometry.Offset(center.x + radius * 0.707f, center.y - radius * 0.707f)
                    val label = measurer.measure("${km} km", style)
                    if (at.x + label.size.width < size.width - 8 && at.y > 8) {
                        drawText(label, topLeft = androidx.compose.ui.geometry.Offset(at.x + 4.dp.toPx(), at.y - label.size.height))
                    }
                }
                val r = 8.dp.toPx()
                // Pulsing user location in the theme colours.
                drawCircle(Edge.Text.copy(alpha = 0.18f * pulse), radius = r * (2.4f + pulse), center = center)
                drawCircle(Edge.Accent.copy(alpha = 0.25f), radius = r * 2.4f, center = center)
                drawCircle(Edge.Bg, radius = r, center = center)
                drawCircle(Edge.Accent, radius = r * 0.55f, center = center)
                drawCircle(Edge.Text.copy(alpha = 0.85f), radius = r * 0.22f, center = center)
                // Name the exact place, not just its marker.
                val placeStyle = TextStyle(
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    shadow = Shadow(Color.Black.copy(alpha = 0.95f), blurRadius = 6f),
                )
                val placeLabel = measurer.measure(data.place, placeStyle)
                drawText(
                    placeLabel,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x + r * 2.2f, center.y - placeLabel.size.height / 2f),
                )

                val frame = frames.getOrNull(index)?.bitmap
                val cells = data.analysis?.cells.orEmpty()
                if (frame != null && cells.isNotEmpty()) {
                    val scale = maxOf(size.width / frame.width, size.height / frame.height)
                    val ox = (size.width - frame.width * scale) / 2f
                    val oy = (size.height - frame.height * scale) / 2f
                    val cellStyle = TextStyle(
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        shadow = Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 5f),
                    )
                    cells.forEach { c ->
                        val at = androidx.compose.ui.geometry.Offset(ox + c.x * scale, oy + c.y * scale)
                        val colour = when {
                            c.maxDbz >= 55 -> Edge.Alert
                            else -> Color.White
                        }
                        val radius = (12 + c.maxDbz / 5).dp.toPx()
                        drawCircle(colour.copy(alpha = 0.18f), radius = radius, center = at)
                        drawCircle(colour, radius = radius, center = at, style = Stroke(width = 1.5.dp.toPx()))
                        val label = measurer.measure("C${c.id}", cellStyle)
                        drawText(label, topLeft = androidx.compose.ui.geometry.Offset(at.x + radius + 3.dp.toPx(), at.y - label.size.height / 2f))
                    }
                }
            }

            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Edge.Bg.copy(alpha = SHADE_TEXT), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(560.dp).background(Brush.verticalGradient(
                0f to Color.Transparent,
                0.30f to Edge.Bg.copy(alpha = SHADE_TEXT * 0.25f),
                0.65f to Edge.Bg.copy(alpha = SHADE_TEXT * 0.65f),
                1f to Edge.Bg.copy(alpha = SHADE_STRIP),
            )))
            Column(Modifier.align(Alignment.TopStart).padding(horizontal = 24.dp, vertical = 18.dp)) {
                Text(data.place, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        frames.getOrNull(index)?.let { "Radar ${time(it.time, use24h)}" } ?: if (loading) "Loading radar…" else null,
                        if (satellite) "Satellite" else "Map",
                        Radar.zoomLabel(zoom).lowercase(),
                        if (frames.isEmpty()) null else if (playing) "Looping" else "Paused",
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), maxLines = 1,
                )
            }

            // Reflectivity key: the same NEXRAD ramp the tiles use, so colour always means dBZ.
            Column(Modifier.align(Alignment.TopEnd).padding(horizontal = 24.dp, vertical = 22.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            Color(0xFF57C77A), Color(0xFF2E9E5B), Color(0xFFF2D34A),
                            Color(0xFFF29A3D), Color(0xFFE0483E), Color(0xFFB43ACB),
                        ).forEach { c -> Box(Modifier.size(width = 17.dp, height = 6.dp).background(c)) }
                        Text(
                            "  dBZ 5–65",
                            style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
                        )
                    }
                    if (!satellite) {
                        Text(
                            "© Esri · RainViewer",
                            style = MaterialTheme.typography.labelSmall, color = Edge.Faint.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            if (frames.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = 28.dp, end = 28.dp, bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    frames.forEachIndexed { i, f ->
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier.fillMaxWidth().height(if (i == index) 5.dp else 3.dp).clip(RoundedCornerShape(50))
                                    .background(
                                        when {
                                            i == index -> Edge.Accent
                                            i < index -> Edge.Text.copy(alpha = 0.45f)
                                            else -> Edge.Text.copy(alpha = 0.18f)
                                        },
                                    ),
                            )
                            if (i == 0 || i == frames.lastIndex) {
                                Text(time(f.time, use24h), style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) CircularProgressIndicator(Modifier.size(38.dp), color = Edge.Accent, strokeWidth = 3.dp)
                Text(
                    if (loading) "Loading satellite…" else "Radar unavailable",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Real Esri satellite imagery and RainViewer frames.",
                    style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RadarHud(
    detail: PublicApis.WeatherDetail?,
    simple: PublicApis.Weather?,
    analysis: Radar.Analysis?,
    frameTime: Long?,
    rangeKm: Int,
    warnings: List<Trackers.Warning>,
    use24h: Boolean,
    fahrenheit: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "RIGHT NOW", style = MaterialTheme.typography.labelSmall,
            color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), modifier = Modifier.padding(bottom = 6.dp),
        )
        if (detail == null) {
            Column(Modifier.padding(top = 8.dp)) {
                if (simple != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WeatherGlyph(simple.code, glyphSize = 34.dp, night = !simple.isDay)
                        Text(
                            temp(simple.tempC, fahrenheit),
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = 10.dp),
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(simple.description, style = MaterialTheme.typography.titleMedium)
                            Text(simple.city, style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
                        }
                    }
                    Text("Detailed station data unavailable — retrying.", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text("Loading station data…", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim)
                }
            }
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                WeatherGlyph(detail.code, glyphSize = 34.dp, night = !detail.isDay)
                Text(
                    temp(detail.tempC, fahrenheit),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 10.dp),
                )
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(detail.description, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Feels ${temp(detail.feelsC, fahrenheit)}",
                        style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "H ${temp(detail.highC, fahrenheit)}  ·  L ${temp(detail.lowC, fahrenheit)}  ·  Rain ${detail.rainChance}% (${"%.1f".format(detail.rainSumMm)} mm)",
                style = MaterialTheme.typography.labelMedium, color = Edge.Dim, modifier = Modifier.padding(top = 4.dp),
            )
            if (warnings.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("ALERT", color = Edge.Alert)
                    Text(
                        "  ${warnings.first().title}", style = MaterialTheme.typography.labelMedium,
                        color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            run {
                val lightning = Trackers.lightning(detail)
                val nearestStormKm = analysis?.cells?.minOfOrNull { it.km } ?: analysis?.nearestKm
                val hail = Storm.hailRiskFor(
                    lat = detail.lat,
                    lon = detail.lon,
                    maxDbz = analysis?.maxDbz,
                    cape = detail.capeJkg.takeIf { !it.isNaN() },
                    lightningLevel = lightning.level,
                    nearestStormKm = nearestStormKm,
                )
                Text(
                    "RISK", style = MaterialTheme.typography.labelSmall,
                    color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), modifier = Modifier.padding(top = 10.dp),
                )
                RiskRow("Storms", detail.stormRisk)
                RiskRow("Lightning", lightning.level)
                RiskRow("Hail", hail.levelLabel.lowercase())
                Text(
                    hail.reason,
                    style = MaterialTheme.typography.labelSmall, color = Edge.Dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "BOM ${Storm.bomProductFor(detail.lat, detail.lon).id} · derived lightning, not measured strikes",
                    style = MaterialTheme.typography.labelSmall, color = Edge.Faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "RADAR", style = MaterialTheme.typography.labelSmall,
            color = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA), modifier = Modifier.padding(bottom = 4.dp),
        )
        if (analysis == null) {
            Text("Waiting for the first radar frame…", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(top = 6.dp))
        } else if (analysis.nearestKm == null) {
            Text("No echoes within $rangeKm km.", style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(
                "Nearest rain  ${analysis.nearestKm} km ${analysis.bearing}",
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Coverage ${if (analysis.coveragePct == 0) "<1%" else "${analysis.coveragePct}%"} within $rangeKm km  ·  max ${analysis.maxDbz ?: "—"} dBZ",
                style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
            )
            analysis.motionKph?.let {
                Text(
                    "Moving ${it} km/h ${analysis.motionBearing}",
                    style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
                )
            }
            if (analysis.cells.isNotEmpty()) {
                Text(
                    "${analysis.cells.size} storm cell(s) · strongest ${analysis.cells.first().maxDbz} dBZ",
                    style = MaterialTheme.typography.labelMedium, color = Edge.Text,
                )
            }
        }
        frameTime?.let {
            Text("Frame ${time(it, use24h)}  ·  ${frameMinutes(it)} min ago", style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(4.dp))
    }
}

/** Real focusable controls: zoom, frames and details no longer depend on Menu/Info keys. */
@Composable
private fun RadarControls(
    mapFocus: FocusRequester,
    firstFocus: FocusRequester,
    playing: Boolean,
    hasFrames: Boolean,
    zoom: Int,
    onTogglePlay: () -> Unit,
    onStep: (Int) -> Unit,
    onZoom: (Int) -> Unit,
    onLatest: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.focusProperties { up = mapFocus },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EdgeButton(
            if (playing) "Pause" else "Play", { if (hasFrames) onTogglePlay() },
            Modifier.focusRequester(firstFocus), primary = true,
        )
        EdgeButton("Earlier", { if (hasFrames) onStep(-1) })
        EdgeButton("Later", { if (hasFrames) onStep(1) })
        EdgeButton("Zoom −", { if (zoom > 7) onZoom(zoom - 1) })
        EdgeButton("Zoom +", { if (zoom < 10) onZoom(zoom + 1) })
        EdgeButton("Latest", { if (hasFrames) onLatest() })
        EdgeButton("Details", onDetails)
    }
}

@Composable
private fun Section(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Edge.Line))
    }
}

/** One risk line; only a severe level is coloured, everything else stays plain text. */
@Composable
private fun RiskRow(label: String, level: String) {
    val severe = level.equals("Severe", true) || level.equals("Extreme", true)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, modifier = Modifier.weight(1f))
        Text(level.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (severe) Edge.Alert else Edge.Text)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun RadarDetailsDialog(
    detail: PublicApis.WeatherDetail?,
    nearby: List<PublicApis.NearbyPlace>,
    warnings: List<Trackers.Warning>,
    cyclones: List<Trackers.Cyclone>,
    analysis: Radar.Analysis?,
    frameTime: Long?,
    rangeKm: Int,
    use24h: Boolean,
    fahrenheit: Boolean,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    val lightning = Trackers.lightning(detail)
    val tornadoes = warnings.filter {
        it.title.contains("tornado", true) || it.detail.contains("tornado", true)
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Edge.Bg.copy(alpha = 0.985f))
                .padding(start = 30.dp, top = 24.dp, end = 30.dp, bottom = 22.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> { scope.launch { scroll.scrollBy(-110f) }; true }
                        Key.DirectionDown -> { scope.launch { scroll.scrollBy(110f) }; true }
                        else -> false
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail?.city ?: "Location", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    detail?.observedAt?.substringAfter('T', "")?.take(5)?.let { "Observed $it" } ?: "",
                    style = MaterialTheme.typography.labelSmall, color = Edge.Faint, modifier = Modifier.padding(end = 12.dp),
                )
                EdgeButton("Close", onClose, Modifier.focusRequester(focus))
            }
            Row(Modifier.weight(1f).padding(top = 4.dp).verticalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Column(Modifier.weight(1f)) {
                    Section("ATMOSPHERE")
                    DetailRow("Humidity", detail?.let { "${it.humidity}%" } ?: "—")
                    DetailRow("Dew point", detail?.let { temp(it.dewC, fahrenheit) } ?: "—")
                    DetailRow("Wet bulb", detail?.let { temp(it.wetBulbC, fahrenheit) } ?: "—")
                    DetailRow("Pressure", detail?.let { "${it.pressureHpa.roundToInt()} hPa" } ?: "—")
                    DetailRow("Surface", detail?.let { "${it.surfacePressureHpa.roundToInt()} hPa" } ?: "—")
                    DetailRow("Visibility", detail?.let { "%.1f km".format(it.visibilityKm) } ?: "—")
                    DetailRow("Cloud", detail?.let { "${it.cloudPct}%" } ?: "—")
                    DetailRow("UV index", detail?.let { "%.1f".format(it.uv) } ?: "—")
                    DetailRow("Rain rate", detail?.let { "%.1f mm/h".format(it.rainRateMmH) } ?: "—")
                    DetailRow("Wind", detail?.let { "${it.windKph.roundToInt()} km/h ${compass(it.windDir)}" } ?: "—")
                    DetailRow("Gusts", detail?.let { "${it.gustKph.roundToInt()} km/h" } ?: "—")
                    DetailRow("Sunshine", detail?.let { "${it.sunshineMin.roundToInt()} min/15m" } ?: "—")

                    Section("STORM POTENTIAL")
                    DetailRow("CAPE", detail?.let { "${it.capeJkg.roundToInt()} J/kg" } ?: "—")
                    DetailRow("Lifted index", detail?.let { "%.1f".format(it.liftedIndex) } ?: "—")
                    DetailRow("Risk", detail?.stormRisk ?: "Unknown")
                    DetailRow("Freezing level", detail?.let { "${it.freezingLevelM.roundToInt()} m" } ?: "—")
                    DetailRow("Boundary layer", detail?.let { "${it.boundaryLayerM.roundToInt()} m" } ?: "—")
                    DetailRow("VPD", detail?.let { "%.2f kPa".format(it.vpdKpa) } ?: "—")

                    Section("NEXT HOURS")
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        detail?.hourly.orEmpty().take(6).forEach { h ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(clockOf(h.time), style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
                                WeatherGlyph(h.code, glyphSize = 15.dp)
                                Text(temp(h.tempC, fahrenheit), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                Text("${h.precipChance}%", style = MaterialTheme.typography.labelSmall, color = Edge.Accent2)
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Section("ALERTS · ${warnings.size}")
                    if (warnings.isEmpty()) {
                        Text("No active official warnings, or no feed for this region.", style = MaterialTheme.typography.labelSmall, color = Edge.Dim)
                    } else {
                        warnings.take(4).forEach { w ->
                            Text(w.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${w.severity} · ${w.source}", style = MaterialTheme.typography.labelSmall, color = Edge.Faint)
                        }
                    }

                    Section("TRACKERS")
                    if (tornadoes.isNotEmpty()) {
                        tornadoes.take(2).forEach { Text("TORNADO · ${it.title}", style = MaterialTheme.typography.labelMedium, color = Edge.Alert, maxLines = 2) }
                    } else {
                        DetailRow("Tornado", "No warning · risk ${detail?.stormRisk ?: "Unknown"}")
                    }
                    DetailRow("Lightning risk", "${lightning.level} · CAPE ${lightning.cape.roundToInt()} J/kg · LI ${"%.1f".format(lightning.liftedIndex)}")
                    if (cyclones.isEmpty()) {
                        DetailRow("Cyclones", "None within 3,000 km")
                    } else {
                        cyclones.forEach { c ->
                            DetailRow(c.name, "${c.alert} · ${c.km} km ${c.bearing} · ${c.windKph} km/h")
                        }
                    }

                    Section("STORM CELLS")
                    val cells = analysis?.cells.orEmpty()
                    if (cells.isEmpty()) {
                        Text("No discrete rain cells within $rangeKm km.", style = MaterialTheme.typography.labelSmall, color = Edge.Dim)
                    } else {
                        cells.forEach { c ->
                            DetailRow("C${c.id}", "${c.maxDbz} dBZ · ${c.km} km ${c.bearing} · ${c.areaKm2} km²")
                        }
                    }

                    Section("RADAR")
                    DetailRow("Range", "$rangeKm km")
                    DetailRow("Nearest rain", analysis?.nearestKm?.let { "${it} km ${analysis.bearing}" } ?: "None within range")
                    DetailRow("Coverage", analysis?.let { if (it.nearestKm != null && it.coveragePct == 0) "<1%" else "${it.coveragePct}%" } ?: "—")
                    DetailRow("Max intensity", analysis?.maxDbz?.let { "$it dBZ" } ?: "—")
                    DetailRow("Motion", analysis?.motionKph?.let { "$it km/h ${analysis.motionBearing}" } ?: "—")
                    DetailRow("Frame", frameTime?.let { time(it, use24h) } ?: "—")
                }
                Column(Modifier.weight(1f)) {
                    Section("NEARBY · ${nearby.size}")
                    if (nearby.isEmpty()) {
                        Text("No nearby places found.", style = MaterialTheme.typography.labelSmall, color = Edge.Dim)
                    } else {
                        nearby.take(10).forEach { p -> DetailRow(p.name, "${p.km} km ${p.bearing}") }
                    }

                    Section("SUN & LOCATION")
                    DetailRow("Sunrise", clockOf(detail?.sunrise))
                    DetailRow("Sunset", clockOf(detail?.sunset))
                    DetailRow("Elevation", detail?.elevation?.let { "${it.roundToInt()} m" } ?: "—")
                    DetailRow("Coordinates", detail?.let { "%.3f, %.3f".format(it.lat, it.lon) } ?: "—")
                    DetailRow("Observed", detail?.observedAt?.replace('T', ' ') ?: "—")
                    DetailRow("Source", "Open-Meteo · Esri · RainViewer")
                }
            }
        }
    }
}

private fun compass(deg: Int): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[((deg % 360 + 360) % 360 / 45).coerceIn(0, 7)]
}

private fun temp(v: Double, fahrenheit: Boolean): String =
    if (v.isNaN()) "—" else if (fahrenheit) "${(v * 9 / 5 + 32).roundToInt()}°F" else "${v.roundToInt()}°C"

private fun clockOf(iso: String?): String =
    iso?.substringAfter('T', "")?.take(5)?.takeIf { it.length == 5 } ?: "—"

private fun time(epochSeconds: Long, use24h: Boolean): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))

private fun frameMinutes(epochSeconds: Long): Int =
    ((System.currentTimeMillis() / 1000 - epochSeconds) / 60).toInt().coerceAtLeast(0)
