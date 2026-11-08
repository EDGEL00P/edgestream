package com.edgestream.tv.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edgestream.tv.ui.Edge

/** One weather icon treatment for Home, the ticker and the screen saver. */
@Composable
fun WeatherGlyph(code: Int, modifier: Modifier = Modifier, glyphSize: Dp = 18.dp) {
    Icon(
        weatherIcon(code), contentDescription = null,
        modifier = modifier.size(glyphSize), tint = weatherTint(code),
    )
}

private fun weatherIcon(code: Int): ImageVector = when (code) {
    0 -> Icons.Filled.WbSunny
    1, 2 -> Icons.Filled.WbCloudy
    3, 45, 48 -> Icons.Filled.Cloud
    in 51..67, in 80..82 -> Icons.Filled.WaterDrop
    in 71..77, 85, 86 -> Icons.Filled.AcUnit
    in 95..99 -> Icons.Filled.Thunderstorm
    else -> Icons.Filled.Cloud
}

private fun weatherTint(code: Int): Color = when (code) {
    0 -> Color(0xFFFFC857)
    1, 2 -> Color(0xFFBFD7FF)
    3, 45, 48 -> Color(0xFFB0B6C3)
    in 51..67, in 80..82 -> Color(0xFF6FB6FF)
    in 71..77, 85, 86 -> Color(0xFFE8F4FF)
    in 95..99 -> Color(0xFFB99CFF)
    else -> Edge.Dim
}
