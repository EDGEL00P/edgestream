package com.edgestream.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HttpRetryTest {

    @Test
    fun withRetry_succeedsOnFirstAttempt() = runBlocking {
        var calls = 0
        val result = Http.withRetry(attempts = 3, initialDelayMs = 1) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun withRetry_retriesTransientFailures() = runBlocking {
        var calls = 0
        val result = Http.withRetry(attempts = 3, initialDelayMs = 1, maxDelayMs = 2) {
            calls++
            if (calls < 3) throw IOException("timeout")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun withRetry_doesNotRetryClientErrors() = runBlocking {
        var calls = 0
        try {
            Http.withRetry(attempts = 3, initialDelayMs = 1) {
                calls++
                throw HttpStatusException(404, "HTTP 404 for https://example.test")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().startsWith("HTTP 4"))
        }
        assertEquals(1, calls)
    }

    @Test
    fun withRetry_throwsAfterExhaustingAttempts() = runBlocking {
        var calls = 0
        try {
            Http.withRetry(attempts = 3, initialDelayMs = 1, maxDelayMs = 2) {
                calls++
                throw IOException("boom")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
        assertEquals(3, calls)
    }

    private fun callsFor(error: () -> Throwable): Int = runBlocking {
        var calls = 0
        runCatching { Http.withRetry(attempts = 3, initialDelayMs = 1, maxDelayMs = 2) { calls++; throw error() } }
        calls
    }

    @Test
    fun status503_retried() = runBlocking {
        var calls = 0
        val result = Http.withRetry(attempts = 3, initialDelayMs = 1, maxDelayMs = 2) {
            calls++
            if (calls < 3) throw HttpStatusException(503, "HTTP 503")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun ssl_notRetried() = assertEquals(1, callsFor { javax.net.ssl.SSLHandshakeException("bad cert") })

    @Test
    fun cleartextBlocked_notRetried() = assertEquals(1, callsFor { java.net.UnknownServiceException("CLEARTEXT not permitted") })
}
