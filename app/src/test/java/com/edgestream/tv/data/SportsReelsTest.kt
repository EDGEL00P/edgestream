package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsReelsTest {
    @Test fun gameHighlightsOnly() = assertEquals(
        listOf("2" to "Nuggets vs. Pacers: Game Highlights"),
        SportsReels.parseMediaIds("""{"articles":[
            {"id":1,"type":"Media","headline":"Postgame presser"},
            {"id":9,"type":"Story","headline":"A story"},
            {"id":2,"type":"Media","headline":"Nuggets vs. Pacers: Game Highlights"}]}"""),
    )

    @Test fun prefersAdaptiveStream() = assertEquals(
        "https://x/playlist.m3u8",
        SportsReels.parseClipStream("""{"videos":[{"links":{"source":{"href":"https://x/a.mp4","HLS":{"href":"https://x/playlist.m3u8"},"HD":{"href":"https://x/hd.mp4"}}}}]}"""),
    )

    @Test fun fallsBackToMp4() = assertEquals(
        "https://x/hd.mp4",
        SportsReels.parseClipStream("""{"videos":[{"links":{"source":{"HD":{"href":"https://x/hd.mp4"}}}}]}"""),
    )

    @Test fun badReplyHasNoStream() = assertNull(SportsReels.parseClipStream("<html>"))
}
