package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ProviderLibraryTest {
    private val lib = ProviderLibrary(Files.createTempDirectory("provider").toFile())

    @Test fun providerNamesAndCatalogueTitlesShareAKey() {
        assertEquals(ProviderLibrary.key("The Batman"), ProviderLibrary.key("EN| The Batman (2022)"))
        assertEquals(ProviderLibrary.key("Fast & Furious"), ProviderLibrary.key("Fast and Furious"))
    }

    @Test fun m3uEpisodeNamesSplitIntoShowSeasonEpisode() {
        assertEquals(Triple("Fire Country", 5, 1), ProviderLibrary.episodeOf("Fire Country S05E01 Hot Hot Heat"))
        assertNull(ProviderLibrary.episodeOf("The Batman (2022)"))
    }

    @Test fun lookupPrefersTheSameYearThenOneEitherSide() {
        lib.write(
            1, "movie",
            sequenceOf(
                ProviderLibrary.Hit(ProviderLibrary.key("Dune"), "1984", "http://p/dune84.mkv"),
                ProviderLibrary.Hit(ProviderLibrary.key("Dune"), "2021", "http://p/dune21.mkv"),
            ),
        )
        assertEquals("http://p/dune21.mkv", lib.find(1, "movie", "Dune", "2021")?.ref)
        assertEquals("http://p/dune21.mkv", lib.find(1, "movie", "Dune", "2020")?.ref)
        assertNull(lib.find(1, "movie", "Dune", "2000"))
        assertNull(lib.find(1, "movie", "Arrival", "2016"))
    }

    @Test fun episodesAreFoundBySeasonAndNumber() {
        lib.write(2, "episode", sequenceOf(ProviderLibrary.Hit(ProviderLibrary.key("Bluey") + ProviderLibrary.episodeSuffix(3, 4), null, "http://p/b.mkv")))
        assertEquals("http://p/b.mkv", lib.find(2, "episode", "Bluey", null, ProviderLibrary.episodeSuffix(3, 4))?.ref)
        assertNull(lib.find(2, "episode", "Bluey", null, ProviderLibrary.episodeSuffix(3, 5)))
    }
}
