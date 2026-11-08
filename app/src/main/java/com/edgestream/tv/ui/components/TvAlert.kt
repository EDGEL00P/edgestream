package com.edgestream.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edgestream.tv.ui.Edge
import kotlinx.coroutines.delay

/** One choice in a [TvAlert]. A destructive choice is drawn in the alert colour. */
data class AlertAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * The tvOS alert: the screen dims, a title and a short message sit centred on it, and the choices
 * stack below as wide buttons; the focused one turns white. Back closes it (same as Cancel).
 */
@Composable
fun TvAlert(title: String, message: String?, actions: List<AlertAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(12) { delay(40); runCatching { first.requestFocus() } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = Edge.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                message?.let {
                    Text(it, color = Edge.Text.copy(alpha = 0.7f), fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 14.dp))
                }
                actions.forEachIndexed { i, a ->
                    FocusSurface(
                        onClick = { a.onClick() },
                        modifier = Modifier.fillMaxWidth().height(58.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        shape = RoundedCornerShape(12.dp),
                        color = Edge.Text.copy(alpha = 0.12f),
                        focusedColor = Edge.Text,
                        focusScale = 1.04f,
                        glow = false,
                    ) { focused ->
                        Text(
                            a.label,
                            color = when { a.destructive -> Edge.Alert; focused -> Edge.Bg; else -> Edge.Text },
                            fontSize = 19.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}
