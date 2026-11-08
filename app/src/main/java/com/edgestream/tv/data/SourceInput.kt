package com.edgestream.tv.data

import com.edgestream.tv.data.db.SourceType

/**
 * Reads a provider URL the way a person pastes it and fills the form for them.
 *
 * Accepts:
 *  - Xtream player_api.php / get.php links with username + password query parameters
 *  - `host:port` server roots with no path (treated as Xtream)
 *  - explicit .m3u / .m3u8 playlists
 *
 * Returns null while the text is still ambiguous, so typing never fights the user.
 */
data class SourceInput(
    val type: SourceType,
    val server: String,
    val user: String,
    val pass: String,
    val playlist: String,
)

object SourceInputParser {
    /** Accepts "host:port" shorthand, but only ever produces an http(s) URL; null when it can't. */
    fun playableUrl(raw: String): String? {
        val t = raw.trim()
        val u = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            "://" in t -> return null
            else -> "http://$t"
        }
        return u.takeIf { it.length <= 2_048 && runCatching { java.net.URI(it) }.getOrNull()?.host?.isNotBlank() == true }
    }

    fun parse(raw: String): SourceInput? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if ("://" in text) text else "http://$text"
        val uri = runCatching { android.net.Uri.parse(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (host == "http" || host == "https") return null
        val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: "http"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val server = "$scheme://$host$port"
        val user = uri.getQueryParameter("username").orEmpty()
        val pass = uri.getQueryParameter("password").orEmpty()
        val path = uri.path.orEmpty().lowercase()
        val playlist = path.endsWith(".m3u") || path.endsWith(".m3u8")

        return when {
            user.isNotBlank() && pass.isNotBlank() ->
                SourceInput(SourceType.XTREAM, server, user, pass, text)
            path.contains("player_api") || (path.contains("get.php") && path != "") ->
                SourceInput(SourceType.XTREAM, server, user, pass, text)
            playlist -> SourceInput(SourceType.M3U, server, "", "", text)
            path.isBlank() || path == "/" ->
                SourceInput(SourceType.XTREAM, server, "", "", text)
            else -> null
        }
    }

    /** A readable default name: the host without scheme, port or `www.`. */
    fun nameFor(raw: String): String {
        val text = if ("://" in raw) raw else "http://$raw"
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
        return (uri?.host ?: raw).removePrefix("www.")
    }
}
