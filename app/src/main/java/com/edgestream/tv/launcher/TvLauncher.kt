package com.edgestream.tv.launcher

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.edgestream.tv.R
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.data.db.ProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EdgeStream on the Android TV home screen:
 *  - films and episodes in progress go into the system "Continue watching" (Play Next) row;
 *  - favourite and recent channels form an "EdgeStream Live TV" row with what's on now.
 * Every card carries an edgestream:// link (see [Links]) that opens straight into it.
 *
 * Home-screen channels and Watch Next need Android 8; older boxes simply skip all of this.
 */
object TvLauncher {
    private const val TAG = "TvLauncher"
    private const val PREFS = "tv_launcher"
    private const val KEY_CHANNEL = "liveChannelId"
    private const val MAX_LIVE = 20

    private val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    // ---- Continue watching --------------------------------------------------------------

    /** Adds or refreshes [p] in Continue watching; a finished title is taken out instead. */
    @SuppressLint("RestrictedApi")
    suspend fun updateContinue(context: Context, p: ProgressEntity) = withContext(Dispatchers.IO) {
        if (!supported) return@withContext
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val idKey = "wn:${p.key}"
            val finished = p.durationMs > 0 && p.positionMs.toDouble() / p.durationMs > 0.92
            val existing = prefs.getLong(idKey, -1L)
            if (finished) {
                if (existing >= 0) context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null)
                prefs.edit().remove(idKey).apply()
                return@runCatching
            }
            val episode = p.key.contains(":E:")
            val program = WatchNextProgram.Builder()
                .setType(if (episode) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(p.updatedAt)
                .setTitle(p.title)
                .setLastPlaybackPositionMillis(p.positionMs.toInt())
                .setDurationMillis(p.durationMs.toInt())
                .setIntentUri(Links.resume(p.key))
                .setInternalProviderId(p.key)
                .apply {
                    p.poster?.let {
                        setPosterArtUri(Uri.parse(it))
                        setPosterArtAspectRatio(
                            if (episode) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3,
                        )
                    }
                }
                .build()
            val values = program.toContentValues()
            // If the user removed the card from the row, the update finds nothing: add it back
            // only when they watch again, which is exactly now.
            val updated = existing >= 0 &&
                context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(existing), values, null, null) > 0
            if (!updated) {
                context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                    ?.let { prefs.edit().putLong(idKey, ContentUris.parseId(it)).apply() }
                    ?: android.util.Log.w(TAG, "Continue watching: insert returned nothing")
            }
        }.onFailure { android.util.Log.w(TAG, "Continue watching update failed", it) }
    }

    // ---- Live TV row --------------------------------------------------------------------

    /** Rebuilds the Live TV row: [channels] in order, each with its current programme. */
    @SuppressLint("RestrictedApi")
    suspend fun syncLive(context: Context, channels: List<ChannelEntity>, now: (ChannelEntity) -> ProgrammeEntity?) =
        withContext(Dispatchers.IO) {
            if (!supported) return@withContext
            runCatching {
                val id = ensureLiveChannel(context) ?: return@runCatching
                val cr = context.contentResolver
                cr.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
                channels.take(MAX_LIVE).forEachIndexed { i, ch ->
                    val on = now(ch)
                    val program = PreviewProgram.Builder()
                        .setChannelId(id)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                        .setLive(true)
                        .setTitle(on?.title ?: ch.name)
                        .setDescription(if (on != null) ch.name else com.edgestream.tv.data.NameCleaner.tidyGroup(ch.groupName))
                        .setIntentUri(Links.live(ch.key))
                        .setInternalProviderId(ch.key)
                        .setWeight(MAX_LIVE - i)
                        .apply {
                            ch.logo?.let {
                                setPosterArtUri(Uri.parse(it))
                                setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                            }
                            if (on != null) { setStartTimeUtcMillis(on.start); setEndTimeUtcMillis(on.stop) }
                        }
                        .build()
                    cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
                }
            }.onFailure { android.util.Log.w(TAG, "Live TV row update failed", it) }
        }

    /** The app's own home-screen row, created once; recreated if the system dropped it. */
    private fun ensureLiveChannel(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val helper = PreviewChannelHelper(context)
        val saved = prefs.getLong(KEY_CHANNEL, -1L)
        if (saved >= 0 && runCatching { helper.getPreviewChannel(saved) }.getOrNull() != null) return saved
        val logo = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(160, 160)
        val channel = PreviewChannel.Builder()
            .setDisplayName("EdgeStream Live TV")
            .setDescription("Your favourite and recent channels")
            .setAppLinkIntentUri(Links.open("LIVE"))
            .setInternalProviderId("edgestream-live")
            .apply { logo?.let { setLogo(it) } }
            .build()
        // The first channel an app publishes can be its default one, shown without asking.
        val id = runCatching { helper.publishDefaultChannel(channel) }.getOrNull()
            ?: runCatching { helper.publishChannel(channel) }.getOrNull() ?: return null
        prefs.edit().putLong(KEY_CHANNEL, id).apply()
        return id
    }
}

/** edgestream:// links used by home-screen cards (and anything else that wants to open a spot in the app). */
object Links {
    const val SCHEME = "edgestream"
    fun live(channelKey: String): Uri = Uri.Builder().scheme(SCHEME).authority("live").appendPath(channelKey).build()
    fun resume(progressKey: String): Uri = Uri.Builder().scheme(SCHEME).authority("resume").appendPath(progressKey).build()
    fun open(screen: String): Uri = Uri.Builder().scheme(SCHEME).authority("open").appendPath(screen).build()
}
