package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class ChannelIdentityTest {
    private val catalogue = ChannelIdentities.parse(
        StringReader(
            """{"channels":[
            ["FoxLeague.au","Fox League",[],"AU",["sports"],"https://l/fl.png",null],
            ["ComedyCentral.us","Comedy Central",[],"US",["entertainment"],null,"Paramount"],
            ["CoziTV.us","Cozi TV",["COZI"],"US",["classic"],null,null],
            ["WVTMDT.us","WVTM-DT",[],"US",["general"],null,"NBC"],
            ["WVTMDT2.us","WVTM-DT2",[],"US",["general"],null,null],
            ["SkySportsF1.uk","Sky Sports F1",[],"UK",["sports"],null,null],
            ["BeINSports2.us","beIN Sports 2",[],"US",["sports"],null,null],
            ["FoxSports.us","Fox Sports",[],"US",["sports"],null,null],
            ["FoxSportsNet.us","Fox Sports",[],"US",["sports"],null,null],
            ["TinyPop.uk","Tiny Pop",[],"UK",["kids"],null,null],
            ["Quest.uk","Quest",[],"UK",["documentary"],null,null],
            ["Quest.us","Quest",[],"US",["documentary"],null,null],
            ["Alibi.uk","Alibi",[],"UK",["entertainment"],null,null]
            ]}""",
        ),
    )

    private fun id(name: String, tvgId: String? = null, group: String? = null) = catalogue.resolve(name, tvgId, group)?.id

    @Test fun providerNamesResolveAcrossFormats() {
        assertEquals("FoxLeague.au", id("AU| FOX LEAGUE HD"))
        assertEquals("FoxLeague.au", id("AU: Fox League FHD"))
        assertEquals("ComedyCentral.us", id("US| COMEDY CENTRAL (WEST)"))
        assertEquals("CoziTV.us", id("US| COZI HD"))
        assertEquals("SkySportsF1.uk", id("UK| SKY SPORTS F1 ᴴᴰ"))
        assertEquals("BeINSports2.us", id("US| BEIN SPORTS 02"))
    }

    @Test fun playlistsWithoutCountryPrefixesStillResolve() {
        // A name unique across the catalogue needs no country; a tag or the group supplies one.
        assertEquals("TinyPop.uk", id("Tiny Pop (1080p)"))
        assertEquals("Quest.uk", id("Quest (United Kingdom)"))
        assertEquals("Quest.us", id("QUEST HD", group = "AM | USA ENTERTAINMENT"))
        assertNull(id("Quest"))
        assertEquals("Alibi.uk", id("UK| ALIBI +1 HD"))
    }

    @Test fun callSignFindsTheMainStationNotASubchannel() {
        assertEquals("WVTMDT.us", id("US| NBC 13 (WVTM) BIRMINGHAM"))
        assertEquals("WVTMDT.us", id("NBC WVTM-DT (720p)"))
    }

    @Test fun localStationIsItsNetworkWithTheStationAsFeed() {
        val local = ChannelIdentities.parse(
            StringReader(
                """{"channels":[["ABC.us","ABC",[],"US",["general"],"https://l/abc.png",null],["PBS.us","PBS",[],"US",[],null,null],
                ["NBC.us","NBC",[],"US",[],null,null],["AntennaTV.us","Antenna TV",[],"US",[],null,null]],
                "feeds":[["PBS.us","WNEPTV","WNEP-TV",[]],["NBC.us","WTLV","WTLV",[]],["AntennaTV.us","WTLV","WTLV",[]]]}""",
            ),
        )
        // The station's own network word picks among its subchannels.
        assertEquals("NBC.us" to "WTLV", local.resolve("US| NBC 12 (WTLV) JACKSONVILLE", null)?.let { it.id to it.feed })
        // The catalogue files WNEP under PBS; the provider says ABC, and the provider is right.
        assertEquals("ABC.us" to "WNEP", local.resolve("US| ABC 16 (WNEP) WILKES BARRE", null)?.let { it.id to it.feed })
    }

    @Test fun catalogueIdInTvgIdWinsOverTheName() {
        assertEquals("FoxLeague.au", id("Some Odd Name", "FoxLeague.au@SD"))
    }

    @Test fun ambiguousOrCountrylessNamesStayUnresolved() {
        assertNull(id("US| FOX SPORTS"))
        // The same name in another country is a different channel.
        assertNull(id("UK| FOX LEAGUE"))
    }

    private fun ch(name: String, group: String) = com.edgestream.tv.data.db.ChannelEntity(
        key = name, sourceId = 0, name = name, groupName = group, logo = null, streamUrl = "", epgId = null, number = 0,
    )

    /**
     * Coverage on real lineups: EDGE_LINEUPS=a.tsv;b.tsv (name<TAB>tvg-id<TAB>group per row),
     * EDGE_CATALOGUE=path to channel_catalog.json. Skipped when unset.
     */
    @Test fun coverageReport() {
        val lineups = System.getenv("EDGE_LINEUPS")
        val path = System.getenv("EDGE_CATALOGUE")
        assumeTrue(lineups != null && path != null)
        val real = ChannelIdentities.parse(File(path!!).reader())
        for (file in lineups!!.split(';')) {
            val rows = File(file).readLines().map { it.split('\t').let { p -> Triple(p[0], p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2)) } }
            // English real channels only: event slots and looping movie channels have no identity.
            val english = rows.filter { (n, t, g) ->
                (EpgMatcher.countryOf(n) ?: EpgMatcher.countryOf(t))?.let(ChannelIdentities::iso) in setOf("US", "UK", "CA", "AU", "NZ", "IE") &&
                    !NameListings.isLoop(ch(n, g.orEmpty())) && '[' !in n
            }
            fun report(label: String, withIds: Boolean) {
                val hits = english.map { (n, t, g) -> n to real.resolve(n, if (withIds) t else null, g) }
                val found = hits.count { it.second != null }
                println("$label ${File(file).name}: ${found}/${english.size} resolved, ${hits.count { it.second?.logo != null }} with logos")
                hits.filter { it.second != null }.shuffled(java.util.Random(7)).take(25).forEach { println("   ${it.first}  ->  ${it.second!!.id}") }
                println("   unresolved e.g.: " + hits.filter { it.second == null }.shuffled(java.util.Random(7)).take(25).joinToString(" | ") { it.first })
            }
            report("with tvg-ids", true)
            report("names only", false)
        }
    }
}
