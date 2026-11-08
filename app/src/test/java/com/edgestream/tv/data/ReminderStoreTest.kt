package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReminderStoreTest {
    private val now = System.currentTimeMillis()
    private fun r(startIn: Long) = Reminder("ch1", "Channel 1", "Show", now + startIn, now + startIn + 3_600_000)

    @Test fun toggleSetsThenCancels() {
        val store = ReminderStore(File.createTempFile("rem", ".json"))
        assertTrue(store.toggle(r(600_000)))
        assertTrue(store.has("ch1", now + 600_000))
        assertFalse(store.toggle(r(600_000)))
        assertTrue(store.all.value.isEmpty())
    }

    @Test fun dueOnlyOnceStarted() {
        val store = ReminderStore(File.createTempFile("rem", ".json"))
        store.toggle(r(600_000))
        assertTrue(store.due(now).isEmpty())
        assertEquals(1, store.due(now + 600_000).size)
        // Long after it started it is no longer announced.
        assertTrue(store.due(now + 600_000 + 20 * 60_000).isEmpty())
    }

    @Test fun survivesRestart() {
        val file = File.createTempFile("rem", ".json")
        ReminderStore(file).toggle(r(600_000))
        assertEquals(1, ReminderStore(file).all.value.size)
    }
}
