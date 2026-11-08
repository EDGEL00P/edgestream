package com.edgestream.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.data.Profile
import com.edgestream.tv.data.ProfileCodec
import com.edgestream.tv.data.Profiles
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FocusSurface
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 4

/**
 * Four-digit PIN entry for the remote: a D-pad keypad, and the remote's own number keys work too.
 * [onPin] gets the full PIN and returns whether it was accepted (wrong → cleared, try again).
 */
@Composable
fun PinPad(title: String, onPin: (String) -> Boolean, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    fun type(d: Char) {
        if (pin.length >= PIN_LENGTH) return
        pin += d; wrong = false
        if (pin.length == PIN_LENGTH) { if (!onPin(pin)) { wrong = true; pin = "" } }
    }
    Dialog(onDismissRequest = onCancel) {
        BackHandler(onBack = onCancel)
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        Column(
            Modifier.width(420.dp).background(Edge.Solid, EdgeShapes.Panel).padding(28.dp)
                .onPreviewKeyEvent { e ->
                    val digit = when (e.key) {
                        Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'
                        Key.Three, Key.NumPad3 -> '3'; Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'
                        Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'; Key.Eight, Key.NumPad8 -> '8'
                        Key.Nine, Key.NumPad9 -> '9'; else -> null
                    }
                    if (digit != null && e.type == KeyEventType.KeyDown) { type(digit); true } else digit != null
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Edge.Text)
            Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(Modifier.size(16.dp).background(if (i < pin.length) Edge.Accent else Edge.Text.copy(alpha = 0.18f), CircleShape))
                }
            }
            Text(if (wrong) "Wrong PIN, try again" else " ", style = MaterialTheme.typography.labelLarge, color = Edge.Alert)
            listOf("123", "456", "789", "<0").forEach { row ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        FocusSurface(
                            onClick = { if (c == '<') pin = pin.dropLast(1) else type(c) },
                            modifier = Modifier.size(width = 84.dp, height = 56.dp).then(if (c == '1') Modifier.focusRequester(first) else Modifier),
                            shape = RoundedCornerShape(12.dp), color = Edge.Text.copy(alpha = 0.08f), focusedColor = Edge.Text, focusScale = 1.06f, glow = false,
                        ) { focused ->
                            Text(if (c == '<') "⌫" else "$c", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Edge.Bg else Edge.Text, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(p: Profile, size: Int, ink: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(size.dp).background(Edge.Accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(p.name.take(1).uppercase(), fontSize = (size / 2.4).sp, fontWeight = FontWeight.Bold, color = Edge.OnAccent)
    }
}

/** Shown at launch once there's more than one profile. Back continues as the current profile. */
@Composable
fun WhoIsWatching(onDone: () -> Unit) {
    val profiles by Profiles.all.collectAsStateWithLifecycle()
    val active by Profiles.active.collectAsStateWithLifecycle()
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    BackHandler { if (profiles.firstOrNull { it.id == active }?.locked != true) onDone() }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Edge.Bg.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = Edge.Text)
            Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEachIndexed { i, p ->
                    FocusSurface(
                        onClick = { if (p.locked) unlocking = p else { Profiles.switchTo(p.id); onDone() } },
                        modifier = Modifier.width(170.dp).height(210.dp).then(if (p.id == active || (i == 0 && profiles.none { it.id == active })) Modifier.focusRequester(first) else Modifier),
                        shape = RoundedCornerShape(18.dp), color = Edge.Text.copy(alpha = 0.06f), focusedColor = Edge.Text.copy(alpha = 0.18f), focusScale = 1.08f, glow = false,
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Avatar(p, 96, Edge.OnAccent)
                            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (p.locked) Icon(Icons.Rounded.Lock, "PIN", Modifier.size(16.dp).padding(end = 4.dp), tint = Edge.Dim)
                                Text(p.name, style = MaterialTheme.typography.titleMedium, color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    unlocking?.let { p ->
        PinPad("Enter ${p.name}'s PIN", onPin = { pin ->
            ProfileCodec.pinMatches(p, pin).also { ok -> if (ok) { unlocking = null; Profiles.switchTo(p.id); onDone() } }
        }, onCancel = { unlocking = null })
    }
}

/** Settings › Profiles: switch, add, PIN on/off, remove. */
@Composable
fun ProfilesSettings(vm: AppViewModel) {
    val profiles by Profiles.all.collectAsStateWithLifecycle()
    val active by Profiles.active.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var switching by remember { mutableStateOf<Profile?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Each profile keeps its own watch list, followed teams, remembered channels and calendar. Channels, favourites, continue watching and settings are shared.",
            style = MaterialTheme.typography.bodyMedium, color = Edge.Dim,
        )
        profiles.forEach { p ->
            Row(
                Modifier.fillMaxWidth().background(Edge.Text.copy(alpha = 0.05f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(p, 44, Edge.OnAccent)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, color = Edge.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(if (p.id == active) "Watching now" else null, if (p.locked) "PIN on" else null).joinToString("  ·  ").ifEmpty { " " }, style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
                }
                if (p.id != active) EdgeButton("Switch", { if (p.locked) switching = p else { Profiles.switchTo(p.id); vm.showNotice("Now watching as ${p.name}") } })
                EdgeButton(if (p.locked) "Remove PIN" else "Set PIN", { if (p.locked) { Profiles.setPin(p.id, null); vm.showNotice("PIN removed for ${p.name}") } else pinFor = p })
                if (p.id != ProfileCodec.MAIN) EdgeButton("Remove", { Profiles.remove(p.id); vm.showNotice("Removed ${p.name}") })
            }
        }
        Spacer(Modifier.height(6.dp))
        Field("New profile", newName, "Name", Modifier.fillMaxWidth()) { newName = it }
        EdgeButton("Add Profile", {
            val p = Profiles.add(newName); newName = ""
            vm.showNotice("Added ${p.name}. Pick who's watching from here or at start-up.")
        }, primary = true)
    }
    pinFor?.let { p ->
        PinPad("Choose a 4-digit PIN for ${p.name}", onPin = { pin ->
            Profiles.setPin(p.id, pin); pinFor = null; vm.showNotice("PIN set for ${p.name}"); true
        }, onCancel = { pinFor = null })
    }
    switching?.let { p ->
        PinPad("Enter ${p.name}'s PIN", onPin = { pin ->
            ProfileCodec.pinMatches(p, pin).also { ok -> if (ok) { switching = null; Profiles.switchTo(p.id); vm.showNotice("Now watching as ${p.name}") } }
        }, onCancel = { switching = null })
    }
}
