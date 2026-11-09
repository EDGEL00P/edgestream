package com.edgestream.tv.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgestream.tv.BuildConfigInfo
import com.edgestream.tv.data.DeviceInfo
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.glass.SECONDARY_TEXT_ALPHA
import com.edgestream.tv.ui.input.remoteClickable

private val Dim get() = Edge.Text.copy(alpha = SECONDARY_TEXT_ALPHA)

internal enum class DevicePage(val title: String, val hint: String, val icon: ImageVector) {
    DISPLAY("Display & Sound", "Resolution, HDR and audio output", Icons.Rounded.Tv),
    NETWORK("Network", "Connection and speed", Icons.Rounded.Wifi),
    STORAGE("Storage & Apps", "Space, memory and maintenance", Icons.Rounded.SdStorage),
    ABOUT("About", "Model and system version", Icons.Rounded.Info),
}

/**
 * One device page: real readings, plus a button into the matching Android settings screen —
 * an app cannot change resolution or sound output itself, so it never pretends to.
 */
@Composable
internal fun DevicePageContent(page: DevicePage, storagePage: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val device = remember(page) { runCatching { DeviceInfo.snapshot(ctx) }.getOrNull() }
    fun open(action: String) = runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    if (page == DevicePage.STORAGE) { storagePage(); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(page.title, color = Edge.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp)) }
        val rows: List<Pair<String, String>> = when (page) {
            DevicePage.DISPLAY -> listOf("Output" to (device?.mode ?: "–"), "HDR" to (device?.hdr ?: "–"), "Sound output" to (device?.audio ?: "–"))
            DevicePage.NETWORK -> listOf("Connection" to (device?.network ?: "–"))
            DevicePage.ABOUT -> listOf("Model" to (device?.model ?: "–"), "Android" to (device?.android ?: "–"), "EdgeStream" to BuildConfigInfo.VERSION)
            DevicePage.STORAGE -> emptyList()
        }
        rows.forEach { (k, v) -> item { InfoRow(k, v) } }
        item {
            val (label, action) = when (page) {
                DevicePage.DISPLAY -> "Open display & sound settings" to Settings.ACTION_DISPLAY_SETTINGS
                DevicePage.NETWORK -> "Open network settings" to Settings.ACTION_WIFI_SETTINGS
                else -> "Open Android settings" to Settings.ACTION_SETTINGS
            }
            OpenButton(label) { open(action) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().background(Edge.Text.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Edge.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = Edge.Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun OpenButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.padding(top = 8.dp)
            .background(if (focused) Edge.Accent else Edge.Text.copy(alpha = 0.10f), shape)
            .then(if (focused) Modifier.border(2.dp, Edge.Text, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .remoteClickable(onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = if (focused) Edge.OnAccent else Edge.Text)
        Text(label, color = if (focused) Edge.OnAccent else Edge.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
}
