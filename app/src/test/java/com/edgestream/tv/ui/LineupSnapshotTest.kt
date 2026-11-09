package com.edgestream.tv.ui

import com.edgestream.tv.data.CleanName
import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupSnapshotTest {
    private fun ch(key: String, name: String, logo: String? = null, epg: String? = null, identity: String? = null) =
        ChannelEntity(
            key = key, sourceId = 7, name = name, groupName = "AU | SPORT", logo = logo, streamUrl = "http://s/$key",
            epgId = epg, number = 501, catchupDays = 3, catchupSource = "xtream", xtreamStreamId = 12345L,
            offline = false, tvgId = epg, identity = identity,
        )

    @Test fun roundTripsEveryField() {
        val display = listOf(
            ch("a", "Fox Sports 501", logo = "https://l/1.png", epg = "foxsports.au", identity = "FoxLeague.au"),
            ch("b", "Kayo 1", epg = null),
        )
        val badges = mapOf(
            "a" to CleanName("Fox Sports 501", "FHD", listOf("Backup")),
            "b" to CleanName("Kayo 1"),
        )
        val rawNames = mapOf("a" to "AU| FOX SPORTS 501 HD [BK]", "b" to "AU| KAYO 1")
        val text = LineupSnapshot.serialize(display, badges, rawNames)
        val back = LineupSnapshot.deserialize(text)
        assertNotNull(back)
        // ChannelEntity equality covers every field the snapshot carries (id is 0 on both sides).
        assertEquals(display, back!!.display)
        assertEquals(badges, back.badges)
        assertEquals(rawNames, back.rawNames)
        assertTrue(back.at > 0)
    }

    @Test fun unreadableOrWrongVersionIsNull() {
        assertNull(LineupSnapshot.deserialize("not json"))
        assertNull(LineupSnapshot.deserialize(""))
        assertNull(LineupSnapshot.deserialize("v2\n123\n"))
        // An empty display parses (load() is what gates emptiness and age before use).
        assertTrue(LineupSnapshot.deserialize("v1\n123\n")!!.display.isEmpty())
    }
}
