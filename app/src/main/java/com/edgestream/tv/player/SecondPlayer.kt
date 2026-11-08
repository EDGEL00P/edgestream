package com.edgestream.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.edgestream.tv.data.Http
import com.edgestream.tv.data.StreamUrl

/**
 * The right-hand picture in Multiview: a plain live player beside the main engine. The SHIELD
 * has two video decoders, so there is never a third. Starts muted; the screen decides whose
 * sound plays. Owned by the view model; [release] frees its decoder.
 */
@OptIn(UnstableApi::class)
class SecondPlayer(context: Context) {
    @Volatile private var headers: Map<String, String> = emptyMap()

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    ResolvingDataSource.Factory(OkHttpDataSource.Factory(Http.client)) { spec ->
                        val h = headers
                        if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
                    },
                ),
            ),
        )
        .build()
        .apply { volume = 0f }

    fun play(url: String) {
        val parsed = StreamUrl.parse(url)
        headers = parsed.headers
        exo.setMediaItem(MediaItem.fromUri(parsed.url))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** App hidden: free the decoder but keep the channel, so [resume] picks it back up. */
    fun stop() { exo.clearVideoSurface(); exo.stop() }

    fun resume() { exo.prepare(); exo.playWhenReady = true }

    fun release() { exo.clearVideoSurface(); exo.release() }
}
