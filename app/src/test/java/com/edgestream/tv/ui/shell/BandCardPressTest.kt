package com.edgestream.tv.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards cards that highlight but do nothing on OK. Compose dispatches key events from the focused
 * focus target up its ancestors, so a standalone focusable() placed above a card's clickable node
 * takes focus itself and leaves the clickable (a descendant) deaf to every press.
 */
class BandCardPressTest {
    private fun source(path: String) = File("src/main/java/com/edgestream/tv/$path").readText()

    @Test fun cardPrimitivesKeepTheirClickableAsTheFocusTarget() {
        for (path in listOf("ui/shell/BandCards.kt", "ui/components/Components.kt")) {
            val src = source(path)
            val click = src.indexOf(".combinedClickable(")
            assertTrue("$path: card has no clickable node", click >= 0)
            val focusable = src.indexOf(".focusable(")
            assertTrue("$path: a focusable above the clickable swallows OK", focusable < 0 || focusable > click)
        }
    }

    @Test fun everyBandItemKindHasAnOkAction() {
        val kinds = Regex("""data class (\w+)\([^)]*\) : BandItem""")
            .findAll(source("ui/shell/BandRows.kt")).map { it.groupValues[1] }.toList()
        assertTrue("BandItem kinds not parsed", kinds.size >= 9)
        val shell = source("ui/shell/ShellScreen.kt")
        val start = shell.indexOf("when (item) {")
        assertTrue("band card dispatch missing", start >= 0)
        val dispatch = shell.substring(start, minOf(shell.length, start + 3000))
        for (kind in kinds) assertTrue("BandItem.$kind has no OK action", dispatch.contains("is BandItem.$kind ->"))
    }
}
