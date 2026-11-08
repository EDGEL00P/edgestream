package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarTest {
    @Test
    fun rangeAndRingsStayConsistent() {
        assertEquals(35, Radar.rangeKm(10))
        assertEquals(70, Radar.rangeKm(9))
        assertEquals(140, Radar.rangeKm(8))
        assertEquals(280, Radar.rangeKm(7))
        assertEquals(listOf(10, 20, 35), Radar.rings(10))
        assertEquals(35, Radar.rings(10).last())
        assertEquals(280, Radar.rings(7).last())
        assertEquals("35 KM", Radar.zoomLabel(10))
    }

    @Test
    fun nexradColoursMapToReflectivity() {
        assertEquals(5, Radar.reflectivity(0xFF00ECEC.toInt()))
        assertEquals(15, Radar.reflectivity(0xFF00A000.toInt()))
        assertEquals(30, Radar.reflectivity(0xFFFFFF00.toInt()))
        assertEquals(45, Radar.reflectivity(0xFFFF0000.toInt()))
        assertEquals(55, Radar.reflectivity(0xFFC00000.toInt()))
        assertEquals(60, Radar.reflectivity(0xFFFF00FF.toInt()))
        assertEquals(65, Radar.reflectivity(0xFF9955C9.toInt()))
    }

    @Test
    fun transparentAndUiColoursAreNotRain() {
        assertEquals(0, Radar.reflectivity(0x00000000))
        assertEquals(0, Radar.reflectivity(0xFFFFFFFF.toInt()))
        assertEquals(0, Radar.reflectivity(0xFF202020.toInt()))
    }
}
