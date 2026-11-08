package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VodDedupeTest {
    private fun d(vararg t: Pair<String, String?>) = dedupeTitles(t.toList(), { it.first }, { it.second })

    @Test fun yearlessCopyDroppedWhenDatedExists() =
        assertEquals(listOf("Tarzan" to "1999"), d("Tarzan" to null, "Tarzan" to "1999", "TARZAN" to "1999"))

    @Test fun differentYearsAreDifferentFilms() =
        assertEquals(listOf("Tarzan" to "1999", "Tarzan" to "2013"), d("Tarzan" to "1999", "Tarzan" to "2013"))

    @Test fun yearlessKeptWhenNoDatedCopy() =
        assertEquals(listOf("Junk" to null), d("Junk" to null, "Junk" to null))
}
