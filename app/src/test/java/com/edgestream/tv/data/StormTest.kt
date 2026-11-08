package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StormTest {

    @Test
    fun bomProduct_picksNearest() {
        val p = Storm.bomProductFor(-27.5, 153.0)
        assertEquals("IDR663", p.id)
    }

    @Test
    fun hailRisk_severeWhenExtremeReflectivityAndCape() {
        val risk = Storm.hailRiskFor(0.0, 0.0, maxDbz = 60, cape = 3000.0, lightningLevel = "Severe", nearestStormKm = 20)
        assertEquals(Storm.RiskLevel.SEVERE, risk.level)
    }

    @Test
    fun hailRisk_highWhen50DbzPlusCapeAndNearby() {
        val risk = Storm.hailRiskFor(0.0, 0.0, maxDbz = 52, cape = 1800.0, lightningLevel = "High", nearestStormKm = 30)
        assertEquals(Storm.RiskLevel.HIGH, risk.level)
    }

    @Test
    fun hailRisk_lowWhenNoEcho() {
        val risk = Storm.hailRiskFor(0.0, 0.0, maxDbz = 0, cape = 100.0, lightningLevel = "Low", nearestStormKm = null)
        assertEquals(Storm.RiskLevel.LOW, risk.level)
    }

    @Test
    fun distanceKm_bananaToSydneyApprox() {
        val km = Storm.distanceKm(-27.47, 153.02, -33.87, 151.21)
        assertTrue("expected ~700km, got $km", km in 650.0..800.0)
    }

    @Test
    fun evaluateAlerts_flagsCloseStorm() {
        val cell = object : Storm.StormCellLike {
            override val lat = 0.05; override val lon = 0.0; override val maxDbz = 55
        }
        val hail = Storm.hailRiskFor(0.0, 0.0, 55, 2000.0, "High", 5)
        val alerts = Storm.evaluateAlerts(0.0, 0.0, listOf(cell), 2000.0, "High", hail)
        assertTrue(alerts.any { it.kind == "LIGHTNING" })
        assertTrue(alerts.any { it.kind == "HAIL" })
    }
}
