package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NameListingsTest {
    private val now = ZonedDateTime.of(2026, 11, 1, 6, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private fun ch(name: String, group: String = "AM | USA ESPN PLUS") =
        ChannelEntity(key = "6:L:1", sourceId = 6, name = name, groupName = group, logo = null, streamUrl = "", epgId = null, tvgId = null, number = 1)

    @Test fun datedEventBecomesProgrammeAtEasternTime() {
        val list = NameListings.programmes(ch("US| ESPN+ 004 [Soccer: Eldense vs. Granada (2026-11-01 07:50:00)]"), 6, "x", now)!!
        val event = list.first { it.title == "Soccer: Eldense vs. Granada" }
        assertEquals(ZonedDateTime.of(2026, 11, 1, 7, 50, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli(), event.start)
        assertTrue(list.first().title.startsWith("Up next:"))
    }

    @Test fun liveTagAndDuplicateBracketsAreTidied() {
        val list = NameListings.programmes(
            ch("US| FLO SPORTS 001 [live: Brisbane Heat vs Sydney Thunder _ Women's Cricket (Heat vs Thunder) (2026-11-01 00:40:00)]", "AM | USA FLO"), 6, "x", now,
        )!!
        assertTrue(list.any { it.title == "Brisbane Heat vs Sydney Thunder · Women's Cricket" })
    }

    @Test fun idleSlotsSayNothingIsOn() {
        assertEquals("No event scheduled", NameListings.programmes(ch("CA| DAZN 08 [EVENT ONLY]", "AM | CANADA LIVE EVENT-PPV"), 6, "x", now)!!.single().title)
        assertEquals("No event scheduled", NameListings.programmes(ch("CA| SN+ 040 [(2098-12-31 08:00:15)]", "AM | CANADA LIVE EVENT-PPV"), 6, "x", now)!!.single().title)
    }

    @Test fun loopsNameTheShow() {
        assertEquals("Batman 1966", NameListings.showName("24/7-EN - BATMAN 1966"))
        assertEquals("X-Men '97", NameListings.showName("24/7-EN| X-Men '97"))
        assertEquals("Spidey", NameListings.showName("US| PLAY KIDZ SPIDEY HD"))
    }

    @Test fun ordinaryChannelsAreLeftToTheGuide() {
        assertNull(NameListings.programmes(ch("UK| BBC 1 HD", "EU | UK GENERAL "), 6, "x", now))
        assertTrue(NameListings.isHeader("✦●✦ GENERAL HD✦●✦"))
    }

    @Test fun mapKeyMatchesTheOfflineTool() {
        assertEquals("abc 07 (koat) albuquerque-santa fe", EpgMap.key("US| ABC 07 (KOAT) ALBUQUERQUE-SANTA FE "))
        assertEquals("bbc 1", EpgMap.key("UK| BBC 1 [BK]"))
    }
}
