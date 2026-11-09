package com.edgestream.tv.player

import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDelayProcessorTest {
    private val format = AudioFormat(48_000, 2, 2) // 48 kHz stereo 16-bit → 192 bytes per 1 ms

    private fun frame(bytes: Int): ByteBuffer = ByteBuffer.allocate(bytes).apply {
        while (hasRemaining()) put((position() % 251).toByte())
        flip()
    }

    /** What the sink could read right now (each queueInput produces a fresh output batch). */
    private fun available(p: AudioDelayProcessor): Int = p.output.remaining()

    @Test fun holdsBackTheDelayThenFlowsThrough() {
        val p = AudioDelayProcessor().apply { delayMs = 100 } // 19_200 bytes
        p.configure(format)
        assertTrue(p.isActive())
        // 50 ms arrives: all held, nothing out.
        p.queueInput(frame(192 * 50))
        assertEquals(0, available(p))
        // Another 100 ms: 150 ms buffered, only the 50 ms past the delay may pass.
        p.queueInput(frame(192 * 100))
        assertEquals(192 * 50, available(p))
        // End of stream: everything still held flows out (the held 100 ms of audio).
        p.queueEndOfStream()
        assertEquals(192 * 100, available(p))
    }

    /** Driven the way the sink drives it: the pipeline asks isEnded() before every queueInput. */
    @Test fun audioFlowsThroughTheRealPipeline() {
        val p = AudioDelayProcessor().apply { delayMs = 100 }
        val pipeline = AudioProcessingPipeline(ImmutableList.of(p))
        pipeline.configure(format)
        pipeline.flush()
        var out = 0
        repeat(15) { // 150 ms in 10 ms chunks
            val chunk = frame(192 * 10)
            pipeline.queueInput(chunk)
            assertFalse("pipeline must consume the input", chunk.hasRemaining())
            out += pipeline.output.remaining().also { pipeline.output.position(pipeline.output.limit()) }
        }
        assertEquals(192 * 50, out)
        assertFalse(pipeline.isEnded)
        pipeline.queueEndOfStream()
        out += pipeline.output.remaining().also { pipeline.output.position(pipeline.output.limit()) }
        assertEquals(192 * 150, out)
        assertTrue(pipeline.isEnded)
    }

    @Test fun zeroDelayIsActiveNothing() {
        val p = AudioDelayProcessor().apply { delayMs = 0 }
        p.configure(format)
        assertFalse(p.isActive())
    }

    @Test fun flushDropsHeldAudio() {
        val p = AudioDelayProcessor().apply { delayMs = 100 }
        p.configure(format)
        p.queueInput(frame(192 * 10))
        p.flush()
        p.queueInput(frame(192 * 60))
        // After the flush the counters start over: 60 ms is still under the 100 ms delay.
        assertEquals(0, available(p))
        p.queueEndOfStream()
        assertEquals(192 * 60, available(p))
    }
}
