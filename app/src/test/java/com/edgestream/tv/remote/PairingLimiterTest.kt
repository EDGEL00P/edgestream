package com.edgestream.tv.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class PairingLimiterTest {
    private var now = 1_000_000L
    private val limiter = PairingLimiter(clock = { now })

    private fun exhaustGlobal(prefix: String = "a") = repeat(20) { assertTrue(limiter.tryAttempt("$prefix$it")) }

    @Test fun perAddress_fiveThenRefused() {
        repeat(5) { assertTrue(limiter.tryAttempt("a")) }
        assertFalse(limiter.tryAttempt("a"))
    }

    @Test fun global_twentyAcrossAddresses() {
        exhaustGlobal()
        assertFalse(limiter.tryAttempt("b"))
    }

    @Test fun trusted_bypassesGlobal() {
        assertTrue(limiter.tryAttempt("p")); limiter.onSuccess("p")
        exhaustGlobal()
        assertTrue(limiter.tryAttempt("p"))
    }

    @Test fun trustExpires() {
        assertTrue(limiter.tryAttempt("p")); limiter.onSuccess("p")
        now += 86_400_000L + 1
        exhaustGlobal()
        assertFalse(limiter.tryAttempt("p"))
    }

    @Test fun windowResets() {
        repeat(5) { limiter.tryAttempt("a") }
        now += 600_000L + 1
        assertTrue(limiter.tryAttempt("a"))
    }

    @Test fun reset_clearsEverything() {
        exhaustGlobal()
        limiter.reset()
        assertTrue(limiter.tryAttempt("b"))
    }

    @Test fun emptyIp_countsGlobally_neverTrusted() {
        repeat(5) { assertTrue(limiter.tryAttempt("")) }
        repeat(15) { assertTrue(limiter.tryAttempt("o$it")) }
        assertFalse(limiter.tryAttempt("x"))
        limiter.onSuccess("") // un-counts one attempt but must not trust the unknown address
        assertTrue(limiter.tryAttempt("y"))
        assertFalse(limiter.tryAttempt(""))
    }

    @Test fun concurrentAttempts_countedExactly() {
        val allowed = AtomicInteger()
        val gate = CountDownLatch(1)
        val threads = (1..8).map { Thread { gate.await(); if (limiter.tryAttempt("c")) allowed.incrementAndGet() }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(5, allowed.get())
    }
}
