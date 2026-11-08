package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity

/**
 * One row per real channel. IPTV providers list the same feed many times — HD, FHD, 4K,
 * `[BK]` backups, `+1` timeshift copies — and the guide, search and zapping lists should not
 * repeat them. Favourites always win, then online feeds, then the best quality tier.
 */
object ChannelDedupe {
    private val cleaner = NameCleaner()

    fun apply(
        channels: List<ChannelEntity>,
        favourites: Set<String>,
        cleanTitles: Map<String, String> = emptyMap(),
    ): List<ChannelEntity> {
        if (channels.size < 2) return channels
        val best = HashMap<String, ChannelEntity>(channels.size)
        for (channel in channels) {
            val key = key(channel, cleanTitles[channel.key])
            val current = best[key]
            if (current == null || better(channel, current, favourites)) best[key] = channel
        }
        return channels.filter { best[key(it, cleanTitles[it.key])]?.key == it.key }
    }

    /** Country + cleaned name, so US "Fox Sports" and AU "Fox Sports" stay separate. */
    private fun key(channel: ChannelEntity, cleanTitle: String?): String {
        val name = (cleanTitle ?: cleaner.clean(channel.name).title)
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return ChannelTaxonomy.country(channel) + ":" + name
    }

    private fun better(candidate: ChannelEntity, current: ChannelEntity, favourites: Set<String>): Boolean {
        val candidateFav = candidate.key in favourites
        val currentFav = current.key in favourites
        if (candidateFav != currentFav) return candidateFav
        if (candidate.offline != current.offline) return !candidate.offline
        val candidateRank = ChannelNumbers.rank(candidate)
        val currentRank = ChannelNumbers.rank(current)
        if (candidateRank != currentRank) return candidateRank < currentRank
        if (candidate.number != current.number) return candidate.number < current.number
        return candidate.id < current.id
    }
}
