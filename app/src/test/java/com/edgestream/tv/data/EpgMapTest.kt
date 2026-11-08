package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMapTest {
    private val guides = listOf(
        "https://epgshare01.online/epgshare01/epg_US.xml.gz",
        "https://epgshare01.online/epgshare01/epg_UK1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_CA1.xml.gz",
    )
    private val map = EpgMap(
        guides,
        mapOf(
            // Same name in more than one country keeps one entry per country.
            "food network" to listOf(
                EpgMap.Entry(0, "FoodNetwork.us", "US"),
                EpgMap.Entry(1, "FoodNetwork.uk", "UK"),
            ),
            // A country-less entry is the worldwide fallback.
            "mtv live" to listOf(EpgMap.Entry(0, "MTVLive.us", null)),
            // Korea-only channel: never a valid match for an English playlist row.
            "kbs world" to listOf(EpgMap.Entry(0, "KBSWorld.us", "KR")),
            // Entry claims the US but points at a UK file: the URL check must reject it.
            "bad url" to listOf(EpgMap.Entry(1, "BadUrl.us", "US")),
            "logo channel" to listOf(
                EpgMap.Entry(0, "LogoUS.us", "US", "https://cdn.example/logo-us.png"),
                EpgMap.Entry(1, "LogoUK.uk", "UK", "https://cdn.example/logo-uk.png"),
            ),
            "no logo channel" to listOf(EpgMap.Entry(0, "NoLogo.us", "US")),
        ),
    )

    @Test fun countryPick() {
        assertEquals(guides[1] to "FoodNetwork.uk", map.lookup("Food Network", "UK"))
        assertEquals(guides[0] to "FoodNetwork.us", map.lookup("Food Network", "us"))
    }

    @Test fun nameIsCleanedLikeTheGenerator() {
        assertEquals("FoodNetwork.uk", map.lookup("UK: Food Network FHD", "UK")?.second)
        assertEquals("FoodNetwork.uk", map.lookup("Food Network HD [Backup]", "UK")?.second)
    }

    @Test fun countryComparisonIgnoresCase() {
        assertEquals("FoodNetwork.us", map.lookup("Food Network", "US")?.second)
        assertEquals("FoodNetwork.us", map.lookup("Food Network", "us")?.second)
        assertEquals("FoodNetwork.us", map.lookup("Food Network", "Us")?.second)
    }

    @Test fun worldwideEntryMatchesAnyCountryWhenItsGuideAgrees() {
        assertEquals("MTVLive.us", map.lookup("MTV Live", "US")?.second)
        assertEquals("MTVLive.us", map.lookup("MTV Live", null)?.second)
        // The entry has no country, but its guide is American: an AU channel is not sent there.
        assertNull(map.lookup("MTV Live", "AU"))
    }

    @Test fun wrongCountryIsRejected() {
        assertNull(map.lookup("KBS World", "US"))
        // With no country to compare against, the single entry is still usable.
        assertEquals("KBSWorld.us", map.lookup("KBS World")?.second)
    }

    @Test fun guideUrlMustAgreeWithTheEntry() {
        assertNull(map.lookup("Bad URL", "US"))
    }

    @Test fun unknownName() = assertNull(map.lookup("Nowhere TV", "US"))

    @Test fun logoCountryPick() {
        assertEquals("https://cdn.example/logo-uk.png", map.logo("Logo Channel", "UK"))
        assertEquals("https://cdn.example/logo-us.png", map.logo("Logo Channel", "us"))
        assertEquals("https://cdn.example/logo-us.png", map.lookupEntry("Logo Channel", "US")?.third)
    }

    @Test fun oldEntriesHaveNullLogo() {
        // Entries built without a 4th element (legacy 2/3-element asset entries) parse as null.
        assertNull(map.logo("Food Network", "US"))
        assertNull(map.logo("No Logo Channel", "US"))
        assertNull(map.lookupEntry("Food Network", "US")?.third)
    }

    @Test fun keyMatchesGeneratorRules() {
        assertEquals("bbc one", EpgMap.key("UK| BBC One FHD"))
        assertEquals("sky sports 1", EpgMap.key("Sky Sports 1 HD [Backup]"))
        assertEquals("e4 +1", EpgMap.key("E4 +1"))
    }

    @Test fun countryOfGuide() {
        assertEquals("us", EpgMap.countryOfGuide("https://epgshare01.online/epgshare01/epg_US.xml.gz"))
        assertEquals("uk", EpgMap.countryOfGuide("https://epgshare01.online/epgshare01/epg_UK1.xml.gz"))
        assertEquals("ca", EpgMap.countryOfGuide("https://example.test/epg_CA1.xml"))
        assertEquals("au", EpgMap.countryOfGuide("https://i.mjh.nz/au/Sydney/epg.xml.gz"))
        assertNull(EpgMap.countryOfGuide("https://example.test/epg.xml.gz"))
    }
}
