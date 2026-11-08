package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNumbersTest {
    private fun ch(name: String, group: String = "", number: Int = 9000, epg: String? = null) =
        ChannelEntity(
            key = name, sourceId = 1, name = name, groupName = group, logo = null,
            streamUrl = "", epgId = epg, number = number, tvgId = epg,
        )

    @Test fun ukFreeview() {
        assertEquals(101, ChannelNumbers.lcn(ch("UK| BBC 1 HD", "EU | UK GENERAL ", epg = "bbc1.uk")))
        assertEquals(103, ChannelNumbers.lcn(ch("UK| ITV 1 HD", "EU | UK GENERAL ", epg = "itv1.uk")))
        assertEquals(233, ChannelNumbers.lcn(ch("UK| SKY NEWS HD", "EU | UK NEWS ", epg = "skynews.uk")))
        assertEquals(401, ChannelNumbers.lcn(ch("UK| SKY SPORTS MAIN EVENT HD", "EU | UK SPORT ", epg = "skysportsmain.uk")))
    }

    @Test fun australia() {
        assertEquals(2, ChannelNumbers.lcn(ch("AU| ABC TV HD", "AU | GENERAL ", epg = "abc.au")))
        assertEquals(526, ChannelNumbers.lcn(ch("AU| SKY RACING 1 HD", "AU | SPORT ", number = 7719, epg = "skyracing1.au")))
        assertEquals(508, ChannelNumbers.lcn(ch("AU| ESPN HD", "AU | SPORT ", number = 7301, epg = "espn.au")))
    }

    @Test fun newZealand() {
        assertEquals(1, ChannelNumbers.lcn(ch("NZ| TVNZ 1 HD", "NZ | GENERAL ", epg = "tvnz1.nz")))
        assertEquals(51, ChannelNumbers.lcn(ch("NZ| SKY SPORT 1 HD", "NZ | SPORT ", number = 7401, epg = "skysport1.nz")))
    }

    @Test fun usAndCanada() {
        assertEquals(206, ChannelNumbers.lcn(ch("US| ESPN HD", "AM | USA SPORT ", number = 7201, epg = "espn.us")))
        // ESPN+ is a streaming service: its event slots keep the provider number, never ESPN's 206.
        assertEquals(4123, ChannelNumbers.lcn(ch("US| ESPN+ 005", "AM | USA SPORT ", number = 4123)))
        assertEquals(219, ChannelNumbers.lcn(ch("US| FS1 HD", "AM | USA SPORT ", number = 7202, epg = "fs1.us")))
        assertEquals(400, ChannelNumbers.lcn(ch("CA| TSN1 HD", "AM | CANADA SPORT ", number = 7101, epg = "tsn1.ca")))
    }

    @Test fun unknownKeepsProviderNumber() {
        assertEquals(7719, ChannelNumbers.lcn(ch("Some Local Channel", "General", number = 7719)))
    }
}
