package com.edgestream.tv.ui.glass

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.edgestream.tv.data.SportsReels

/**
 * Real game highlights looping behind Home: muted, shuffled, cropped to fill, at most 1080p.
 * Composed only while nothing plays full screen, so it never holds one of the SHIELD's two
 * decoders during live TV; stop() frees its decoder whenever the app is hidden.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoBackdrop(
    reels: List<SportsReels.Reel>,
    sound: Boolean,
    modifier: Modifier = Modifier,
    /** Told which clip starts playing, for captions and transitions. */
    onClip: ((SportsReels.Reel) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val player = remember(reels) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            shuffleModeEnabled = true
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
            setMediaItems(reels.map { r ->
                MediaItem.Builder().setUri(r.stream).setMediaId(r.id).apply { if (r.stream.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            })
        }
    }
    // Quiet bed under the UI when the viewer turned it on; silent otherwise.
    androidx.compose.runtime.SideEffect { player.volume = if (sound) 0.35f else 0f }
    DisposableEffect(player) {
        // A clip's CDN link can expire before the 3-hourly refresh: skip it rather than stop.
        val skipBroken = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (player.mediaItemCount > 1) { player.seekToNextMediaItem(); player.prepare() }
            }
        }
        // The first clip's transition fires before anyone listens, so a drawn frame also counts;
        // each clip is reported once.
        var reported: String? = null
        fun report() {
            val id = player.currentMediaItem?.mediaId ?: return
            if (id == reported) return
            reported = id
            reels.firstOrNull { it.id == id }?.let { onClip?.invoke(it) }
        }
        val clips = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = report()
            override fun onRenderedFirstFrame() = report()
        }
        player.addListener(skipBroken)
        player.addListener(clips)
        onDispose { player.removeListener(skipBroken); player.removeListener(clips); player.clearVideoSurface(); player.release() }
    }
    LifecycleStartEffect(player) {
        player.prepare()
        player.playWhenReady = true
        onStopOrDispose { player.stop() }
    }
    // A new reel list rebuilds the player; a fresh view per player re-attaches the picture.
    key(player) {
        // 12% larger than the screen: covers the TV safe-area margins and crops the corner network bugs.
        AndroidView(
            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            modifier = modifier.graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
        )
    }
}
