package com.edgestream.tv.system

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.max

/**
 * Lets EdgeStream see other apps' media sessions (ported from EdgeLauncher). Android requires a
 * notification-listener grant for that; the service itself does nothing else.
 */
class MediaListenerService : NotificationListenerService()

/** What another app on the box is playing right now. */
@Immutable
data class OtherNowPlaying(
    val title: String,
    val subtitle: String,
    val appLabel: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    private val controls: MediaController.TransportControls,
) {
    fun playPause() = if (playing) controls.pause() else controls.play()
}

/** Follows the most relevant media session of any *other* app (EdgeStream's own player is excluded). */
class OtherAppsNowPlaying(private val context: Context) {
    val state: Flow<OtherNowPlaying?> = callbackFlow {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListenerService::class.java)
        val handler = Handler(Looper.getMainLooper())
        var current: MediaController? = null

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = push()
            override fun onPlaybackStateChanged(state: PlaybackState?) = push()
            override fun onSessionDestroyed() { current = null; push() }
            fun push() { trySend(current?.toNowPlaying()) }
        }

        fun pick(sessions: List<MediaController>?) {
            val others = sessions?.filter { it.packageName != context.packageName }
            val best = others?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: others?.firstOrNull()
            if (best?.sessionToken != current?.sessionToken) {
                current?.unregisterCallback(callback)
                current = best
                best?.registerCallback(callback, handler)
            }
            callback.push()
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            pick(msm.getActiveSessions(listener))
        } catch (_: SecurityException) {
            trySend(null) // no notification-listener grant: the chip simply never shows
        }
        awaitClose {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
            current?.unregisterCallback(callback)
        }
    }

    private fun MediaController.toNowPlaying(): OtherNowPlaying? {
        val meta = metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val subtitle = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val appLabel = runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() } }
            .getOrDefault(packageName)
        return OtherNowPlaying(title, subtitle, appLabel, art?.let { shrink(it).asImageBitmap() },
            playbackState?.state == PlaybackState.STATE_PLAYING, transportControls)
    }

    /** Album art often arrives at 1000 px+; the chip shows it at ~40 dp. */
    private fun shrink(bitmap: Bitmap): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= 160) return bitmap
        val ratio = 160f / largest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }
}
