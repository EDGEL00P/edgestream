package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StreamBadgesTest {
    /** Shape of leonevz/Elite-Badges/main/badges.json, trimmed to two groups. */
    private val elite = """
        {
          "groups": [{"id": "resolution"}, {"id": "audio-tech"}],
          "filters": [
            {"id":"4k","groupId":"resolution","name":"4K","pattern":"(?i)\\b(4k|2160p|uhd)\\b","imageURL":"https://x/4k.png","isEnabled":true,"type":"filter"},
            {"id":"atmos","groupId":"audio-tech","name":"Atmos","pattern":"(?i)\\batmos\\b","imageURL":"https://x/atmos.png","isEnabled":true,"type":"filter"},
            {"id":"off","groupId":"audio-tech","name":"Off","pattern":"(?i)\\boff\\b","imageURL":"https://x/off.png","isEnabled":false,"type":"filter"}
          ]
        }
    """.trimIndent()

    @Test fun eliteShapeParsesInGroupOrder() {
        val b = StreamBadges.parse(elite)
        assertEquals(listOf("4K", "Atmos"), b.match("Movie 2160p ATMOS off").map { it.name })
        assertTrue(b.match("nothing here").isEmpty())
    }

    @Test fun describeTurnsRealTracksIntoMatchableText() {
        val b = StreamBadges.parse(elite)
        val text = StreamBadges.describe("1920×1080 · 24fps · AVC", 2160, listOf("English · 5.1 · EAC3"))
        assertTrue(text.contains("2160p"))
        assertEquals(listOf("4K"), b.match(text).map { it.name })
    }

    @Test fun malformedUpdateNeverOverwritesTheGoodFile() {
        val (badges, commit) = StreamBadges.resolve(elite, "{not json")
        assertNull(commit)
        assertEquals(2, badges.match("2160p ATMOS").size)
    }

    @Test fun validUpdateIsCommitted() {
        val updated = elite.replace("https://x/4k.png", "https://x/4k-v2.png")
        val (badges, commit) = StreamBadges.resolve(elite, updated)
        assertEquals(updated, commit)
        assertTrue(badges.match("2160p").single().image.endsWith("4k-v2.png"))
    }

    @Test fun noUpdateKeepsTheCachedSet() {
        val (badges, commit) = StreamBadges.resolve(elite, null)
        assertNull(commit)
        assertEquals(2, badges.match("2160p ATMOS").size)
    }

    @Test fun aFileWithNoUsableFiltersIsNotCached() {
        assertNull(StreamBadges.resolve(null, """{"groups":[],"filters":[]}""").second)
    }

    /** The real Elite set uses negative lookahead to keep DTS-HD MA from also showing DTS-HD. */
    @Test fun lookaheadPatternsCompileOnTheJvm() {
        val raw = """{"groups":[{"id":"audio-tech"}],"filters":[
            {"id":"dts-hd","groupId":"audio-tech","name":"DTS-HD","pattern":"(?i)\\b(dts[\\s._-]*hd|dtshd)(?![\\s._-]*(ma|master)|ma)\\b","imageURL":"https://x/dtshd.png","isEnabled":true,"type":"filter"},
            {"id":"dts-hd-ma","groupId":"audio-tech","name":"DTS-HD MA","pattern":"(?i)\\b(dts[\\s._-]*hd[\\s._-]*ma|dts[\\s._-]*hd[\\s._-]*master)\\b","imageURL":"https://x/dtsma.png","isEnabled":true,"type":"filter"}
        ]}""".trimIndent()
        val b = StreamBadges.parse(raw)
        assertEquals("DTS-HD MA", b.match("Movie DTS-HD MA").single().name)
        assertEquals("DTS-HD", b.match("Movie DTS-HD").single().name)
    }

    @Test fun freshCacheIsUsedWithoutANetworkCall() {
        kotlinx.coroutines.runBlocking {
            val dir = Files.createTempDirectory("badges").toFile()
            val file = File(dir, StreamBadges.CACHE_FILE)
            file.writeText(elite)
            file.setLastModified(System.currentTimeMillis())
            val b = StreamBadges.load(dir)
            assertEquals(listOf("4K", "Atmos"), b.match("2160p ATMOS").map { it.name })
            dir.deleteRecursively()
        }
    }
}
