package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMatcherTest {
    private val guide = mapOf(
        "mjh-gem-nsw" to listOf("9Gem"),
        "mjh-nine-syd" to listOf("Nine", "Channel 9"),
        "mjh-abc-me" to listOf("ABC Entertains"),
        "BBCOne.uk" to listOf("BBC One"),
        "SkySports1.uk" to listOf("Sky Sports 1"),
        "SkySports2.uk" to listOf("Sky Sports 2"),
        "E4.uk" to listOf("E4"),
        "E4Plus1.uk" to listOf("E4 +1"),
        "ABC.au" to listOf("ABC"),
        "ABC.us" to listOf("ABC"),
        "FoxSportsNews.au" to listOf("Fox Sports News"),
        "SevenNSW" to listOf("Seven Sydney"),
        "SevenVIC" to listOf("Seven Melbourne"),
        "American.Heroes.Channel.HD.us2" to listOf("American Heroes Channel HD"),
        "AMC.Canada.HD.ca2" to listOf("AMC Canada HD"),
        "AMC.us2" to listOf("AMC"),
    )
    private val m = EpgMatcher(guide)

    private fun id(tvgId: String?, name: String) = m.match(tvgId, name)?.guideId

    @Test fun exactId() = assertEquals("BBCOne.uk", id("BBCOne.uk", "whatever"))
    @Test fun idIgnoringCaseAndFeed() = assertEquals("FoxSportsNews.au", id("foxsportsnews.au@SD", "Fox Sports News (1080p)"))
    @Test fun nameWithDecorations() = assertEquals("mjh-gem-nsw", id("9Gem.au@Sydney", "9Gem (720p) [Geo-blocked]"))
    @Test fun prefixAndQuality() = assertEquals("BBCOne.uk", id(null, "UK: BBC One FHD"))
    @Test fun looseDropsNetworkAndCity() = assertEquals("mjh-nine-syd", id(null, "Nine Network Sydney"))
    @Test fun numbersMatter() = assertEquals("SkySports2.uk", id(null, "Sky Sports 2 HD"))
    @Test fun plusOneIsItsOwnChannel() = assertEquals("E4Plus1.uk", id(null, "E4 +1"))
    @Test fun plainE4() = assertEquals("E4.uk", id(null, "E4 HD"))
    @Test fun countryBreaksTie() = assertEquals("ABC.au", id("ABCTV.au", "ABC (1080p)"))
    @Test fun countryFromNamePrefix() = assertEquals("ABC.us", id(null, "US: ABC"))
    @Test fun ambiguousWithoutCountryIsRejected() = assertNull(id(null, "ABC"))
    @Test fun ambiguousLooseIsRejected() = assertNull(id(null, "Seven"))
    @Test fun unrelatedStaysUnmatched() = assertNull(id("Ausbiz.au", "Ausbiz TV"))

    // "US| AHC FHD" carries the provider's wrong tvg-id "amc.us". The channel name is AHC, so the
    // acronym index must win over the id's base name and never accept AMC Canada.
    @Test fun acronymBeatsWrongTvgId() = assertEquals("American.Heroes.Channel.HD.us2", id("amc.us", "US| AHC FHD"))
    @Test fun acronymFromNamePrefix() = assertEquals("American.Heroes.Channel.HD.us2", id(null, "US| AHC HD"))
    @Test fun nameBeatsExactButWrongId() {
        val g = EpgMatcher(
            mapOf(
                "amc.us" to listOf("AMC"),
                "American.Heroes.Channel.HD.us2" to listOf("American Heroes Channel HD"),
            ),
        )
        assertEquals("American.Heroes.Channel.HD.us2", g.match("amc.us", "US| AHC FHD")?.guideId)
    }
    @Test fun loneForeignCandidateIsRejected() {
        val onlyCanada = EpgMatcher(mapOf("AMC.Canada.HD.ca2" to listOf("AMC Canada HD", "AMC")))
        assertNull(onlyCanada.match("amc.us", "US| AHC FHD")?.guideId)
    }
    @Test fun countryFromEpgshareSuffix() {
        assertEquals("us", EpgMatcher.countryOf("American.Heroes.Channel.HD.us2"))
        assertEquals("ca", EpgMatcher.countryOf("AMC.Canada.HD.ca2"))
        assertEquals("us", EpgMatcher.countryOf("KAMC-DT.us_locals1"))
        assertEquals("au", EpgMatcher.countryOf("9Gem.au@Sydney"))
    }
    @Test fun qualitySuffixIsNotACountry() = assertNull(EpgMatcher.countryOf("Some.Channel.HD"))

    @Test fun regionFromName() = assertEquals("WA", EpgMatcher.regionOf("ABC TV WA (720p)", "ABCTV.au@WA"))
    @Test fun regionFromFeed() = assertEquals("NSW", EpgMatcher.regionOf("9Gem (720p)", "9Gem.au@Sydney"))
    @Test fun noRegion() = assertNull(EpgMatcher.regionOf("ABC Kids", "ABCKids.au"))
    @Test fun regionStrippedForMatching() =
        assertEquals("mjh-abc-me", EpgMatcher(mapOf("mjh-abc-me" to listOf("ABC Entertains"))).match(null, EpgMatcher.withoutRegion("ABC Entertains QLD"))?.guideId)
}
