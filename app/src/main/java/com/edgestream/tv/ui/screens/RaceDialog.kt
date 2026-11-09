package com.edgestream.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.edgestream.tv.data.Racing
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.Format
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.Pill
import com.edgestream.tv.ui.glass
import java.util.Locale
import kotlinx.coroutines.delay

/** Race card shared by the Sports hub and the Odds board. */
@Composable
internal fun RaceDialog(
    race: Racing.Race,
    channel: ChannelEntity?,
    use24h: Boolean,
    onWatch: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val sorted = remember(race.id) { race.runners.sortedBy { it.number ?: 999 } }
    LaunchedEffect(Unit) { delay(180); runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth(0.92f).widthIn(max = 780.dp).glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.96f)).padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(race.category.uppercase(), color = Edge.Accent)
                Spacer(Modifier.width(10.dp))
                Text("${race.venue} R${race.number}", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(10.dp))
                Text(Format.time(race.startMs, use24h), style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
            }
            Text(
                "${race.booksQuoting} books · ${race.runners.size} runners · D-pad to scroll",
                style = MaterialTheme.typography.labelMedium,
                color = Edge.Faint,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (channel != null && onWatch != null) {
                Spacer(Modifier.height(14.dp))
                EdgeButton(
                    "Watch ${channel.name}",
                    onWatch,
                    Modifier.fillMaxWidth().focusRequester(focus),
                    primary = true,
                    icon = Icons.Rounded.PlayArrow,
                )
            } else {
                Text(
                    "No Sky Racing channel in your lineup",
                    style = MaterialTheme.typography.labelMedium,
                    color = Edge.Faint,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 14.dp)) {
                itemsIndexed(sorted, key = { _, runner -> "${runner.number}-${runner.name}" }) { index, runner ->
                    RunnerRow(runner, if (index == 0 && channel == null) focus else null)
                }
            }
            EdgeButton("Close", onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RunnerRow(runner: Racing.Runner, firstFocus: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Edge.PanelHi else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${runner.number ?: "-"}",
            style = MaterialTheme.typography.labelSmall,
            color = Edge.Faint,
            modifier = Modifier.width(38.dp),
        )
        Text(runner.name, style = MaterialTheme.typography.bodyLarge, color = Edge.Text, modifier = Modifier.weight(1f))
        Text(
            runner.best?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Edge.Text,
        )
    }
}

/** Horse → Sky Racing 1, greyhounds → 2, harness → 3; the EPG wins when it names the venue. */
internal fun raceChannel(
    race: Racing.Race,
    channels: List<ChannelEntity>,
    nowNext: Map<String, Pair<ProgrammeEntity?, ProgrammeEntity?>>,
): ChannelEntity? {
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val candidates = channels.filter {
        val n = norm(it.name)
        n.contains("skyracing") || n.contains("skysportsracing")
    }
    val pool = candidates.filter {
        it.name.startsWith("AU", true) || it.groupName.contains("AU", true) ||
            it.groupName.contains("Australia", true)
    }
    if (pool.isEmpty()) return null
    val venue = race.venue.lowercase()
    pool.firstOrNull { channel ->
        channel.epgId?.let { nowNext[it]?.first?.title?.lowercase()?.contains(venue) } == true
    }?.let { return it }
    fun pick(n: Int) = pool.firstOrNull { norm(it.name).contains("skyracing$n") }
    return when (race.category.lowercase()) {
        "horse" -> pick(1) ?: pick(2) ?: pick(3)
        "greyhound" -> pick(2) ?: pick(1) ?: pick(3)
        "harness" -> pick(3) ?: pick(2) ?: pick(1)
        else -> pick(1) ?: pick(2) ?: pick(3)
    }
}
