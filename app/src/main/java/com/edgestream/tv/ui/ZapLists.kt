package com.edgestream.tv.ui

import com.edgestream.tv.data.db.ChannelEntity

/**
 * The Channel Up/Down list for a channel started without a browsed list (number entry, phone,
 * reminder, sports, deep link, launch restore): its taxonomy bucket, as Live TV shows it. A channel
 * hidden by a filter falls back to itself, so Up/Down simply stays put.
 */
fun zapListFor(channel: ChannelEntity, groups: Map<String, List<ChannelEntity>>): List<ChannelEntity> =
    groups.values.firstOrNull { list -> list.any { it.key == channel.key } } ?: listOf(channel)
