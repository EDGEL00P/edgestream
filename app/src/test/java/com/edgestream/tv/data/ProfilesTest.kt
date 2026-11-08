package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    @Test fun pinStoredHashedAndChecked() {
        val p = Profile("kid1", "Sam", ProfileCodec.hash("kid1", "1234"))
        assertFalse("never the raw PIN", p.pinHash!!.contains("1234"))
        assertTrue(ProfileCodec.pinMatches(p, "1234"))
        assertFalse(ProfileCodec.pinMatches(p, "4321"))
        assertNotEquals("salted per profile", ProfileCodec.hash("kid1", "1234"), ProfileCodec.hash("kid2", "1234"))
    }

    @Test fun unlockedProfileNeedsNoPin() = assertTrue(ProfileCodec.pinMatches(Profile("a", "A"), ""))

    @Test fun profilesRoundTrip() {
        val list = listOf(Profile("main", "Me"), Profile("k", "Sam", ProfileCodec.hash("k", "0000")))
        assertEquals(list, ProfileCodec.decode(ProfileCodec.encode(list)))
        assertEquals(emptyList<Profile>(), ProfileCodec.decode("{bad"))
    }

    @Test fun mainProfileKeepsExistingStorageNames() {
        assertEquals("calendar.json", ProfileCodec.scoped("calendar.json", "main"))
        assertEquals("sportsFollows_k", ProfileCodec.scoped("sportsFollows", "k"))
    }

    @Test fun watchListToggleAndRoundTrip() {
        val a = WatchItem("imdb:tt0918940", "The Legend of Tarzan", "2016", "p", movie = true, imdbId = "tt0918940")
        val b = WatchItem("6:M:1", "Junk", null, null, movie = true, vodKey = "6:M:1")
        val list = WatchListCodec.toggle(WatchListCodec.toggle(emptyList(), a), b)
        assertEquals(listOf(b, a), list)
        assertEquals(listOf(a), WatchListCodec.toggle(list, b))
        assertEquals(list, WatchListCodec.decode(WatchListCodec.encode(list)))
    }
}
