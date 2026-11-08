package com.edgestream.tv.remote

/**
 * The phone-remote pairing code, read (and decrypted) once and then served from memory. Creation
 * and reset run under one lock, so the QR code and the server can never hold different codes.
 */
class PairingCode(
    private val load: () -> String?,
    private val store: (String) -> Unit,
    private val clear: () -> Unit,
    private val generate: () -> String,
) {
    @Volatile private var cached: String? = null
    private val lock = Any()

    fun get(): String {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val code = load()?.takeIf { it.isNotBlank() } ?: generate().also(store)
            cached = code
            return code
        }
    }

    fun reset(): String = synchronized(lock) {
        clear()
        val code = generate()
        store(code)
        cached = code
        code
    }
}
