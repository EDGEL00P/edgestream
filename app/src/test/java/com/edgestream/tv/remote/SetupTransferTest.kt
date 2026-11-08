package com.edgestream.tv.remote

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTransferTest {
    @After fun reset() = SetupReceiver.stop()

    private fun code() = (SetupReceiver.status.value as SetupReceiver.Status.Waiting).code

    /** What the sending TV does: its own key pair, the receiver's public key, the typed code. */
    private fun sealFor(receiverPublic: String, code: String, payload: String): Pair<String, String> {
        val pair = SetupTransfer.newKeyPair()
        val mine = SetupTransfer.publicText(pair)
        return mine to LinkCipher.seal(SetupTransfer.deriveKey(pair.private, mine, receiverPublic, code), payload)
    }

    @Test fun bothTvsDeriveTheSameKeyOnlyWithTheSameCode() {
        val a = SetupTransfer.newKeyPair(); val b = SetupTransfer.newKeyPair()
        val pa = SetupTransfer.publicText(a); val pb = SetupTransfer.publicText(b)
        assertArrayEquals(SetupTransfer.deriveKey(a.private, pa, pb, "482913"), SetupTransfer.deriveKey(b.private, pb, pa, "482913"))
        assertFalse(SetupTransfer.deriveKey(a.private, pa, pb, "482913").contentEquals(SetupTransfer.deriveKey(b.private, pb, pa, "482914")))
    }

    @Test fun theRightCodeOpensTheSetupOnce() {
        SetupReceiver.start()
        val (pub, sealed) = sealFor(SetupReceiver.hello()!!, code(), """{"v":1}""")
        assertEquals("""{"v":1}""", SetupReceiver.open(pub, sealed))
        assertEquals(SetupReceiver.Status.Importing, SetupReceiver.status.value)
        // The session ends with the first success: a replay finds nothing waiting.
        assertNull(SetupReceiver.open(pub, sealed))
    }

    @Test fun fiveWrongCodesEndReceiving() {
        SetupReceiver.start()
        val receiver = SetupReceiver.hello()!!
        val wrong = if (code() == "111111") "222222" else "111111"
        repeat(4) { val (pub, sealed) = sealFor(receiver, wrong, "x"); assertNull(SetupReceiver.open(pub, sealed)) }
        assertTrue(SetupReceiver.status.value is SetupReceiver.Status.Waiting)
        val (pub, sealed) = sealFor(receiver, wrong, "x")
        assertNull(SetupReceiver.open(pub, sealed))
        assertTrue(SetupReceiver.status.value is SetupReceiver.Status.Failed)
        assertNull(SetupReceiver.hello())
    }

    @Test fun codesAreSixDigitsWithoutALeadingZero() {
        repeat(200) { val c = SetupTransfer.newCode(); assertEquals(6, c.length); assertTrue(c[0] != '0') }
    }
}
