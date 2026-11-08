package com.edgestream.tv.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.edgestream.tv.ui.AppViewModel
import com.edgestream.tv.ui.Edge
import com.edgestream.tv.ui.EdgeShapes
import com.edgestream.tv.ui.components.EdgeButton
import com.edgestream.tv.ui.components.FocusSurface
import kotlinx.coroutines.delay

/** One installed app, ready to launch from the grid. */
private data class AppTile(val label: String, val pkg: String, val intent: Intent, val icon: ImageBitmap?)

/**
 * The Apps tab: the viewer's own hand-picked shortcuts plus a tile into the TV's system
 * settings. Only these show — not the whole drawer — which is what makes EdgeStream the one
 * launcher you need on the box.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val apps by produceState(emptyList<AppTile>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadApps(context) }
    }
    val first = remember { FocusRequester() }
    // Retry for a moment: on some platforms the grid's nodes are not attached yet when the
    // screen first composes, and a single early request is silently dropped.
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isEmpty()) return@LaunchedEffect
        repeat(10) { delay(60); runCatching { first.requestFocus() } }
    }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 18.dp, end = 42.dp, bottom = 26.dp)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = remember(context) { context.getSystemService(android.app.role.RoleManager::class.java) }
            if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                Row(Modifier.padding(top = 12.dp)) {
                    EdgeButton(
                        "Set EdgeStream as the home app",
                        {
                            runCatching {
                                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
                                    .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                                // The ROM's dialog reads the caller via getCallingPackage(), which only
                                // startActivityForResult populates; a plain start leaves it null and
                                // the dialog closes itself instantly.
                                val activity = context as? android.app.Activity
                                if (activity != null) activity.startActivityForResult(intent, 1001)
                                else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        primary = true,
                    )
                }
            }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(148.dp),
            Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // The first tile always exists, so the initial focus requester lives here permanently
            // (a requester moved between nodes as data loads silently breaks D-pad focus).
            item(key = "__system_settings") {
                SystemSettingsTile(Modifier.focusRequester(first))
            }
            items(apps, key = { it.pkg }) { app ->
                AppTileCard(app, Modifier)
            }
        }
    }
}

@Composable
private fun SystemSettingsTile(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FocusSurface(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        modifier = modifier.aspectRatio(1f),
        shape = EdgeShapes.Poster,
        focusScale = 1.08f,
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Settings, "System settings", Modifier.size(40.dp), tint = Edge.Accent)
            Text(
                "System settings", style = MaterialTheme.typography.labelLarge, color = Edge.Text,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun AppTileCard(app: AppTile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FocusSurface(
        onClick = { runCatching { context.startActivity(app.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        onLongClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        modifier = modifier.aspectRatio(1f),
        shape = EdgeShapes.Poster,
        focusScale = 1.08f,
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (app.icon != null) {
                androidx.compose.foundation.Image(app.icon, app.label, Modifier.size(64.dp))
            }
            Text(
                app.label, style = MaterialTheme.typography.labelLarge, color = Edge.Text,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The viewer's hand-picked shortcuts, matched by name or package against everything launchable
 * (TV apps and sideloaded mobile APKs alike). Order is the viewer's order.
 */
private val PREFERRED_APPS = listOf(
    "tizen" to "TizenTube",
    "nuvio" to "NuvioEdge",
    "appkiller" to "AppKiller",
    "cache" to "Cache Cleaner",
)

/** The curated shortcuts; every launchable app as a fallback so the tab is never empty. */
private fun loadApps(context: Context): List<AppTile> {
    val pm = context.packageManager
    fun query(category: String): List<AppTile> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                val launch = pm.getLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).setClassName(pkg, info.activityInfo.name)
                val icon = runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                AppTile(info.loadLabel(pm).toString(), pkg, launch, icon)
            }
            .toList()
    }
    // Sideloaded helpers often lack the TV launcher category, so both lists are searched.
    val all = (query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER))
        .distinctBy { it.pkg }
    val curated = LinkedHashMap<String, AppTile>()
    for ((needle, _) in PREFERRED_APPS) {
        val hit = all.firstOrNull { app ->
            app.label.lowercase().contains(needle) || app.pkg.lowercase().contains(needle)
        }
        if (hit != null) curated.putIfAbsent(hit.pkg, hit)
    }
    return curated.values.toList().ifEmpty { all.sortedBy { it.label.lowercase() } }
}
