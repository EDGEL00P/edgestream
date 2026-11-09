package com.edgestream.tv.ui

import android.graphics.RenderEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A modern ten-foot colour system: near-black neutral surfaces, one saturated accent per preset,
 * and white-first text. Depth comes from artwork, scrims and a
 * soft accent glow on focus rather than from the palette itself.
 */
data class Palette(
    val id: String,
    val name: String,
    val bg: Color,
    val panel: Color,
    val panelHi: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,
    /** Text/icons drawn on top of [accent]. */
    val onAccent: Color,
    /** Secondary marker colour (catch-up, info). */
    val secondary: Color,
    val live: Color,
    val gold: Color,
)

object Palettes {
    /**
     * Broadcast collection. Accent/secondary values come from the supplied brand hexes;
     * surfaces are derived dark tints. Colours only — no broadcaster marks or logos.
     */
    val Skyline = Palette(
        "skyline", "Sky UK",
        bg = Color(0xFF020816), panel = Color(0xFF05132B), panelHi = Color(0xFF0A2146), line = Color(0xFF153666),
        text = Color(0xFFF1F6FF), dim = Color(0xFF9EB3D4), faint = Color(0xFF5A7194),
        accent = Color(0xFF4C86FF), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFFDF1A19),
        live = Color(0xFFDF1A19), gold = Color(0xFFF2C66D),
    )

    val Primetime = Palette(
        "primetime", "ESPN",
        bg = Color(0xFF050505), panel = Color(0xFF101010), panelHi = Color(0xFF1A1A1A), line = Color(0xFF2C2C2C),
        text = Color(0xFFFFFFFF), dim = Color(0xFFB8B8B8), faint = Color(0xFF6E6E6E),
        accent = Color(0xFFE52534), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFFFF6B75),
        live = Color(0xFFE52534), gold = Color(0xFFD6B25E),
    )

    val FoxAu = Palette(
        "foxau", "Fox AU",
        bg = Color(0xFF030A12), panel = Color(0xFF071521), panelHi = Color(0xFF0C2233), line = Color(0xFF153348),
        text = Color(0xFFF3F7FB), dim = Color(0xFF9BB0C2), faint = Color(0xFF5A7186),
        accent = Color(0xFFAC0033), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFFE0BB7F),
        live = Color(0xFFFF3B4E), gold = Color(0xFFE0BB7F),
    )

    val Kayo = Palette(
        "kayo", "Kayo",
        bg = Color(0xFF050A0D), panel = Color(0xFF0C161C), panelHi = Color(0xFF132229), line = Color(0xFF1F333D),
        text = Color(0xFFF1FBF7), dim = Color(0xFF9DB5AE), faint = Color(0xFF5E776F),
        accent = Color(0xFF02BF6F), onAccent = Color(0xFF00120A), secondary = Color(0xFF79BCF2),
        live = Color(0xFFFF4D6A), gold = Color(0xFFFFC857),
    )

    val Nine = Palette(
        "nine", "Nine",
        bg = Color(0xFF02060F), panel = Color(0xFF061224), panelHi = Color(0xFF0C2038), line = Color(0xFF173452),
        text = Color(0xFFF4F7FC), dim = Color(0xFF9FB2CA), faint = Color(0xFF5C708A),
        accent = Color(0xFFD42027), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFF1661A9),
        live = Color(0xFFFF3B4E), gold = Color(0xFFE8C46A),
    )

    val Stan = Palette(
        "stan", "Stan Sport",
        bg = Color(0xFF06080C), panel = Color(0xFF161616), panelHi = Color(0xFF262626), line = Color(0xFF3A3A3A),
        text = Color(0xFFF5F8FF), dim = Color(0xFFA8B0BD), faint = Color(0xFF646B76),
        accent = Color(0xFF0073FA), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFF4DA3FF),
        live = Color(0xFFFF3B4E), gold = Color(0xFFFFC857),
    )

    val SkyNz = Palette(
        "skynz", "Sky NZ",
        bg = Color(0xFF020816), panel = Color(0xFF05132B), panelHi = Color(0xFF0A2146), line = Color(0xFF153666),
        text = Color(0xFFF1F6FF), dim = Color(0xFF9EB3D4), faint = Color(0xFF5A7194),
        accent = Color(0xFFDF1A19), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFF4C86FF),
        live = Color(0xFFDF1A19), gold = Color(0xFFF2C66D),
    )

    val BeIn = Palette(
        "bein", "beIN",
        bg = Color(0xFF0A0612), panel = Color(0xFF160E24), panelHi = Color(0xFF24163A), line = Color(0xFF3A235C),
        text = Color(0xFFF8F4FF), dim = Color(0xFFB8A6D4), faint = Color(0xFF75638F),
        accent = Color(0xFF9B5CFF), onAccent = Color(0xFF140A24), secondary = Color(0xFFC9A7FF),
        live = Color(0xFFFF4D6A), gold = Color(0xFFE8C46A),
    )

    val Eurosport = Palette(
        "eurosport", "Eurosport",
        bg = Color(0xFF020617), panel = Color(0xFF081130), panelHi = Color(0xFF101D4A), line = Color(0xFF1C2E6B),
        text = Color(0xFFF0F3FF), dim = Color(0xFF9FAAD4), faint = Color(0xFF5C6894),
        accent = Color(0xFF4C64FF), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFF7D8CFF),
        live = Color(0xFFFF4D6A), gold = Color(0xFFF2C66D),
    )

    val Dazn = Palette(
        "dazn", "DAZN",
        bg = Color(0xFF04090C), panel = Color(0xFF0C161C), panelHi = Color(0xFF14242C), line = Color(0xFF213842),
        text = Color(0xFFF4FAF7), dim = Color(0xFFA2B8B4), faint = Color(0xFF617C7A),
        accent = Color(0xFFE8FF3B), onAccent = Color(0xFF0A1200), secondary = Color(0xFF6EE7FF),
        live = Color(0xFFFF4D6A), gold = Color(0xFFE8FF3B),
    )

    val SuperSport = Palette(
        "supersport", "SuperSport",
        bg = Color(0xFF0D0506), panel = Color(0xFF18090B), panelHi = Color(0xFF261013), line = Color(0xFF3A1A1E),
        text = Color(0xFFFFF5F3), dim = Color(0xFFC2A4A3), faint = Color(0xFF7C6160),
        accent = Color(0xFFE71E20), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFFFFBB38),
        live = Color(0xFFE71E20), gold = Color(0xFFFFBB38),
    )

    val Star = Palette(
        "star", "Star",
        bg = Color(0xFF05060A), panel = Color(0xFF0B0D14), panelHi = Color(0xFF141926), line = Color(0xFF232A3A),
        text = Color(0xFFF5F7FF), dim = Color(0xFFA3AAC2), faint = Color(0xFF646C85),
        accent = Color(0xFF0570F0), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFFFF207C),
        live = Color(0xFFFF207C), gold = Color(0xFFFFDC35),
    )

    val Tsn = Palette(
        "tsn", "TSN",
        bg = Color(0xFF0A0404), panel = Color(0xFF150808), panelHi = Color(0xFF220D0E), line = Color(0xFF351719),
        text = Color(0xFFFFF5F4), dim = Color(0xFFC2A2A1), faint = Color(0xFF7C5F5E),
        accent = Color(0xFFD60000), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFFFF6B6B),
        live = Color(0xFFD60000), gold = Color(0xFFFFC94D),
    )

    val Sportsnet = Palette(
        "sportsnet", "Sportsnet",
        bg = Color(0xFF06101E), panel = Color(0xFF0B223E), panelHi = Color(0xFF123253), line = Color(0xFF1C446B),
        text = Color(0xFFF2F8FF), dim = Color(0xFFA1B8CF), faint = Color(0xFF5F7892),
        accent = Color(0xFFC8102E), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFF5BC2E7),
        live = Color(0xFFC8102E), gold = Color(0xFFF2C66D),
    )

    val FoxUs = Palette(
        "foxus", "Fox US",
        bg = Color(0xFF030A18), panel = Color(0xFF071528), panelHi = Color(0xFF0C2440), line = Color(0xFF17365A),
        text = Color(0xFFF3F7FC), dim = Color(0xFF9FB2C9), faint = Color(0xFF5C708A),
        accent = Color(0xFF2F6FB5), onAccent = Color(0xFFFFFFFF), secondary = Color(0xFF8BA0B2),
        live = Color(0xFFFF3B4E), gold = Color(0xFFE8C46A),
    )

    val all = listOf(
        FoxAu, Kayo, Nine, Stan, SkyNz,
        Primetime, Skyline, BeIn, Eurosport, Dazn,
        SuperSport, Star, Tsn, Sportsnet, FoxUs,
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: Primetime
}

