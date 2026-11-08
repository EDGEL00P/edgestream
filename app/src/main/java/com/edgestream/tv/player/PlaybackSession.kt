package com.edgestream.tv.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.edgestream.tv.MainActivity

/**
 * What a home screen needs to know about the thing playing: launchers such as Edge read the
 * media session to build their Continue watching and Live TV rows, and reopen [link] when a
 * card is chosen.
 */
data class NowPlayingInfo(
    val title: String,
    /** Second line: the channel for live TV, the episode or year for films and series. */
    val subtitle: String? = null,
    val artworkUri: String? = null,
    /** edgestream:// link straight back to this item (see launcher.Links). */
    val link: Uri? = null,
    val live: Boolean = false,
)

/** Puts [info] on a media item in the fields media sessions publish to other apps. */
fun MediaItem.Builder.applyInfo(info: NowPlayingInfo): MediaItem.Builder {
    info.link?.let {
        // Media ID and request URI both carry the link; launchers read either.
        setMediaId(it.toString())
        setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(it).build())
    }
    return setMediaMetadata(info.toMetadata())
}

fun NowPlayingInfo.toMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setDisplayTitle(title)
    .setArtist(subtitle)
    .setSubtitle(subtitle)
    .setArtworkUri(artworkUri?.let(Uri::parse))
    .setIsPlayable(true)
    .setMediaType(if (live) MediaMetadata.MEDIA_TYPE_TV_CHANNEL else MediaMetadata.MEDIA_TYPE_MOVIE)
    .build()

/**
 * The app's media session. It exists only while video is full screen, so muted previews on
 * the Home and guide screens never show up as "watched" on the launcher.
 */
class PlaybackSession(private val context: Context, private val player: ExoPlayer) {
    private var session: MediaSession? = null
    private var link: Uri? = null

    fun setActive(active: Boolean) {
        if (active && session == null) {
            session = runCatching { MediaSession.Builder(context, player).setId("edgestream").build() }.getOrNull()
            applyActivity()
        } else if (!active) {
            session?.release()
            session = null
        }
    }

    /** Where the system "now playing" card and launcher taps return to. */
    fun setLink(uri: Uri?) {
        link = uri
        applyActivity()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyActivity() {
        val s = session ?: return
        val intent = Intent(Intent.ACTION_VIEW, link, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        s.setSessionActivity(
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }
}
