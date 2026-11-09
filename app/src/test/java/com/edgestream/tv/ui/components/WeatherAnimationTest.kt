package com.edgestream.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WeatherAnimationTest {
    @Test fun everyWmoCodeHasABundledIcon() {
        val raw = File("src/main/res/raw").list().orEmpty().toSet()
        for (code in (0..99)) for (night in listOf(false, true)) {
            val file = "meteo_" + weatherAnimation(code, night).replace('-', '_') + ".json"
            assertTrue("$code night=$night -> $file missing", file in raw)
        }
    }

    @Test fun nightPicksTheMoon() {
        assertEquals("clear-night", weatherAnimation(0, night = true))
        assertEquals("clear-day", weatherAnimation(0, night = false))
        assertEquals("hail", weatherAnimation(99, night = false))
    }
}
