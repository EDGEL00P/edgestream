package com.edgestream.tv.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkCipherTest {
    /** McGrew & Viega GCM test case 14 (AES-256, zero key/IV, one zero block); the phone page checks the same vector. */
    @Test fun matchesPublishedVector() {
        val sealed = LinkCipher.seal(ByteArray(32), "\u0000".repeat(16), iv = ByteArray(12))
        val bytes = Base64.getDecoder().decode(sealed)
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
            bytes.copyOfRange(12, bytes.size).joinToString("") { "%02x".format(it) })
    }

    @Test fun roundTripsAndRefusesTampering() {
        val key = LinkCipher.newKey()
        val sealed = LinkCipher.seal(key, "pässwörd €1")
        assertEquals("pässwörd €1", LinkCipher.open(key, sealed))
        assertNull(LinkCipher.open(LinkCipher.newKey(), sealed))
        val bytes = Base64.getDecoder().decode(sealed).also { it[14] = (it[14].toInt() xor 1).toByte() }
        assertNull(LinkCipher.open(key, Base64.getEncoder().encodeToString(bytes)))
        assertNull(LinkCipher.open(key, "not base64!"))
        assertEquals(key.toList(), LinkCipher.keyFromText(LinkCipher.keyToText(key))!!.toList())
    }

    @Test fun replayGuard() {
        var now = 1_000_000L
        val guard = ReplayGuard(windowMs = 300_000) { now }
        assertTrue(guard.accept("a", now))
        assertFalse(guard.accept("a", now))           // played back
        assertFalse(guard.accept("b", now - 400_000)) // too old
        now += 400_000
        assertTrue(guard.accept("c", now))
    }

    /** Produced by the phone page's own JavaScript cipher (assets/companion.html), exactly as its api() sends a field fill. */
    @Test fun opensWhatThePhonePageSeals() {
        val key = LinkCipher.keyFromText("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")!!
        val envelope = "5XqCvL8U208YhFkchqmED+Fkl9JT3XnkN/fNdM/fpCrJdyvmTvZp9D8CM+QtgEi7dsTYs/Qm4QkGiI/C3dGSQ8MwdlDY1ykGqkzNBXtFPc4hefAx3c6BKDzM+bdtxQ=="
        val sentAt = 1_800_000_000_000L
        val guard = ReplayGuard { sentAt + 1_000 }
        assertNull(LinkCipher.openRequest(key, envelope, "/api/source", guard)) // redirected to another endpoint
        assertEquals("pässwörd $€!", LinkCipher.openRequest(key, envelope, "/api/field", guard)?.getString("text"))
        assertNull(LinkCipher.openRequest(key, envelope, "/api/field", guard))  // replayed
        assertNull(LinkCipher.openRequest(key, envelope, "/api/field", ReplayGuard { sentAt + 600_000 })) // stale
    }
}
