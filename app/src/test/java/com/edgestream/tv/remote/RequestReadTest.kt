package com.edgestream.tv.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class RequestReadTest {

    @Test fun readsNormalRequest() {
        val raw = "GET /api/state HTTP/1.1\r\nX-Edge-Token: 1\r\n\r\n".toByteArray()
        val req = readRequest(ByteArrayInputStream(raw))!!
        assertEquals("GET", req.method)
        assertEquals("/api/state", req.path)
        assertEquals("1", req.headers["x-edge-token"])
    }

    @Test fun eachReadBoundedByRemainingBudget() {
        var now = 0L
        val timeouts = mutableListOf<Int>()
        val drip = object : InputStream() {
            override fun read(): Int { now += 1_000; return 'A'.code }
        }
        readRequest(drip, clock = { now }, budgetMs = 5_000, setTimeout = { timeouts += it })
        assertTrue("timeouts $timeouts", timeouts.isNotEmpty() && timeouts.all { it in 1..5_000 })
        assertTrue("shrinks: $timeouts", timeouts.first() == 5_000 && timeouts.last() <= 1_000)
    }

    @Test fun slowDrip_abortsAfterBudget() {
        var now = 0L
        var bytes = 0
        val drip = object : InputStream() {
            override fun read(): Int { bytes++; now += 1_000; return 'A'.code }
        }
        assertNull(readRequest(drip, clock = { now }, budgetMs = 5_000))
        assertTrue("read $bytes bytes", bytes <= 6)
    }
}