/**
 * Live colour tokens. Reading them in a composable recomposes it when the theme changes.
 *
 * Surfaces are frosted glass: translucent tints over [AmbientBackground], each edged with a
 * light-catching stroke. Backdrop blur needs API 31 and the SHIELD is on 30, so the frost comes
 * from layered translucency rather than RenderEffect.
 */
object Edge {
    var palette by mutableStateOf(Palettes.Primetime)
    /** The theme's own backdrop: near-black tint of the broadcaster colour. */
    val Bg get() = palette.bg
    /** Resting glass: the theme's own panel colour, 85% translucent. */
    val Panel get() = palette.panel.copy(alpha = 0.15f)
    /** Raised glass: focused, selected or "now" surfaces. */
    val PanelHi get() = palette.panelHi.copy(alpha = 0.22f)
    /** Opaque surface for the rare place nothing may show through. */
    val Solid get() = palette.panel.copy(alpha = 0.97f)
    val Line get() = palette.line
    val Text get() = palette.text
    val Dim get() = palette.dim
    val Faint get() = palette.faint
    val Accent get() = palette.accent
    val OnAccent get() = palette.onAccent
    val Accent2 get() = palette.secondary
    val Live get() = palette.accent

    /** Errors, severe weather and warnings — the one colour that is not the theme's. */
    val Alert get() = palette.live
    val Gold get() = palette.gold

