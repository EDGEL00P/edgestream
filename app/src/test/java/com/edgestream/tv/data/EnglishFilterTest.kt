package com.edgestream.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishFilterTest {
    @Test fun keepsEnglishCountries() {
        assertTrue(EnglishFilter.isEnglish("US| BRAVO (EAST)", "AM | USA ENTERTAINMENT"))
        assertTrue(EnglishFilter.isEnglish("UK| SKY SPORTS MAIN EVENT", "EU | UK SKY SPORTS"))
        assertTrue(EnglishFilter.isEnglish("AU| FOX SPORTS 501 HD", "AS | AUSTRALIA"))
        assertTrue(EnglishFilter.isEnglish("TSN 1", "AM | CANADA SPORTS/EVENTS"))
        assertTrue(EnglishFilter.isEnglish("Friends 24/7", "24/7 | ENGLISH"))
        assertTrue(EnglishFilter.isEnglish("PPV| UFC 320", "AM | USA PPV-LIVE EVENTS"))
    }

    @Test fun dropsForeignChannels() {
        assertFalse(EnglishFilter.isEnglish("FR| BRAVO HD", "EU | FRANCE"))
        assertFalse(EnglishFilter.isEnglish("FR| TVA", "AM | CANADA FRANÇAIS"))
        assertFalse(EnglishFilter.isEnglish("Canal 5", "AM | MEXICO"))
        assertFalse(EnglishFilter.isEnglish("US| TELEMUNDO", "AM | USA TELEMUNDO"))
        assertFalse(EnglishFilter.isEnglish("1+1", "EU | UKRAINE "))
        assertFalse(EnglishFilter.isEnglish("AR | FIFA WC 26 FINALES", "VIP | FIFA PLUS"))
        assertFalse(EnglishFilter.isEnglish("Star Plus", "EU | UK INDIA CHANNELS"))
    }

    /** iptv-org: genre groups, unlabelled names; the tvg-id carries the country. */
    @Test fun publicListsUseTvgIdCountry() {
        assertTrue(EnglishFilter.isEnglish("2GB (1080p)", "News", "2GB.au@SD"))
        assertTrue(EnglishFilter.isEnglish("BBC News (720p)", "News", "BBCNews.uk@SD"))
        assertTrue(EnglishFilter.isEnglish("CBS News (1080p)", "News", "CBSNews.us"))
        assertFalse(EnglishFilter.isEnglish("TN (720p)", "News", "TodoNoticias.ar@SD"))
        assertFalse(EnglishFilter.isEnglish("TF1 (1080p)", "General", "TF1.fr@SD"))
        assertFalse(EnglishFilter.isEnglish("Unknown (480p)", "General", null))
        // An explicit foreign label still wins over an English tvg-id.
        assertFalse(EnglishFilter.isEnglish("Star Plus", "Hindi", "StarPlus.uk"))
    }
}
