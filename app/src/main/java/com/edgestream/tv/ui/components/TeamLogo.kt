package com.edgestream.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.edgestream.tv.ui.Edge

/** One team-logo treatment: real artwork when ESPN supplies it, a consistent initial otherwise. */
@Composable
fun TeamLogo(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) {
            AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Edge.PanelHi),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.trim().take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (size.value * 0.42f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Edge.Dim,
                )
            }
        }
    }
}