    /** Chrome that floats over content (toasts, chips): a light theme fill. */
    val Glass get() = palette.panel.copy(alpha = 0.15f)
    /** Panels drawn over video: near-opaque smoke so the picture never bleeds through text. */
    val Smoke get() = palette.bg.copy(alpha = 0.92f)

    /** Duotone accent for progress, primary actions and selected markers. */
    val AccentGradient: Brush
        get() = Brush.horizontalGradient(listOf(palette.accent, lerp(palette.accent, palette.secondary, 0.55f)))
}

/** Extra inset on top of a screen's own margins, for TVs that crop the picture edges. */
data class SafeArea(val horizontal: Dp = 0.dp, val vertical: Dp = 0.dp)
val LocalSafeArea = staticCompositionLocalOf { SafeArea() }

/**
 * Lets a full-screen picture run past the safe-area inset (plus [extra], e.g. a section's own
 * padding) to the screen's edges, so art behind inset content never shows a box edge.
 */
fun Modifier.bleed(safe: SafeArea, extra: Dp = 0.dp) = layout { measurable, constraints ->
    val x = (safe.horizontal + extra).roundToPx()
    val y = (safe.vertical + extra).roundToPx()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(constraints.maxWidth + 2 * x, constraints.maxHeight + 2 * y))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-x, -y) }
}

/**
 * Corner radii shared across the app: crisp, instrument-like corners. Posters 10, cards 12,
 * panels 16, chips fully round. Hero stays 0 for full-bleed cinema.
 */
object EdgeShapes {
    val Badge = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    val Poster = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val Card = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    /** Full-bleed hero: no box, no radius. Windows that need a box use [Card] or [Panel]. */
    val Hero = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
    val Panel = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val Chip = androidx.compose.foundation.shape.RoundedCornerShape(50)
}

/** Single spacing and layout rhythm — generous, airy, 4dp-grid. */
object EdgeLayout {
    /** Outer safe margin from the screen edge to the navigation/content chrome. */
    val PageEdge = 48.dp
    /** Additional inset used inside a page so content aligns with the nav bar text. */
    val ContentInset = 20.dp
    /** Standard first row below the global navigation/header. */
    val PageTop = 36.dp
    /** Bottom breathing room for a page's final row or control group. */
    val PageBottom = 44.dp
    val FieldHeight = 62.dp
}

/**
 * Frosted glass: the panel shows a heavily blurred, colour-boosted slice of the live background
 * behind it ([com.edgestream.tv.ui.glass.LiveBlur]), darkened a touch for legible text, tinted with
 * [fill], with a faint lit top edge. Without a blurred background (dialogs, full-screen video,
 * low-tier devices) it is the plain [fill].
 */
