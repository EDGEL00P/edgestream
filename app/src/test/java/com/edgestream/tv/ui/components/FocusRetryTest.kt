package com.edgestream.tv.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRetryTest {

    @Test fun stopsAtFirstSuccess() = runBlocking {
        var calls = 0
        assertTrue(retryUntil(tries = 12, gapMs = 1) { ++calls == 3 })
        assertEquals(3, calls)
    }

    @Test fun givesUpAfterTries() = runBlocking {
        var calls = 0
        assertFalse(retryUntil(tries = 4, gapMs = 1) { calls++; false })
        assertEquals(4, calls)
    }

    @Test fun firstTrySuccess_oneCall() = runBlocking {
        var calls = 0
        assertTrue(retryUntil(tries = 12, gapMs = 1) { calls++; true })
        assertEquals(1, calls)
    }
}
