package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelTaxonomyTest {
    private fun channel(name: String, group: String = "", tvgId: String? = null, epgId: String? = null) =
        ChannelEntity(
            id = 0, key = name, sourceId = 0, name = name, groupName = group,
            logo = null, streamUrl = "http://local/$name", epgId = epgId, number = 1, tvgId = tvgId,
        )

    @Test
    fun classifiesNflByCountry() {
        assertEquals("USA · NFL", ChannelTaxonomy.groupKey(channel("NFL Network", "Sports", "nflnetwork.us")))
    }

    @Test
    fun classifiesNrlByCountry() {
        assertEquals("Australia · NRL", ChannelTaxonomy.groupKey(channel("AU| NRL", "Sports", "nrl.au")))
    }

    @Test
    fun classifiesPremierLeagueByCountry() {
        assertEquals(
            "UK · Soccer",
            ChannelTaxonomy.groupKey(channel("Sky Sports Premier League", "Sports", "skysportspremierleague.uk")),
        )
    }

    @Test
    fun classifiesF1ByCountry() {
        assertEquals("UK · Motorsport", ChannelTaxonomy.groupKey(channel("Sky Sports F1", "Sports", "skysportsf1.uk")))
    }

    @Test
    fun classifiesNewsByCountry() {
        assertEquals("UK · News", ChannelTaxonomy.groupKey(channel("Sky News", "News", "skynews.uk")))
    }

    @Test
    fun classifiesKidsWithoutCountry() {
        assertEquals("International · Kids", ChannelTaxonomy.groupKey(channel("Cartoon Network", "Kids")))
    }

    @Test
    fun fallsBackToProviderGroup() {
        assertEquals("International · Other", ChannelTaxonomy.groupKey(channel("Some Channel", "Provider Group")))
    }

    @Test
    fun readsCountryFromProviderGroupName() {
        assertEquals("USA · Entertainment", ChannelTaxonomy.groupKey(channel("Some Channel", "AM | USA GENERAL")))
        assertEquals("Canada · Entertainment", ChannelTaxonomy.groupKey(channel("Some Channel", "AM | CANADA GENERAL")))
        assertEquals("UK · Entertainment", ChannelTaxonomy.groupKey(channel("Some Channel", "EU | UK GENERAL")))
    }

    @Test
    fun mergesProviderTypos() {
        assertEquals("UK · Entertainment", ChannelTaxonomy.groupKey(channel("Some Channel", "EU | UK ENTERTAIMENT")))
        assertEquals("UK · Entertainment", ChannelTaxonomy.groupKey(channel("Some Channel", "EU | UK ENTERTAINMENT")))
    }

    @Test
    fun classifiesEntertainmentByCountry() {
        assertEquals("UK · Entertainment", ChannelTaxonomy.groupKey(channel("BBC One", "Entertainment", "bbcone.uk")))
    }

    @Test
    fun eventSlotsShareOneLiveEventsGroup() {
        assertEquals("USA · Live Events", ChannelTaxonomy.groupKey(channel("US| ESPN+ 001 [Elche CF vs. Real Betis (2026-11-08 07:50:00)]", "AM | USA ESPN PLUS")))
        assertEquals("Canada · Live Events", ChannelTaxonomy.groupKey(channel("CA| PPV 12", "AM | CANADA LIVE EVENT-PPV")))
    }

    @Test
    fun networkAffiliatesAreLocalChannels() {
        assertEquals("USA · Local Channels", ChannelTaxonomy.groupKey(channel("US| NBC CHICAGO NEWS", "AM | USA NBC")))
        assertEquals("Australia · Local Channels", ChannelTaxonomy.groupKey(channel("AU| SEVEN SYDNEY", "AS | AUSTRALIA")))
    }

    @Test
    fun providerBrandsAreNotGroups() {
        assertEquals("USA · Movies", ChannelTaxonomy.groupKey(channel("US| CINEMANIA HOLLYWOOD PREMIER 1 FHD", "AM | USA CINEMANIA")))
        assertEquals("USA · Other", ChannelTaxonomy.groupKey(channel("US| 3ABN HD", "AM | USA STREAMED CHANNELS")))
    }

    @Test
    fun foldsSmallGroupsAndCountries() {
        assertEquals("Ireland", ChannelTaxonomy.fold("Ireland · Sports", 12, 32))
        assertEquals("USA · Sports", ChannelTaxonomy.fold("USA · Golf", 5, 3000))
        assertEquals("USA · Other", ChannelTaxonomy.fold("USA · Lifestyle", 4, 3000))
        assertEquals("USA · NFL", ChannelTaxonomy.fold("USA · NFL", 49, 3000))
    }

    @Test
    fun groupAllKeepsEveryChannelOnce() {
        val chans = (1..12).map { channel("US| NFL Network $it", "AM | USA NFL", "nfl$it.us") } +
            (1..3).map { channel("NZ| TVNZ $it", "NZ | GENERAL", "tvnz$it.nz") }
        val groups = ChannelTaxonomy.groupAll(chans)
        assertEquals(15, groups.values.sumOf { it.size })
        assertEquals(setOf("USA", "New Zealand"), groups.keys.map { it.substringBefore(" · ") }.toSet())
    }
}
