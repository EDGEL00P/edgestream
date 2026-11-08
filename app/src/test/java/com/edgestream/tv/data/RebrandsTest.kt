package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RebrandsTest {
    @Test fun renamesRetiredBrands() {
        assertEquals("TNT Sports 2", Rebrands.current("BT Sport 2"))
        assertEquals("FanDuel Sports Network Detroit", Rebrands.current("Bally Sports Detroit"))
        assertEquals("MS NOW", Rebrands.current("MSNBC"))
        assertEquals("MGM+ Hits", Rebrands.current("Epix Hits"))
        assertEquals("10 Bold", Rebrands.current("One"))
        assertEquals("U&Dave", Rebrands.current("Dave"))
    }

    @Test fun fixesBrandCasing() {
        assertEquals("MeTV 54 Los Angeles", Rebrands.current("Metv 54 Los Angeles"))
        assertEquals("MeTV+", Rebrands.current("Metv+"))
        assertEquals("ION 68 Boston", Rebrands.current("Ion 68 Boston"))
    }

    @Test fun leavesOtherNamesAlone() {
        assertEquals("Dave's Kitchen", Rebrands.current("Dave's Kitchen"))
        assertEquals("BBC One", Rebrands.current("BBC One"))
        assertEquals("Fox Sports 503", Rebrands.current("Fox Sports 503"))
        assertEquals("ESPN", Rebrands.current("ESPN"))
    }

    @Test fun appliedByTheCleaner() {
        assertEquals("TNT Sports 1", NameCleaner().clean("UK: BT Sport 1 FHD").title)
    }
}
