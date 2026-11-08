package com.edgestream.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTuningTest {
    @Test fun lowLiveSettingNoLongerStarvesFilms() {
        val p = bufferPlan(500)
        assertEquals("keeps loading 30 s ahead, not 0.5 s", 30_000, p.minMs)
        assertEquals(500, p.startMs)
        assertEquals(2_500, p.rebufferMs)
        assertTrue(p.maxMs >= p.minMs)
    }

    @Test fun planStaysOrdered() {
        listOf(0, 500, 2_000, 30_000, 60_000, 90_000).map(::bufferPlan).forEach { p ->
            assertTrue("$p", p.startMs <= p.minMs && p.rebufferMs <= p.minMs && p.minMs <= p.maxMs)
        }
    }

    @Test fun bufferBudgetFollowsTheHeap() {
        assertEquals("SHIELD large heap (512 MB) → 160 MB cap", 160 shl 20, bufferBudgetBytes(512L shl 20))
        assertEquals("192 MB heap → ~67 MB", (192L * 1024 * 1024 * 0.35).toInt(), bufferBudgetBytes(192L shl 20))
        assertEquals("tiny heap floor", 32 shl 20, bufferBudgetBytes(48L shl 20))
    }

    @Test fun tunnellingOnlyForLive() {
        assertTrue(tunnelFor(setting = true, isLive = true))
        assertFalse(tunnelFor(setting = true, isLive = false))
        assertFalse(tunnelFor(setting = false, isLive = true))
    }

    @Test fun hungAudioOrTimeoutIsRecoverableButBadFormatIsNot() {
        assertTrue(recoverableOnDemand(PlaybackException.ERROR_CODE_TIMEOUT))
        assertTrue(recoverableOnDemand(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertFalse(recoverableOnDemand(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }
}
