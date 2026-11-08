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
            "UK · Premier League",
            ChannelTaxonomy.groupKey(channel("Sky Sports Premier League", "Sports", "skysportspremierleague.uk")),
        )
    }

    @Test
    fun classifiesF1ByCountry() {
        assertEquals("UK · F1", ChannelTaxonomy.groupKey(channel("Sky Sports F1", "Sports", "skysportsf1.uk")))
    }

    @Test
    fun classifiesNewsByCountry() {
        assertEquals("UK · News", ChannelTaxonomy.groupKey(channel("Sky News", "News", "skynews.uk")))
    }

    @Test
    fun classifiesKidsWithoutCountry() {
        assertEquals("Other · Kids", ChannelTaxonomy.groupKey(channel("Cartoon Network", "Kids")))
    }

    @Test
    fun fallsBackToProviderGroup() {
        assertEquals("Other · Provider Group", ChannelTaxonomy.groupKey(channel("Some Channel", "Provider Group")))
    }

    @Test
    fun readsCountryFromProviderGroupName() {
        assertEquals("USA · General", ChannelTaxonomy.groupKey(channel("Some Channel", "AM | USA GENERAL")))
        assertEquals("Canada · General", ChannelTaxonomy.groupKey(channel("Some Channel", "AM | CANADA GENERAL")))
        assertEquals("UK · General", ChannelTaxonomy.groupKey(channel("Some Channel", "EU | UK GENERAL")))
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
}
