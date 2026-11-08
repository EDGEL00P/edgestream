package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebridParseTest {
    @Test fun film() {
        val p = Debrid.parse("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265.mkv")
        assertEquals("Dune Part Two", p.title); assertEquals("2024", p.year); assertNull(p.season)
    }

    @Test fun filmWithYearInTitle() {
        assertEquals("Blade Runner 2049" to "2017", Debrid.parse("Blade.Runner.2049.2017.1080p.BluRay.x264.mkv").let { it.title to it.year })
        assertEquals("2001 A Space Odyssey" to "1968", Debrid.parse("2001.A.Space.Odyssey.1968.REMASTERED.mkv").let { it.title to it.year })
    }

    @Test fun filmInBrackets() {
        val p = Debrid.parse("The Matrix (1999) [1080p] [YTS.MX].mp4")
        assertEquals("The Matrix", p.title); assertEquals("1999", p.year)
    }

    @Test fun episode() {
        val p = Debrid.parse("Breaking.Bad.S02E05.Breakage.1080p.BluRay.x264.mkv")
        assertEquals("Breaking Bad", p.title); assertEquals(2, p.season); assertEquals(5, p.episode)
    }

    @Test fun episodeInSeasonPack() {
        val p = Debrid.parse("S01E03.mkv", "Severance.S01.2160p.ATVP.WEB-DL")
        assertEquals("Severance", p.title); assertEquals(1, p.season); assertEquals(3, p.episode)
    }

    @Test fun episodeAltStyle() {
        val p = Debrid.parse("The Office 3x07 Branch Wars.avi")
        assertEquals("The Office", p.title); assertEquals(3, p.season); assertEquals(7, p.episode)
    }

    @Test fun episodeWithYear() {
        assertEquals("Doctor Who", Debrid.parse("Doctor.Who.2005.S01E01.Rose.mkv").title)
    }
}
