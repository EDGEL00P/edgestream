package com.edgestream.tv.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class PairingCodeTest {

    @Test fun concurrentFirstGet_generatesOnce() {
        val generated = AtomicInteger()
        val code = PairingCode(load = { null }, store = {}, clear = {}, generate = { generated.incrementAndGet(); "123456" })
        val gate = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..16).map { Thread { gate.await(); results.add(code.get()) }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, generated.get())
        assertEquals(16, results.size)
        assertEquals(setOf("123456"), results.toSet())
    }

    @Test fun get_loadsOnce() {
        var loads = 0
        val code = PairingCode(load = { loads++; "654321" }, store = {}, clear = {}, generate = { "x" })
        code.get(); code.get()
        assertEquals(1, loads)
    }

    @Test fun reset_returnsNewCodeAndStores() {
        val codes = ArrayDeque(listOf("111111", "222222"))
        var stored: String? = null
        var clears = 0
        val code = PairingCode(load = { null }, store = { stored = it }, clear = { clears++ }, generate = { codes.removeFirst() })
        assertEquals("111111", code.get())
        assertEquals("222222", code.reset())
        assertEquals("222222", code.get())
        assertEquals("222222", stored)
        assertEquals(1, clears)
    }
}
