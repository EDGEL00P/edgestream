package com.edgestream.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.edgestream.tv.data.Http
import com.edgestream.tv.data.Settings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TrackOption(val group: Int, val track: Int, val label: String, val selected: Boolean)

data class PlaybackState(
    val url: String? = null,
    val title: String = "",
    val isLive: Boolean = true,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val videoInfo: String = "",
    val frameRate: Float = 0f,
    val videoHeight: Int = 0,
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
)

private const val STALL_TIMEOUT_MS = 10_000L

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
enum class AspectMode(val label: String, val resizeMode: Int) {
    /** Always fits the picture; use Stretch or Zoom to override. */
    AUTO("Auto (fit screen)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

/**
 * One ExoPlayer for the whole app. The live preview and the full-screen player share it, so
 * going full screen just moves the video surface: the stream never restarts or rebuffers.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(private val context: Context, private val settings: Settings) {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state
    val aspect = MutableStateFlow(AspectMode.AUTO)

    /** Display aspect of the current video (pixel aspect applied), 0 until the first frame size is known. */
    private val _videoAspect = MutableStateFlow(0f)
    val videoAspect: StateFlow<Float> = _videoAspect

    /**
     * Auto: for live TV, a picture within ~5% of the screen's shape (16:9 TV, anamorphic SD)
     * is filled edge to edge — nobody notices. Anything further off is fitted so people never
     * look stretched. For VOD (movies), even close-aspect content uses FIT: a film must never
     * be stretched, and its letterboxing is the cinematographer's framing, not black bars to
     * remove.
     */
    fun resizeModeFor(mode: AspectMode, video: Float, isLive: Boolean): Int {
        // AUTO is always a true fit: never stretch a near-16:9 live feed and never crop it.
        // Stretch and Zoom remain explicit choices in the Picture menu.
        return if (mode == AspectMode.AUTO) AspectRatioFrameLayout.RESIZE_MODE_FIT else mode.resizeMode
    }
    /** True while the video is full screen; display-mode switching only happens then. */
    val fullscreen = MutableStateFlow(false)

    private var retries = 0

    /**
     * A live stream stuck buffering usually means the server stopped feeding this connection.
     * Rejoining at the live edge recovers far faster than waiting for the buffer to refill.
     */
    private val stallWatchdog = Runnable {
        if (exo.playbackState == Player.STATE_BUFFERING && _state.value.isLive && _state.value.url != null) {
            // Quiet, like a set-top box: the loading ring only, no narration.
            _state.value = _state.value.copy(buffering = true, error = null)
            exo.seekToDefaultPosition()
            exo.prepare()
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retries = 0
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            main.removeCallbacks(stallWatchdog)
            if (playbackState == Player.STATE_BUFFERING && _state.value.isLive) main.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
            _state.value = _state.value.copy(
                buffering = playbackState == Player.STATE_BUFFERING,
                error = if (playbackState == Player.STATE_READY) null else _state.value.error,
            )
        }

        override fun onTracksChanged(tracks: Tracks) = publishTracks(tracks)

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = _state.value.url ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exo.seekToDefaultPosition(); exo.prepare(); return
            }
            // Live IPTV streams drop all the time. Like a set-top box, keep reconnecting at the
            // live edge for as long as the failure looks like the network or server; only give
            // up on errors another try can't fix (refused login, missing stream, bad codec).
            if (_state.value.isLive && isTransient(error)) {
                retries++
                _state.value = _state.value.copy(buffering = true, error = null)
                val delay = (1_000L * retries).coerceAtMost(5_000L)
                main.postDelayed({
                    if (_state.value.url == url) { exo.seekToDefaultPosition(); exo.prepare(); exo.play() }
                }, delay)
            } else if (!_state.value.isLive && recoverableOnDemand(error.errorCode) && retries < 2) {
                // A film whose audio track or decoder hung: start again from the same spot.
                retries++
                android.util.Log.w("EdgePlayer", "on-demand playback hung (${error.errorCodeName}); restarting at ${exo.currentPosition} ms", error)
                _state.value = _state.value.copy(buffering = true, error = null)
                main.post { if (_state.value.url == url) retry() }
            } else {
                _state.value = _state.value.copy(buffering = false, error = describe(error))
            }
        }
    }

    /**
     * Whether another attempt could plausibly succeed. HTTP 401/403/404/410 mean the provider
     * refused or doesn't have the stream, and decoder/format errors repeat identically, so those
     * surface at once. Everything else (timeouts, resets, 5xx, malformed TS from a hiccup) retries.
     */
    private fun isTransient(error: PlaybackException): Boolean {
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http != null) return http.responseCode !in setOf(401, 403, 404, 410)
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            -> false
            else -> true
        }
    }

    /**
     * A failed segment or chunk is retried in place this many times before it fails the whole
     * stream; the stock policy gives HLS only 3, so one lost segment could stop playback.
     * For VOD, a full 15 retries keeps a movie going through a bad CDN patch.
     */
    private val loadErrorPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int) = 15
    }

    /** Headers for the current stream (from "url|Header=value"); applied to every request it makes. */
    @Volatile private var streamHeaders: Map<String, String> = emptyMap()

    /**
     * Adaptive quality that steps up fast (4 s of headroom for VOD instead of the live 8 s)
     * and only uses 70 % of the measured bandwidth, so a brief dip doesn't cause a rebuffer.
     * The wider 4–40 s range lets movies ramp to full quality quickly and hold it.
     */
    private val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
        context,
        androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(4_000, 40_000, 25_000, 0.7f),
    )

    /** Captions generated from the audio for streams without subtitles (see [AutoCaptions]). */
    val captions = AutoCaptions(context)

    private val exoLazy = lazy { buildExo() }
    val exo: ExoPlayer by exoLazy

    private fun buildExo(): ExoPlayer = run {
        // A dedicated connection pool for streams: zapping between channels reuses warm TCP
        // connections, and the timeouts drop dead providers without cutting off a slow but
        // healthy 250–300 ms source mid-segment.
        val streamClient = Http.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
        val http = androidx.media3.datasource.ResolvingDataSource.Factory(OkHttpDataSource.Factory(streamClient)) { spec ->
            val h = streamHeaders
            if (h.isEmpty()) spec else spec.withAdditionalHeaders(h)
        }
        val dataSource = DefaultDataSource.Factory(context, http)
        // The setting is milliseconds now. 500 ms is the floor Media3 can express for a live
        // target, and the load control must never ask for less than the start threshold.
        val bufferMs = settings.value.bufferMs.coerceIn(500, 60_000)
        // Start quickly and keep a deep read-ahead (see bufferPlan). Size limits win over time,
        // so a 4K remux can't fill the heap. No back buffer: what's kept behind the playhead
        // counts against the same byte budget, and 30 s of a 60–80 Mbps debrid remux (225–300 MB)
        // is more than the whole budget, leaving nothing ahead: the picture stalled and jumped.
        // A rewind re-reads from the server instead.
        val plan = bufferPlan(bufferMs)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(plan.minMs, plan.maxMs, plan.startMs, plan.rebufferMs)
            .setTargetBufferBytes(bufferBudgetBytes(Runtime.getRuntime().maxMemory()))
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // NextLib adds FFmpeg decoders (DTS/TrueHD/FLAC/ALAC/Opus/Vorbis/MP2…) behind the platform ones.
        val renderers = object : io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(context) {
            // Same sink as the default, plus a tee that hands decoded audio to the captioner.
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(captions)))
                    .build()
        }
            // Hardware decoders and Dolby/DTS passthrough first; FFmpeg only when they can't play a track.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            // Run MediaCodec asynchronously with its own feeder threads. Stock Media3 only does
            // this from Android 12; the SHIELD and Fire TVs are older, and it cuts dropped
            // frames and audio underruns there.
            .forceEnableMediaCodecAsynchronousQueueing()
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSource)
                    .setLoadErrorHandlingPolicy(loadErrorPolicy)
                    // Exact millisecond target; the old 10 s cap made low settings meaningless.
                    .setLiveTargetOffsetMs(bufferMs.toLong())
                    .setLiveMinSpeed(0.98f)
                    .setLiveMaxSpeed(1.08f),
            )
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // TV remote seeking: short/long jumps map cleanly to back/forward on D-pad controls.
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .also { player ->
                // These are player-level settings; applying them here keeps ExoPlayer off the
                // cold-start path while still respecting the saved preferences on first use.
                val s = settings.value
                // Never stretch the picture: FIT is the default and the fix for "squashed" movies.
                // SCALE_TO_FIT + RESIZE_MODE_FIT = letterbox, never distort. SmartFrame zoom
                // stays at 1.0 (reset in VideoSurface) — any other scaling distorts faces.
                player.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                player.videoChangeFrameRateStrategy =
                    if (s.matchFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(s.audioLanguage.ifBlank { null })
                    .setPreferredTextLanguage(s.subtitleLanguage.ifBlank { null })
                    .setSelectUndeterminedTextLanguage(s.subtitlesOn)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.subtitlesOn)
                    .build()
                player.addListener(listener)
                if (com.edgestream.tv.data.Sports.traceOn) player.addAnalyticsListener(metrics)
            }
    }

    private val metrics = PlaybackMetrics()

    init {
        val s = settings.value
        captions.setEnabled(s.autoCaptions)
        trackSelector.parameters = trackSelector.buildUponParameters().setTunnelingEnabled(tunnelFor(s.tunneling, _state.value.isLive)).build()
    }

    /** Preferred audio/subtitle languages and whether subtitles start switched on. */
    fun applyPreferences(s: com.edgestream.tv.data.AppSettings) {
        captions.setEnabled(s.autoCaptions)
        trackSelector.parameters = trackSelector.buildUponParameters().setTunnelingEnabled(tunnelFor(s.tunneling, _state.value.isLive)).build()
        // Only touch the player if it has actually been created; otherwise first playback will
        // read these values when it is built.
        if (!exoLazy.isInitialized()) return
        exo.videoChangeFrameRateStrategy =
            if (s.matchFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(s.audioLanguage.ifBlank { null })
            .setPreferredTextLanguage(s.subtitleLanguage.ifBlank { null })
            .setSelectUndeterminedTextLanguage(s.subtitlesOn)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.subtitlesOn)
            .build()
    }

    /** Published to launchers while full screen; see [PlaybackSession]. */
    private var session: PlaybackSession? = null
    private var info: NowPlayingInfo? = null

    fun setSessionActive(active: Boolean) {
        if (!active) { session?.setActive(false); return }
        ensureSession().setActive(true)
    }

    private fun ensureSession(): PlaybackSession = session ?: PlaybackSession(context, exo).also { session = it }

    fun play(url: String, title: String, isLive: Boolean, startPositionMs: Long = 0, info: NowPlayingInfo? = null) {
        if (_state.value.url == url && exo.playbackState != Player.STATE_IDLE && _state.value.error == null) {
            if (info != null) updateInfo(info)
            exo.play(); return
        }
        retries = 0
        this.info = info
        session?.setLink(info?.link)
        val parsed = com.edgestream.tv.data.StreamUrl.parse(url)
        streamHeaders = parsed.headers
        val item = MediaItem.Builder()
            .setUri(parsed.url)
            .apply { if (info != null) applyInfo(info) else setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()) }
            .apply { mimeFor(parsed.url)?.let { setMimeType(it) } }
            .apply {
                if (isLive) setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        // Hug the live edge at the exact millisecond target. The window allows
                        // 0.5–2× the target so catch-up does not slam into the live edge.
                        .setTargetOffsetMs(settings.value.bufferMs.coerceIn(500, 60_000).toLong())
                        .setMinOffsetMs(500)
                        .setMaxOffsetMs(minOf(settings.value.bufferMs.coerceIn(500, 60_000) * 2L, 30_000L))
                        .setMinPlaybackSpeed(0.98f)
                        .setMaxPlaybackSpeed(1.08f)
                        .build(),
                )
            }
            .build()
        _state.value = PlaybackState(url = url, title = title, isLive = isLive, buffering = true)
        if (com.edgestream.tv.data.Sports.traceOn) { metrics.summary(); metrics.begin(title) }
        // Tunnelling is decided per stream, before prepare (see tunnelFor).
        trackSelector.parameters = trackSelector.buildUponParameters().setTunnelingEnabled(tunnelFor(settings.value.tunneling, isLive)).build()
        exo.setMediaItem(item, startPositionMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun retry() {
        val s = _state.value
        val url = s.url ?: return
        _state.value = s.copy(url = null)
        play(url, s.title, s.isLive, if (s.isLive) 0 else exo.currentPosition, info)
    }

    /**
     * New details for what's already playing (the next programme on a live channel). Only the
     * metadata changes, so the stream carries on without re-buffering.
     */
    fun updateInfo(next: NowPlayingInfo) {
        if (next == info) return
        info = next
        session?.setLink(next.link)
        val current = exo.currentMediaItem ?: return
        runCatching { exo.replaceMediaItem(exo.currentMediaItemIndex, current.buildUpon().applyInfo(next).build()) }
    }

    fun stop() {
        if (!exoLazy.isInitialized()) return
        main.removeCallbacks(stallWatchdog)
        exo.stop()
        exo.clearMediaItems()
        exo.clearVideoSurface()
        session?.setActive(false)
        _state.value = PlaybackState()
    }

    /** App hidden: stop() keeps the playlist but frees the decoder (pause() would hold it). */
    fun releaseDecoder() {
        if (!exoLazy.isInitialized()) return
        main.removeCallbacks(stallWatchdog)
        exo.clearVideoSurface()
        exo.stop()
    }
    fun resume() {
        if (_state.value.url == null) return
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    fun cycleAspect() { aspect.value = AspectMode.entries[(aspect.value.ordinal + 1) % AspectMode.entries.size] }

    /**
     * Side-loads online subtitles into the stream playing [url]. ExoPlayer only reads subtitle
     * files when an item is prepared, so the item is re-set at the same position (one short
     * rebuffer); [select] switches the first one on. Ignored once a different stream plays.
     */
    fun addSubtitles(url: String, subs: List<com.edgestream.tv.data.OnlineSubtitles.Sub>, select: Boolean) {
        if (subs.isEmpty() || _state.value.url != url) return
        val current = exo.currentMediaItem ?: return
        val configs = subs.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(s.language).setLabel(s.label)
                .setSelectionFlags(if (select && i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val position = exo.currentPosition
        val playing = exo.playWhenReady
        exo.setMediaItem(current.buildUpon().setSubtitleConfigurations(configs).build(), position)
        exo.prepare()
        exo.playWhenReady = playing
        if (select) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    fun selectTrack(option: TrackOption?, type: Int) {
        val builder = exo.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = exo.currentTracks.groups.getOrNull(option.group) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.track))
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun publishTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val subs = mutableListOf<TrackOption>()
        var video = ""
        var fps = 0f
        var height = 0
        tracks.groups.forEachIndexed { gi, g ->
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackOption(gi, ti, buildString {
                        append(f.label ?: f.language?.let { java.util.Locale(it).displayLanguage } ?: "Track ${audio.size + 1}")
                        if (f.channelCount > 0) append(" · ${channelsLabel(f.channelCount)}")
                        f.sampleMimeType?.substringAfter('/')?.uppercase()?.let { append(" · $it") }
                    }, g.isTrackSelected(ti))
                    C.TRACK_TYPE_TEXT -> subs += TrackOption(gi, ti,
                        f.label ?: f.language?.let { java.util.Locale(it).displayLanguage } ?: "Subtitle ${subs.size + 1}",
                        g.isTrackSelected(ti))
                    C.TRACK_TYPE_VIDEO -> if (g.isTrackSelected(ti) && f.height > 0) {
                        fps = f.frameRate; height = f.height
                        video = "${f.width}×${f.height}" + (if (f.frameRate > 0) " · ${f.frameRate.toInt()}fps" else "") +
                            (f.sampleMimeType?.substringAfter('/')?.let { " · ${it.uppercase()}" } ?: "")
                    }
                }
            }
        }
        _state.value = _state.value.copy(audio = audio, subtitles = subs, videoInfo = video, frameRate = fps, videoHeight = height)
    }

    private fun channelsLabel(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch" }

    private fun mimeFor(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            when (code) {
                404, 410 -> "This stream is offline at its source (HTTP $code)."
                401, 403 -> "The server refused this stream (HTTP $code): it may be region-locked, or your subscription or connection limit was hit."
                400 -> "The stream address is no longer valid (HTTP 400)."
                else -> "The stream server returned an error${code?.let { " (HTTP $it)" } ?: ""}."
            }
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't reach the stream server."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Unsupported or broken stream format."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode the stream's video or audio."
        else -> "Playback failed (${e.errorCodeName})."
    }
}
