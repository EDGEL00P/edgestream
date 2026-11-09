package com.edgestream.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes

/**
 * The one card system. Every screen builds from these three shapes, so sizes, corners, badge
 * position, focus ring and spacing never differ between screens:
 *  - [TitleTile]       2:3 poster, 8 dp corners, badge top-left
 *  - [WideTile]        16:9 artwork/genre tile, 12 dp corners, label bottom-left
 *  - [CollectionTile]  16:9 streaming-service tile, the service's wordmark on glass
 */
object CardSize {
    val Poster: Dp = 144.dp
    val Wide: Dp = 312.dp
    val Collection: Dp = 272.dp
    val RowGap: Dp = 18.dp
}

@Composable
fun TitleTile(
    title: String,
    poster: String?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    meta: String? = null,
    dimmed: Boolean = false,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(CardSize.Poster).aspectRatio(2f / 3f)
            .then(if (onFocus != null) Modifier.onFocusGained(onFocus) else Modifier),
        shape = EdgeShapes.Poster, focusScale = 1.1f,
    ) { focused ->
        Box(Modifier.fillMaxSize().alpha(if (dimmed && !focused) 0.45f else 1f)) {
            Logo(poster, title, Modifier.fillMaxSize(), ContentScale.Crop)
            if (poster == null) Text(
                title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp),
            )
            // Netflix-style reveal: the title and its facts rise over the poster on focus.
            if (focused && poster != null) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000)))))
                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!meta.isNullOrBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        badge?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(8.dp), strong = !dimmed) }
    }
}

@Composable
fun WideTile(
    label: String,
    modifier: Modifier = Modifier,
    image: String? = null,
    caption: String? = null,
    tint: Color = Edge.Accent,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(CardSize.Wide).aspectRatio(16f / 9f)
            .then(if (onFocus != null) Modifier.onFocusGained(onFocus) else Modifier),
        shape = EdgeShapes.Card, focusScale = 1.06f,
    ) {
        if (image != null) Logo(image, label, Modifier.fillMaxSize(), ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.12f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            caption?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Brand colours for the services we recognise; the wordmark is set in type, so no logo files ship. */
private val BRANDS = mapOf(
    "Netflix" to Color(0xFFE50914), "Prime Video" to Color(0xFF1FA2FF), "Disney+" to Color(0xFF3E8BFF),
    "Apple TV+" to Color(0xFFF5F5F7), "Max" to Color(0xFF8F5BFF), "Hulu" to Color(0xFF1CE783),
    "Paramount+" to Color(0xFF2E7BFF), "Peacock" to Color(0xFFF2F2F2), "Showtime" to Color(0xFFFF2B2B),
    "Starz" to Color(0xFFF2F2F2), "BritBox" to Color(0xFF4FC3F7), "Crunchyroll" to Color(0xFFFF7A1A),
    "Stan" to Color(0xFF2B8CFF), "Binge" to Color(0xFF00E0A8), "MGM+" to Color(0xFFD4AF37), "AMC+" to Color(0xFFF2F2F2),
)

@Composable
fun CollectionTile(name: String, count: Int?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val brand = BRANDS[name] ?: Edge.Accent
    FocusSurface(
        onClick = onClick, modifier = modifier.width(CardSize.Collection).aspectRatio(16f / 9f),
        shape = EdgeShapes.Card, focusScale = 1.06f,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(brand.copy(alpha = 0.22f), Color.Transparent))))
        Text(
            name, color = brand, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = (-0.5).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
        )
        count?.let { Text("$it", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) }
    }
}

/** Small label in a card's top-left corner. Strong = accent fill; otherwise a dark glass chip. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, strong: Boolean = true) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
        color = if (strong) Edge.OnAccent else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(if (strong) Edge.Accent else Color(0xCC000000), EdgeShapes.Badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun Modifier.onFocusGained(block: () -> Unit) = this.onFocusChanged { if (it.isFocused) block() }
