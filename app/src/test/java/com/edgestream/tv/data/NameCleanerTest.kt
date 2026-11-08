package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameCleanerTest {
    private val cleaner = NameCleaner()

    private fun check(raw: String, title: String, quality: String? = null, tags: List<String> = emptyList()) {
        val c = cleaner.clean(raw)
        assertEquals("title of '$raw'", title, c.title)
        assertEquals("quality of '$raw'", quality, c.quality)
        assertEquals("tags of '$raw'", tags, c.tags)
    }

    @Test fun iptvOrgStyle() = check("3Cat Exclusiu 1 (1080p) [Geo-blocked]", "3Cat Exclusiu 1", "FHD", listOf("Geo-blocked"))
    @Test fun notTwentyFourSeven() = check("Pluto TV Movies (720p) [Not 24/7]", "Pluto TV Movies", "HD", listOf("Not 24/7"))
    @Test fun countryColonAndSuffix() = check("UK: BBC One FHD", "BBC One", "FHD")
    @Test fun pipeCountryAndSymbol() = check("|US| CNN HD ◉", "CNN", "HD")
    @Test fun countryPipe() = check("DE | Das Erste HD", "Das Erste", "HD")
    @Test fun languageDash() = check("EN - The Matrix (1999) [4K]", "The Matrix (1999)", "4K")
    @Test fun stackedSuffixes() = check("Sky Sports Main Event UHD HEVC", "Sky Sports Main Event", "4K")
    @Test fun twoLetterCountryInBrackets() = check("BBC Two (UK)", "BBC Two")

    // Things that must be left alone
    @Test fun keepsGluedLetters() = check("Bloomberg TVHD", "Bloomberg TVHD")
    @Test fun keepsHyphenatedNames() = check("TV-5 Monde", "TV-5 Monde")
    @Test fun keepsThreeLetterBrackets() = check("Granada (ITV)", "Granada", tags = listOf("ITV"))
    @Test fun keepsPlainNames() = check("Discovery Channel", "Discovery Channel")
    @Test fun neverEmpty() = check("HD", "HD")

    @Test fun customRegex() {
        val c = NameCleaner("""\s*\bbackup\b""").clean("ESPN backup")
        assertEquals("ESPN", c.title)
    }

    @Test fun invalidCustomRegexIgnored() {
        val n = NameCleaner("([unclosed")
        assertNull(n.custom)
        assertEquals("ESPN", n.clean("ESPN").title)
    }

    @Test fun stripsDecorativeSymbols() {
        assertEquals("GENERAL", NameCleaner.tidy("✦●✦ GENERAL ✦●✦"))
        assertEquals("Hulu Originals 5", NameCleaner().clean("US| HULU ORIGINALS 5").title)
        assertEquals("ESPN 2", NameCleaner().clean("US| ESPN 2 HD").title)
        assertEquals("ABC 07 Albuquerque-Santa Fe", NameCleaner().clean("US| ABC 07 (KOAT) ALBUQUERQUE-SANTA FE ").title)
        assertEquals("BBC One", NameCleaner().clean("UK: BBC One FHD").title)
        assertEquals("ESPN+ 004", NameCleaner().clean("US| ESPN+ 004 [Soccer: Eldense vs. Granada (2026-11-01 07:50:00)]").title)
        assertEquals("Batman 1966", NameCleaner.tidyTitle("24/7-EN - Batman 1966"))
        assertEquals("Gold Rush", NameCleaner.tidyTitle("EN| Gold Rush"))
        // A release year tacked on with a dash or in brackets belongs in the details, not the title.
        assertEquals("The History of Concrete", NameCleaner.tidyTitle("The History of Concrete - 2026"))
        assertEquals("Altered Carbon: Resleeved", NameCleaner.tidyTitle("Altered Carbon: Resleeved (2020)"))
        assertEquals("Blade Runner 2049", NameCleaner.tidyTitle("Blade Runner 2049"))
        assertEquals("NBC", NameCleaner.tidyGroup("AM | USA NBC"))
        assertEquals("Comedy", NameCleaner.tidyGroup("[EN] COMEDY"))
        assertEquals("Sky Sports", NameCleaner.tidyGroup("EU | UK SKY SPORTS"))
        assertEquals("Live Event-PPV", NameCleaner.tidyGroup("AM | CANADA LIVE EVENT-PPV"))
        assertEquals("24/7", NameCleaner.tidyGroup("24/7 | ENGLISH"))
        assertEquals("Cartoons", NameCleaner.tidyGroup("AM | CARTOONS"))
        assertEquals("Tom & Jerry's", NameCleaner.tidy("Tom & Jerry's 🎬"))
    }

    @Test fun episodeTitles() {
        assertEquals("Hot Hot Heat", NameCleaner.episodeTitle("EN| Fire Country S05E01 - Hot Hot Heat", "EN| Fire Country"))
        assertEquals("The Ride", NameCleaner.episodeTitle("Fire Country 5x02: The Ride", "Fire Country"))
        assertEquals("Pilot", NameCleaner.episodeTitle("Pilot", "Fire Country"))
        assertEquals(null, NameCleaner.episodeTitle("EN| Fire Country S05E03", "EN| Fire Country"))
        assertEquals(null, NameCleaner.episodeTitle("Episode 4", "Fire Country"))
    }
}
