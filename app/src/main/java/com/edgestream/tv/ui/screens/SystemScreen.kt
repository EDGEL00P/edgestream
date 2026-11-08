package com.edgestream.tv.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.EmptyState
import com.edgestream.tv.ui.components.FocusSurface
import com.edgestream.tv.ui.components.OneLine
import com.edgestream.tv.ui.components.ProgressLine
import com.edgestream.tv.ui.components.SectionTitle
import com.edgestream.tv.ui.components.loadingPulse
import java.io.File
import kotlinx.coroutines.launch

/**
 * The one-stop-shop maintenance deck: how the box is doing (RAM, storage), one press to free
 * memory, one press to clear EdgeStream's own cache, and per-app storage shortcuts that hand
 * over to the system's own cleaner. Everything a separate killer/cleaner app would do, built
 * into the launcher.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SystemScreen(vm: AppViewModel, embedded: Boolean = false) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<SysStats?>(null) }
    var apps by remember { mutableStateOf<List<Cleanable>>(emptyList()) }
    // Stats walk the disk and query the system: keep both off the composition thread.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            stats = computeStats(context)
            apps = cleanableApps(context)
        }
    }
    val first = remember { FocusRequester() }
    // Retry for a moment: on some platforms the list nodes are not attached yet at first compose.
    LaunchedEffect(Unit) {
        repeat(10) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize()
            .then(if (embedded) Modifier else Modifier.padding(start = 16.dp, top = 28.dp, end = 42.dp, bottom = 36.dp)),
    ) {
        if (!embedded) Text("Device", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        val s = stats
        if (s == null) {
            Text(
                "Reading the box…", style = MaterialTheme.typography.bodyLarge, color = Edge.Dim,
                modifier = Modifier.padding(top = if (embedded) 0.dp else 24.dp).loadingPulse(),
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(top = if (embedded) 4.dp else 16.dp, bottom = 32.dp, end = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "ram") {
                StatCard(Icons.Rounded.Memory, "Memory", s.ramText, s.ramUsed, s.ramTotal)
            }
            item(key = "storage") {
                StatCard(Icons.Rounded.Storage, "Storage", s.storageText, s.storageUsed, s.storageTotal)
            }
            item(key = "cache") {
                StatCard(
                    Icons.Rounded.Speed, "EdgeStream cache", s.cacheText,
                    s.cacheBytes, s.cacheBytes.coerceAtLeast(1),
                )
            }
            if (s.modelBytes > 0) item(key = "model") {
                StatCard(
                    Icons.Rounded.Speed, "Speech model (captions)", s.modelText,
                    s.modelBytes, s.modelBytes,
                )
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EdgeButton("Close Background Apps", {
                        freeMemory(context)
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { stats = computeStats(context) }
                        vm.showNotice("Background apps closed")
                    }, Modifier.focusRequester(first), primary = true, icon = Icons.Rounded.DeleteSweep)
                    EdgeButton("Clear Cache", {
                        clearOwnCache(context)
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { stats = computeStats(context) }
                        vm.showNotice("EdgeStream cache cleared")
                    }, icon = Icons.Rounded.DeleteSweep)
                    if (s.modelBytes > 0) EdgeButton("Delete Speech Model", {
                        File(context.filesDir, "vosk-en").deleteRecursively()
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { stats = computeStats(context) }
                        vm.showNotice("Speech model deleted — it downloads again if captions are used")
                    }, icon = Icons.Rounded.DeleteSweep)
                }
            }
            item(key = "apps-title") { SectionTitle("Installed Apps", Modifier.padding(top = 6.dp)) }
            item(key = "apps-hint") {
                Text(
                    "OK opens an app's storage screen · hold OK closes the app in the background",
                    style = MaterialTheme.typography.bodyMedium, color = Edge.Dim, maxLines = 2,
                )
            }
            items(apps, key = { it.pkg }) { app ->
                FocusSurface(
                    onClick = { context.startActivity(appDetails(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onLongClick = {
                        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                            .killBackgroundProcesses(app.pkg)
                        vm.showNotice("${app.label} closed")
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    focusScale = 1.02f, glow = false,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storage, app.label, Modifier.size(22.dp), tint = Edge.Accent)
                        OneLine(app.label, Modifier.padding(start = 12.dp).weight(1f), MaterialTheme.typography.titleMedium)
                        OneLine("Manage", style = MaterialTheme.typography.labelMedium, color = Edge.Dim)
                    }
                }
            }
        }
    }
}

private class SysStats(
    val ramText: String, val ramUsed: Long, val ramTotal: Long,
    val storageText: String, val storageUsed: Long, val storageTotal: Long,
    val cacheText: String, val cacheBytes: Long,
    val modelText: String, val modelBytes: Long,
)

private class Cleanable(val pkg: String, val label: String)

private fun computeStats(context: Context): SysStats {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)
    val ramTotal = mem.totalMem
    val ramUsed = ramTotal - mem.availMem
    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    val storageTotal = stat.totalBytes
    val storageUsed = storageTotal - stat.availableBytes
    val cacheBytes = ownCacheBytes(context)
    val modelBytes = File(context.filesDir, "vosk-en").takeIf { it.isDirectory }
        ?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    return SysStats(
        ramText = "${gb(ramUsed)} used · ${gb(ramTotal)} total",
        ramUsed = ramUsed, ramTotal = ramTotal,
        storageText = "${gb(storageUsed)} used · ${gb(storageTotal)} total",
        storageUsed = storageUsed, storageTotal = storageTotal,
        cacheText = mb(cacheBytes),
        cacheBytes = cacheBytes,
        modelText = mb(modelBytes),
        modelBytes = modelBytes,
    )
}

/** EdgeStream's own cache footprint (Coil images + anything else in cacheDir, minus downloads in flight). */
private fun ownCacheBytes(context: Context): Long {
    val dir = context.cacheDir
    return dir.listFiles().orEmpty()
        .filter { it.name != "vosk-en.zip" }
        .sumOf { f -> if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length() }
}

private fun clearOwnCache(context: Context) {
    context.cacheDir.listFiles().orEmpty()
        .filter { it.name != "vosk-en.zip" }
        .forEach { it.deleteRecursively() }
}

/** Closes background processes of every non-system app — the killer apps do exactly this. */
private fun freeMemory(context: Context) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val pm = context.packageManager
    runCatching {
        pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .forEach { am.killBackgroundProcesses(it.packageName) }
    }
}

private fun cleanableApps(context: Context): List<Cleanable> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val fallback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: runCatching { pm.queryIntentActivities(fallback, 0) }.getOrDefault(emptyList())
    return infos.asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .map { Cleanable(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private fun appDetails(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_073_741_824.0)
private fun mb(bytes: Long): String = if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${bytes / 1024} KB"

@Composable
private fun StatCard(icon: ImageVector, title: String, caption: String, used: Long, total: Long) {
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, title, Modifier.size(24.dp), tint = Edge.Accent)
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp))
            Spacer(Modifier.weight(1f))
            Text(caption, style = MaterialTheme.typography.labelMedium, color = Edge.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ProgressLine(
            if (total > 0) used.toFloat() / total else 0f,
            Modifier.fillMaxWidth().padding(top = 10.dp), 6.dp,
        )
    }
}
