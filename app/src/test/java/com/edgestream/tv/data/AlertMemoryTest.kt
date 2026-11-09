package com.edgestream.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMemoryTest {
    private var stored: String? = null
    private var clock = 1_000_000L
    private fun memory() = AlertMemory({ stored }, { stored = it }, { clock })

    @Test fun lightningIsOneAlertWhateverTheDistance() {
        val m = memory()
        m.dismiss(Trackers.Warning("Close lightning 18 km away", "", "Severe", "Xweather"))
        assertTrue(m.isDismissed(Trackers.Warning("Imminent lightning 7 km away", "", "Severe", "Xweather")))
        clock += AlertMemory.LIGHTNING_MS
        assertFalse(m.isDismissed(Trackers.Warning("Close lightning 12 km away", "", "Severe", "Xweather")))
    }

    @Test fun dismissalSurvivesARestart() {
        val w = Trackers.Warning("Tropical cyclone ALFRED", "Red · 40 km N", "Extreme", "GDACS")
        memory().dismiss(w)
        assertTrue(memory().isDismissed(w))
        clock += AlertMemory.WARNING_MS
        assertFalse(memory().isDismissed(w))
    }

    @Test fun unreadableStoreStartsEmpty() {
        stored = "not json"
        assertFalse(memory().isDismissed(Trackers.Warning("Flood", "", "Extreme", "BOM")))
    }
}
