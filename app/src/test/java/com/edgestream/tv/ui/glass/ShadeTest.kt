package com.edgestream.tv.ui.glass

import androidx.compose.ui.graphics.Color
import com.edgestream.tv.ui.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeTest {

    /** Worst case for light text: the photo behind the shade is pure white. */
    private fun onWhitePhoto(shade: Float, textAlpha: Float): List<Pair<String, Double>> = Palettes.all.map { p ->
        val bg = composite(Color.White, p.bg, shade)
        val text = composite(bg, p.text, textAlpha)
        p.id to contrastRatio(text, bg)
    }

    @Test fun primaryTextReadableWhereverTextSits() {
        val weak = onWhitePhoto(SHADE_TEXT, 1f).filter { it.second < 7.0 }
        assertTrue("below 7:1 → $weak", weak.isEmpty())
    }

    @Test fun secondaryTextReadableWhereverTextSits() {
        val weak = onWhitePhoto(SHADE_TEXT, SECONDARY_TEXT_ALPHA).filter { it.second < 4.5 }
        assertTrue("below 4.5:1 → $weak", weak.isEmpty())
    }

    @Test fun deeperShadesNeverWeaker() = assertTrue(SHADE_STRIP >= SHADE_TEXT && SHADE_SECTION >= SHADE_TEXT)

    @Test fun contrastOfBlackOnWhiteIs21() = assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
}
