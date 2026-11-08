package com.edgestream.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.player.PlayerEngine
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.VideoSurface

/**
 * The one picture-in-picture treatment: Home, Live and Guide all use this so the preview
 * chrome (LIVE badge, channel, now programme, progress) never drifts between tabs.
 */
@Composable
fun PreviewFrame(
    engine: PlayerEngine,
    channel: ChannelEntity?,
    now: ProgrammeEntity?,
    clock: Long,
    modifier: Modifier = Modifier,
    showVideo: Boolean = true,
) {
    Box(modifier.clip(EdgeShapes.Card).background(Color.Black)) {
        if (showVideo) VideoSurface(engine, Modifier.fillMaxSize())
        else Logo(channel?.logo, channel?.name.orEmpty(), Modifier.fillMaxSize())

        if (now != null && now.start <= clock && now.stop > clock) {
            Pill("● Live", Modifier.align(Alignment.TopStart).padding(12.dp), Edge.Live)
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    channel?.name.orEmpty(), style = MaterialTheme.typography.labelLarge,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (now != null) Text(
                    now.title, style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            channel?.let {
                Text(
                    "CH ${com.edgestream.tv.data.ChannelNumbers.lcn(it)}", style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f), maxLines = 1,
                )
            }
        }

        if (now != null) {
            ProgressLine(
                Format.fraction(now.start, now.stop, clock),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(), 3.dp,
            )
        }
    }
}
