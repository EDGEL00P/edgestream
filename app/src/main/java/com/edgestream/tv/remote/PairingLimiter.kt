package com.edgestream.tv.remote

/**
 * Pairing-code guessing limits for the phone remote. Every attempt is counted *before* the code
 * is checked (so parallel requests can't all slip under the limit) and un-counted on success.
 *
 * - [perAddress] wrong codes per address per [windowMs].
 * - [global] wrong codes across all addresses per [windowMs], so many addresses can't share the guessing.
 * - An address that paired within [trustMs] skips the global limit, so a stranger on the LAN can't
 *   lock out the phone that is already paired. An empty (unknown) address is never trusted.
 */
class PairingLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perAddress: Int = 5,
    private val global: Int = 20,
    private val windowMs: Long = 600_000,
    private val trustMs: Long = 86_400_000,
) {
    private class Attempts(var count: Int, var first: Long)

    private val byAddress = HashMap<String, Attempts>()
    private val globalAttempts = ArrayDeque<Long>()
    private val trustedUntil = HashMap<String, Long>()

    /** False when this address must be refused; otherwise counts the attempt and returns true. */
    @Synchronized
    fun tryAttempt(ip: String): Boolean {
        val now = clock()
        prune(now)
        val mine = byAddress[ip]?.takeIf { now - it.first <= windowMs }
        if (mine != null && mine.count >= perAddress) return false
        val trusted = ip.isNotEmpty() && (trustedUntil[ip] ?: 0L) > now
        if (!trusted && globalAttempts.size >= global) return false
        val a = mine ?: Attempts(0, now).also { byAddress[ip] = it }
        a.count++
        globalAttempts.addLast(now)
        return true
    }

    /** The code was right: un-count this attempt and trust the address for [trustMs]. */
    @Synchronized
    fun onSuccess(ip: String) {
        globalAttempts.removeLastOrNull()
        byAddress.remove(ip)
        if (ip.isNotEmpty()) trustedUntil[ip] = clock() + trustMs
    }

    /** "New code": everyone starts fresh. */
    @Synchronized
    fun reset() {
        byAddress.clear()
        globalAttempts.clear()
        trustedUntil.clear()
    }

    private fun prune(now: Long) {
        while (globalAttempts.isNotEmpty() && now - globalAttempts.first() > windowMs) globalAttempts.removeFirst()
        if (byAddress.size > 256) byAddress.entries.removeIf { now - it.value.first > windowMs }
        if (trustedUntil.size > 64) trustedUntil.entries.removeIf { it.value <= now }
    }
}
