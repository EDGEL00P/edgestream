package com.edgestream.tv.remote

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone link's envelope: AES-256-GCM, written as base64(iv ‖ ciphertext ‖ tag). The phone
 * page implements the same thing in plain JavaScript (browsers withhold WebCrypto from http
 * pages), so this format is the contract between the two; see assets/companion.html.
 *
 * The key never crosses the network: the TV shows it inside the QR code's #fragment, which a
 * browser keeps to itself.
 */
object LinkCipher {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun seal(key: ByteArray, plain: String, iv: ByteArray = ByteArray(IV_BYTES).also(random::nextBytes)): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Null when the envelope is malformed, sealed with another key, or altered on the way. */
    fun open(key: ByteArray, sealed: String): String? {
        val bytes = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull() ?: return null
        if (bytes.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(c.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    /**
     * A sealed request body from the phone: its JSON, or null unless it was sealed with [key],
     * names [path] (so it can't be redirected to another endpoint) and is fresh and unseen.
     */
    fun openRequest(key: ByteArray, envelope: String, path: String, guard: ReplayGuard): org.json.JSONObject? {
        val inner = open(key, envelope)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return null
        if (inner.optString("path") != path) return null
        // The IV leads the envelope: unique per request, so it identifies a replay.
        return inner.takeIf { guard.accept(envelope.take(16), it.optLong("ts")) }
    }

    fun keyToText(key: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    fun keyFromText(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()?.takeIf { it.size == 32 }
}

/**
 * Refuses a sealed request seen before or stamped too far from now, so a recorded request can't
 * be played back later. Phone and TV clocks both follow network time; five minutes covers drift.
 */
class ReplayGuard(private val windowMs: Long = 300_000, private val clock: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun accept(id: String, stampedAt: Long): Boolean {
        val now = clock()
        if (kotlin.math.abs(now - stampedAt) > windowMs) return false
        seen.entries.removeIf { now - it.value > windowMs }
        if (id in seen) return false
        seen[id] = now
        return true
    }
}
