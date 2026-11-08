package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaServersTest {
    @Test fun serversSurviveSavingAndLoading() {
        val list = listOf(
            MediaServers.Server("jellyfin:abc", MediaServers.Kind.JELLYFIN, "Living Room", "http://192.168.1.20:8096", "u1", "tok1"),
            MediaServers.Server("plex:xyz", MediaServers.Kind.PLEX, "Plex \"Main\"", "https://10-0-0-2.plex.direct:32400", "", "tok2"),
        )
        assertEquals(list, MediaServers.decode(MediaServers.encode(list)))
        assertEquals(emptyList<MediaServers.Server>(), MediaServers.decode(""))
    }
}
