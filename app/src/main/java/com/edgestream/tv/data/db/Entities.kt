package com.edgestream.tv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceType { M3U, XTREAM }

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    /** M3U: playlist URL. Xtream: server base URL, e.g. http://host:port */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional XMLTV override. Blank = use the url-tvg header (M3U) or xmltv.php (Xtream). */
    val epgUrl: String = "",
    val lastRefresh: Long = 0,
    val lastEpgRefresh: Long = 0,
    val lastError: String? = null,
    /** Guide URLs announced by the playlist itself (M3U url-tvg header), comma separated. */
    val headerEpgUrls: String = "",
    val serverTimezone: String? = null,
    val expiresAt: Long? = null,
    val channelCount: Int = 0,
    val vodCount: Int = 0,
    val accountStatus: String? = null,
    val maxConnections: String? = null,
    val activeConnections: String? = null,
    val isTrial: Boolean = false,
    /** How many of this source's channels the guide covered at the last update. */
    val guideMatched: Int = 0,
    /** Guide files actually used at the last update (comma separated). */
    val guideUrlsUsed: String = "",
)

/**
 * A live channel. [key] is stable across refreshes so favourites and history survive.
 */
@Entity(
    tableName = "channels",
    indices = [Index("sourceId"), Index("groupName"), Index("epgId"), Index(value = ["key"], unique = true)],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val sourceId: Long,
    val name: String,
    val groupName: String,
    val logo: String?,
    val streamUrl: String,
    val epgId: String?,
    val number: Int,
    val catchupDays: Int = 0,
    /** "xtream", "flussonic", "shift", or "append|<template>" / "default|<template>" for M3U catch-up. */
    val catchupSource: String? = null,
    val xtreamStreamId: Long? = null,
    /** Set by a stream check when the source doesn't answer; cleared at the next playlist refresh. */
    val offline: Boolean = false,
    /** The playlist's own tvg-id, never overwritten; [epgId] holds the guide channel it resolved to. */
    val tvgId: String? = null,
    /** What the channel really is, as a [com.edgestream.tv.data.ChannelIdentity.ref] ("NBC.us@WTLV"); null when unknown. */
    val identity: String? = null,
)

enum class VodKind { MOVIE, SERIES }

@Entity(
    tableName = "vod",
    indices = [Index("sourceId"), Index("kind", "category"), Index(value = ["key"], unique = true)],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val sourceId: Long,
    val kind: VodKind,
    val name: String,
    val category: String,
    val poster: String?,
    /** Movies: direct stream URL. Series: empty, episodes are fetched on demand. */
    val streamUrl: String,
    val xtreamId: Long? = null,
    val rating: String? = null,
    val year: String? = null,
    val plot: String? = null,
    val added: Long = 0,
)

@Entity(tableName = "programmes", indices = [Index("epgId", "start"), Index("stop")])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val epgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    /** Episode title from XMLTV `<sub-title>`, e.g. "The Reichenbach Fall". */
    val subtitle: String? = null,
    /** First transmission year from `<date>`. */
    val year: Int? = null,
    /** Content rating from `<rating><value>`, e.g. "TV-14". */
    val rating: String? = null,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,
    val addedAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val key: String,
    val watchedAt: Long,
)

/** Resume position for movies and episodes. */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val key: String,
    val title: String,
    val poster: String?,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

