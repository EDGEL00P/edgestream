package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDedupeTest {
    private fun channel(
        name: String,
        key: String = name,
        group: String = "USA Sports",
        number: Int = 1,
        offline: Boolean = false,
    ) = ChannelEntity(
        key = key,
        sourceId = 1,
        name = name,
        groupName = group,
        logo = null,
        streamUrl = "",
        epgId = null,
        number = number,
        offline = offline,
    )

    @Test
    fun removesQualityAndBackupCopies() {
        val channels = listOf(
            channel("ESPN 1 HD", number = 1),
            channel("ESPN 1 FHD [BK]", number = 2),
            channel("ESPN 1 4K", number = 3),
            channel("ESPN 2 HD", number = 4),
        )
        val result = ChannelDedupe.apply(channels, emptySet())
        assertEquals(2, result.size)
        assertEquals("ESPN 1 4K", result[0].name)
        assertEquals("ESPN 2 HD", result[1].name)
    }

    @Test
    fun favouriteCopyIsAlwaysKept() {
        val backup = channel("ESPN 1 HD [BK]", key = "backup", number = 9)
        val channels = listOf(channel("ESPN 1 HD", key = "main", number = 1), backup)
        val result = ChannelDedupe.apply(channels, setOf("backup"))
        assertEquals(listOf("backup"), result.map { it.key })
    }

    @Test
    fun onlineCopyBeatsOfflineCopy() {
        val channels = listOf(
            channel("Sky Sports F1 HD", key = "offline", offline = true),
            channel("Sky Sports F1", key = "online"),
        )
        val result = ChannelDedupe.apply(channels, emptySet())
        assertEquals(listOf("online"), result.map { it.key })
    }

    @Test
    fun sameNameInDifferentCountriesSurvives() {
        val channels = listOf(
            channel("Fox Sports", key = "us", group = "USA Sports"),
            channel("Fox Sports", key = "uk", group = "UK Sports"),
        )
        val result = ChannelDedupe.apply(channels, emptySet())
        assertEquals(2, result.size)
        assertTrue(result.any { it.key == "us" } && result.any { it.key == "uk" })
    }
}
