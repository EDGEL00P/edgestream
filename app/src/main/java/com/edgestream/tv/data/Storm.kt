package com.edgestream.tv.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EdgeStream-native AIO storm models (keyless, in-app, no new deps).
 *
 * Honest data contract:
 * - Radar frames remain RainViewer (free) over Esri (standard map default).
 * - Lightning is *derived risk* from Open-Meteo CAPE/LI (via [Trackers.lightning]),
 *   not measured strikes. [LightningStrike] is a derived proximity point for UI
 *   age/colour styling, explicitly not a strike detector.
 * - Hail is a proxy: high reflectivity (>=50 dBZ) + high CAPE (>=1500) + nearby storm.
 *   No free public real-time hail strike API exists.
 * - Alerts: proximity tiers 50/25/10 km first, then hail/heavy-rain proxies.
 */
object Storm {

    /** Derived (non-measured) strike point for pulse/age styling. */
    data class LightningStrike(
        val id: String,
        val lat: Double,
        val lon: Double,
        val timestampMs: Long,
        val peakCurrent: Float? = null, // kA proxy when available, else null
    )

    enum class RiskLevel(val code: Int) { LOW(0), MODERATE(1), HIGH(2), SEVERE(3) }

    data class HailRisk(
        val lat: Double,
        val lon: Double,
        val level: RiskLevel,
        val maxDbz: Int,
        val cape: Double?,
        val reason: String,
    ) {
        val levelLabel: String get() = level.name
    }

    data class StormAlert(val kind: String, val title: String, val detail: String, val km: Int?)

    // ── BOM products (nearest radar, 128 km) ──────────────────────────────
    data class BomProduct(val id: String, val name: String, val lat: Double, val lon: Double)

    private val BOM_PRODUCTS = listOf(
        BomProduct("IDR663", "Brisbane", -27.38, 153.13),
        BomProduct("IDR713", "Sydney", -33.95, 151.21),
        BomProduct("IDR023", "Melbourne", -37.82, 144.85),
        BomProduct("IDR703", "Perth", -31.95, 115.86),
        BomProduct("IDR643", "Adelaide", -34.93, 138.60),
    )

    fun bomProductFor(lat: Double, lon: Double): BomProduct = BOM_PRODUCTS.minByOrNull {
        distanceKm(lat, lon, it.lat, it.lon)
    } ?: BOM_PRODUCTS.first()

    // ── Geometry ──────────────────────────────────────────────────────────
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── Hail proxy ────────────────────────────────────────────────────────
    fun hailRiskFor(
        lat: Double,
        lon: Double,
        maxDbz: Int?,
        cape: Double?,
        lightningLevel: String,
        nearestStormKm: Int?,
    ): HailRisk {
        val dbz = maxDbz ?: 0
        val c = cape ?: Double.NaN
        val capeHigh = !c.isNaN() && c >= 1500
        val capeSevere = !c.isNaN() && c >= 2500
        val lightningHigh = lightningLevel == "High" || lightningLevel == "Severe"
        val near = nearestStormKm != null && nearestStormKm <= 50

        val level = when {
            dbz >= 55 && (capeSevere || lightningLevel == "Severe") -> RiskLevel.SEVERE
            dbz >= 50 && capeHigh && near -> RiskLevel.HIGH
            dbz >= 45 && (capeHigh || lightningHigh) -> RiskLevel.MODERATE
            else -> RiskLevel.LOW
        }
        val reason = when (level) {
            RiskLevel.SEVERE -> "Severe reflectivity ${dbz}dBZ + extreme CAPE"
            RiskLevel.HIGH -> "High reflectivity ${dbz}dBZ + high CAPE + storm within 50 km"
            RiskLevel.MODERATE -> "Elevated reflectivity ${dbz}dBZ with CAPE/lightning support"
            RiskLevel.LOW -> "No hail-supporting combination"
        }
        return HailRisk(lat, lon, level, dbz, cape?.takeIf { !it.isNaN() }, reason)
    }

    // ── Alert evaluation (pure, testable) ─────────────────────────────────
    fun evaluateAlerts(
        userLat: Double,
        userLon: Double,
        stormCells: List<StormCellLike>,
        cape: Double?,
        lightningLevel: String,
        hail: HailRisk,
    ): List<StormAlert> {
        val out = mutableListOf<StormAlert>()
        // Proximity tiers to nearest storm cell (derived lightning presence).
        stormCells.minByOrNull { distanceKm(userLat, userLon, it.lat, it.lon) }?.let { nearest ->
            val km = distanceKm(userLat, userLon, nearest.lat, nearest.lon).toInt()
            val tier = when {
                km <= 10 -> "Imminent"
                km <= 25 -> "Close"
                km <= 50 -> "Approaching"
                else -> null
            }
            if (tier != null && (lightningLevel == "High" || lightningLevel == "Severe" || nearest.maxDbz >= 45)) {
                out += StormAlert(
                    kind = "LIGHTNING",
                    title = "$tier storm ${km} km away",
                    detail = "Reflectivity ${nearest.maxDbz} dBZ · CAPE ${cape?.toInt() ?: 0} · lightning risk $lightningLevel",
                    km = km,
                )
            }
        }
        if (hail.level == RiskLevel.HIGH || hail.level == RiskLevel.SEVERE) {
            out += StormAlert(
                kind = "HAIL",
                title = "Hail risk ${hail.levelLabel.lowercase()} (proxy)",
                detail = hail.reason,
                km = null,
            )
        }
        return out
    }

    /** Minimal cell view so Storm stays decoupled from Radar internals. */
    interface StormCellLike { val lat: Double; val lon: Double; val maxDbz: Int }

}
