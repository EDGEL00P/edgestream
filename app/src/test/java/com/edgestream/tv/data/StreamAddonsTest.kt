package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAddonsTest {
    @Test fun torrentioCachedAndUncached() {
        assertEquals(true, StreamAddons.cachedFlag("[TB+] Torrentio\n4k"))
        assertEquals(false, StreamAddons.cachedFlag("[TB download] Torrentio\n1080p"))
    }

    @Test fun cometAndAioMarkers() {
        assertEquals(true, StreamAddons.cachedFlag("⚡ TorBox 2160p"))
        assertEquals(false, StreamAddons.cachedFlag("⏳ TorBox 1080p"))
        assertEquals(false, StreamAddons.cachedFlag("TorBox uncached"))
        assertEquals(true, StreamAddons.cachedFlag("TorBox Cached"))
        assertEquals(null, StreamAddons.cachedFlag("MediaFusion 1080p"))
    }

    @Test fun resolutionAndSize() {
        assertEquals(2160, StreamAddons.resolution("Movie.2024.2160p.WEB-DL.DV"))
        assertEquals(2160, StreamAddons.resolution("TorBox 4K HDR"))
        assertEquals(1080, StreamAddons.resolution("Show.S01E02.1080p.WEB"))
        assertEquals(720, StreamAddons.resolution("720p"))
        assertEquals(0, StreamAddons.resolution("Movie.2024.WEB"))
        assertEquals((14.2 * (1L shl 30)).toLong(), StreamAddons.size("💾 14.2 GB 👤 120"))
        assertEquals(700L shl 20, StreamAddons.size("700 MB"))
    }

    @Test fun baseUrl() {
        assertEquals("https://comet.example/abc", StreamAddons.base("https://comet.example/abc/manifest.json"))
        assertEquals("https://comet.example/abc", StreamAddons.base("stremio://comet.example/abc/manifest.json"))
        assertEquals("https://comet.example/abc", StreamAddons.base("https://comet.example/abc/configure"))
    }
}
