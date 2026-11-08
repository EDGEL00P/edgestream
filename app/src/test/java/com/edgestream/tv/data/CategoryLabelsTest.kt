package com.edgestream.tv.data

import com.edgestream.tv.data.CategoryLabels.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryLabelsTest {
    private fun label(raw: String) = CategoryLabels.label(raw)

    @Test fun separatorsAndCasing() {
        assertEquals("Action & War", label("Action/WAR").name)
        assertEquals("Crime & Mystery", label("Crime/Mystery").name)
        assertEquals("Comedy", label("COMEDY MOVIES").name)
        assertEquals("Horror", label("EN - Horror").name)
    }

    @Test fun platformsBecomeCollections() {
        assertEquals(CategoryLabels.Label("Prime Video", Kind.COLLECTION), label("Amazon Prime+"))
        assertEquals(CategoryLabels.Label("Netflix", Kind.COLLECTION), label("NETFLIX MOVIES"))
        assertEquals(CategoryLabels.Label("Disney+", Kind.COLLECTION), label("Disney Plus"))
        assertEquals(CategoryLabels.Label("Max", Kind.COLLECTION), label("HBO Max"))
        assertEquals(CategoryLabels.Label("Apple TV+", Kind.COLLECTION), label("Apple TV+ Series"))
    }

    @Test fun commonGenreNames() {
        assertEquals("Sci-Fi", label("Science Fiction").name)
        assertEquals("Sci-Fi", label("SCI FI").name)
        assertEquals("Documentary", label("Documentaries").name)
        assertEquals("Kids & Family", label("Kids").name)
        assertEquals("4K Ultra HD", label("4K UHD").name)
        assertEquals("3D", label("3D Movies").name)
        assertEquals(Kind.GENRE, label("Anime").kind)
    }

    @Test fun duplicatesMergeIntoOneTile() {
        val merged = CategoryLabels.merge(listOf("Action" to 10, "ACTION MOVIES" to 5, "EN - Comedy" to 3, "Netflix" to 7))
        assertEquals(listOf("Action", "Comedy"), merged.filter { it.label.kind == Kind.GENRE }.map { it.label.name })
        val action = merged.first { it.label.name == "Action" }
        assertEquals(15, action.count)
        assertEquals(setOf("Action", "ACTION MOVIES"), action.raws.toSet())
    }
}
