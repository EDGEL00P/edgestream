package com.edgestream.tv.ui.glass

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.edgestream.tv.data.Http
import com.edgestream.tv.data.StreamUrl

/**
 * A live channel as the Home backdrop: muted, cropped to fill, at most 1080p. It takes the place
 * of the highlight reel (never runs beside it), so Home still uses one of the SHIELD's two
 * decoders. Any playback error hands control back through [onFailed]; leaving or hiding the app
 * frees the decoder.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePreview(url: String, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val parsed = remember(url) { StreamUrl.parse(url) }
    val player = remember(url) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        ctx,
                        ResolvingDataSource.Factory(OkHttpDataSource.Factory(Http.client)) { spec ->
                            if (parsed.headers.isEmpty()) spec else spec.withAdditionalHeaders(parsed.headers)
                        },
                    ),
                ),
            )
            // Preview, not primary playback: small buffer for faster start and less memory pressure.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 8_000, 500, 1_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .build().apply {
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
                setMediaItem(MediaItem.fromUri(parsed.url))
            }
    }
    DisposableEffect(player) {
        val failed = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onFailed()
        }
        player.addListener(failed)
        onDispose { player.removeListener(failed); player.clearVideoSurface(); player.release() }
    }
    LifecycleStartEffect(player) {
        player.prepare()
        player.playWhenReady = true
        onStopOrDispose { player.stop() }
    }
    var surface by remember(player) { androidx.compose.runtime.mutableStateOf<TextureView?>(null) }
    SampleForGlass(surface, 1.12f)
    AndroidView(
        factory = { c -> TextureView(c).also { player.setVideoTextureView(it); surface = it } },
        // Slightly larger than the screen, like the highlight reel: fills overscan, crops corner bugs.
        modifier = modifier.graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
    )
}