fun Modifier.glass(shape: Shape, fill: Color = Edge.Panel): Modifier = composed {
    val view = androidx.compose.ui.platform.LocalView.current
    val origin = remember { floatArrayOf(0f, 0f) }
    val inMainWindow = view.rootView === com.edgestream.tv.ui.components.FocusBeam.hostRoot
    this.clip(shape)
        .onGloballyPositioned { c -> c.positionInRoot().let { origin[0] = it.x; origin[1] = it.y } }
        .drawBehind {
            val frame = com.edgestream.tv.ui.glass.LiveBlur.frame
            val rootW = view.rootView.width.toFloat()
            val rootH = view.rootView.height.toFloat()
            if (frame == null || !inMainWindow || rootW <= 0f || rootH <= 0f) {
                drawRect(fill); return@drawBehind
            }
            // Screen point -> background point: the background may be drawn scaled about the centre.
            val k = com.edgestream.tv.ui.glass.LiveBlur.sourceScale
            fun mapX(x: Float) = (0.5f + (x / rootW - 0.5f) / k) * frame.width
            fun mapY(y: Float) = (0.5f + (y / rootH - 0.5f) / k) * frame.height
            val l = mapX(origin[0]).coerceIn(0f, frame.width - 1f)
            val t = mapY(origin[1]).coerceIn(0f, frame.height - 1f)
            val r = mapX(origin[0] + size.width).coerceIn(l + 1f, frame.width.toFloat())
            val b = mapY(origin[1] + size.height).coerceIn(t + 1f, frame.height.toFloat())
            drawImage(
                frame,
                srcOffset = androidx.compose.ui.unit.IntOffset(l.toInt(), t.toInt()),
                srcSize = androidx.compose.ui.unit.IntSize((r - l).toInt().coerceAtLeast(1), (b - t).toInt().coerceAtLeast(1)),
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                colorFilter = GLASS_SATURATION,
            )
            drawRect(Color.Black.copy(alpha = 0.40f))
            drawRect(fill)
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), endY = size.height * 0.5f))
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent)),
                Offset(0f, 0.75f), Offset(size.width, 0.75f), strokeWidth = 1.5f,
            )
        }
}

/** Glass lifts the colour behind it, like the system blur on newer Android. */
private val GLASS_SATURATION = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(1.4f) },
)

/**
 * The room the glass sits in: the palette's background with two faint, fixed pools of accent
 * light and a vignette pulling the eye to the centre. Static by design: no decorative motion,
 * and nothing redraws it frame to frame on low-end GPUs.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, plain: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    val p = Edge.palette
    Box(
        modifier.drawBehind {
            drawRect(p.bg)
            // Covered by the photo: skip the light pools and vignette nobody would see.
            if (plain) return@drawBehind
            val r = size.maxDimension
            val a = Offset(size.width * 0.78f, size.height * 0.12f)
            val b = Offset(size.width * 0.16f, size.height * 0.9f)
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.10f), Color.Transparent), a, r * 0.62f), r * 0.62f, a)
            // A second, cooler pool keeps each palette from reading as a single flat tint.
            drawCircle(Brush.radialGradient(listOf(p.secondary.copy(alpha = 0.065f), Color.Transparent), b, r * 0.52f), r * 0.52f, b)
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.46f)), center, r * 0.78f))
        },
        content = content,
    )
}

/**
 * One modern type system: Inter everywhere, tuned for ten-foot reading — bold, tight-tracked
 * headlines over the same comfortable body the big streaming apps use.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Inter = androidx.compose.ui.text.font.FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        androidx.compose.ui.text.font.Font(
            com.edgestream.tv.R.font.inter_variable, FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    },
)

/** Condensed scoreboard face for display type: headlines, scores, big numbers. */
private val Display = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(com.edgestream.tv.R.font.bebasneue_regular))

private fun typography(p: Palette) = Typography(
    // Bebas is narrow and caps-only, so display sizes run larger with open tracking.
    displayMedium = TextStyle(fontFamily = Display, fontSize = 76.sp, color = p.text, letterSpacing = 1.0.sp, lineHeight = 76.sp),
    displaySmall = TextStyle(fontFamily = Display, fontSize = 58.sp, color = p.text, letterSpacing = 0.8.sp, lineHeight = 60.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontSize = 40.sp, color = p.text, letterSpacing = 0.6.sp, lineHeight = 44.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.text, letterSpacing = (-0.25).sp, lineHeight = 30.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.text, lineHeight = 21.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = p.text, letterSpacing = (-0.15).sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 18.sp, color = p.text, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = p.dim, lineHeight = 23.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text, letterSpacing = 0.15.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.dim, letterSpacing = 0.25.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.dim, letterSpacing = 0.9.sp, lineHeight = 18.sp),
)

/** Text with no style of its own (and every default) is Inter too. */
private val BaseText = TextStyle(fontFamily = Inter)

@Composable
fun EdgeTheme(content: @Composable () -> Unit) {
    val p = Edge.palette
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.secondary, background = Edge.Bg,
            surface = Edge.Panel, onSurface = p.text, onBackground = p.text,
        ),
        typography = typography(p),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides BaseText.copy(color = p.text),
            content = content,
        )
    }
}
