package com.edgestream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.Pill
import kotlinx.coroutines.delay

@Composable
fun PhoneRemoteDialog(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val running by com.edgestream.tv.remote.PhoneLink.running.collectAsStateWithLifecycle()
    val lastSeen by com.edgestream.tv.remote.PhoneLink.lastSeen.collectAsStateWithLifecycle()
    var nonce by remember { mutableStateOf(0) }
    val url = remember(running, nonce) { com.edgestream.tv.remote.PhoneLink.url(context) }
    val code = remember(nonce) { com.edgestream.tv.remote.PhoneLink.token(context) }
    // Re-checked every few seconds so the pill drops back once the phone goes quiet.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { delay(5_000); value = System.currentTimeMillis() }
    }
    val connected = now - lastSeen < 120_000
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(12) { delay(40); runCatching { close.requestFocus() } } }

    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(700.dp)
                    .glass(EdgeShapes.Panel, Edge.Solid.copy(alpha = 0.985f))
                    .padding(26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remote", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Your phone becomes the remote. No app to install.",
                            style = MaterialTheme.typography.bodyMedium, color = Edge.Dim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    FocusSurface(
                        onClick = onClose,
                        modifier = Modifier.focusRequester(close).size(36.dp),
                        shape = RoundedCornerShape(50),
                        color = Edge.Panel,
                        focusedColor = Edge.PanelHi,
                        focusScale = 1.08f,
                        glow = false,
                    ) { focused ->
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.Close, "Close", Modifier.align(Alignment.Center).size(18.dp),
                            tint = if (focused) Edge.Accent else Edge.Dim,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Box(
                        Modifier.size(244.dp).clip(RoundedCornerShape(22.dp)).background(Color.White)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (url != null && running) {
                            com.edgestream.tv.ui.components.QrCode(url, Modifier.fillMaxSize())
                        } else {
                            Text(
                                if (running) "Connecting…" else "Start the companion server in Settings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF303030),
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Pill(if (connected) "PHONE CONNECTED" else "READY TO PAIR", color = if (connected) Edge.Accent else Edge.Accent2)
                        Text(
                            "Same Wi-Fi",
                            style = MaterialTheme.typography.titleMedium, color = Edge.Text,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "Scan the code. Your phone opens a private remote with a channel list, search and a keyboard.",
                            style = MaterialTheme.typography.bodyMedium, color = Edge.Dim,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (url != null) {
                            Text(
                                url.substringBefore("/#"),
                                style = MaterialTheme.typography.titleMedium, color = Edge.Accent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                        Text(
                            "Pairing code",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = Edge.Faint,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            code,
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                                fontFeatureSettings = "tnum",
                            ),
                            color = Edge.Text,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(14.dp))
                        EdgeButton(
                            "New Pairing Code",
                            {
                                com.edgestream.tv.remote.PhoneLink.resetToken(context)
                                nonce++
                            },
                            icon = androidx.compose.material.icons.Icons.Rounded.Refresh,
                            fill = true,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PairStep("1", "Scan the code")
                    PairStep("2", "Stay on the same Wi-Fi")
                    PairStep("3", "Control the TV")
                }
            }
        }
    }
}

@Composable
private fun PairStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).background(Edge.Accent.copy(alpha = 0.16f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = Edge.Accent)
        }
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = Edge.Dim,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Shown while any text box has focus: scan to type on the phone instead of the remote. Once a
 * phone is connected the code is not needed, so only a one-line note stays.
 */
@Composable
fun PhoneTypeHint(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val running by com.edgestream.tv.remote.PhoneLink.running.collectAsStateWithLifecycle()
    val lastSeen by com.edgestream.tv.remote.PhoneLink.lastSeen.collectAsStateWithLifecycle()
    val url = remember(running) { com.edgestream.tv.remote.PhoneLink.url(context) }
    if (!running || url == null) return
    val connected = System.currentTimeMillis() - lastSeen < 120_000
    Row(
        modifier.padding(top = 8.dp).background(Edge.Solid.copy(alpha = 0.94f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!connected) {
            com.edgestream.tv.ui.components.QrCode(url, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(14.dp))
        } else {
            Icon(androidx.compose.material.icons.Icons.Rounded.PhoneAndroid, null, Modifier.size(22.dp), tint = Edge.Accent)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.width(190.dp)) {
            Text("Type on your phone", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                if (connected) "Your phone shows a keyboard for this box." else "Scan, then type. It fills this box.",
                style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 2,
            )
        }
    }
}
