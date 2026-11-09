package com.edgestream.tv.remote

import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneChannelsTest {
    private fun ch(key: String, name: String, number: Int, offline: Boolean = false) = ChannelEntity(
        key = key, sourceId = 1, name = name, groupName = "", logo = null,
        streamUrl = "", epgId = null, number = number, tvgId = null, offline = offline,
    )

    private val lineup = listOf(
        ch("c", "Sky Racing 2", 527), ch("a", "Sky Racing 1", 526), ch("b", "BBC One", 101), ch("d", "Sky Racing 3", 528, offline = true),
    )

    @Test fun listIsTheTvLineupInChannelNumberOrderWithoutOfflineChannels() {
        assertEquals(listOf("b", "a", "c"), CompanionServer.phoneChannels(lineup, "").map { it.key })
    }

    @Test fun searchMatchesNamesIgnoringCase() {
        assertEquals(listOf("a", "c"), CompanionServer.phoneChannels(lineup, "  sky racing ").map { it.key })
    }
}
