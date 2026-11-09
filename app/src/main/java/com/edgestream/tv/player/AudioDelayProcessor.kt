package com.edgestream.tv.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer

/**
 * Delays the audio by a fixed number of milliseconds, for lip-sync correction on receivers and
 * soundbars where the picture lags the sound. Pure PCM buffer: everything received is held back
 * until [delayMs] worth of bytes have arrived behind it, then it flows through untouched.
 *
 * The delay is read at configure time, so a change applies from the next stream. A flush (seek,
 * stream change) drops what was held: stale audio must never play after a discontinuity.
 */
class AudioDelayProcessor : AudioProcessor {
    @Volatile var delayMs: Int = 0

    private var targetBytes = 0
    private var received = 0L
    private var emitted = 0L
    private var inputEnded = false
    private val held = ArrayDeque<ByteBuffer>()
    /** Bytes ready for the sink (read mode, position..limit). */
    private var output: ByteBuffer = EMPTY
    /** Accumulation for the next output batch (write mode). Heap buffers throughout: the
     *  growth path reads [array], which direct buffers refuse. */
    private var buffer: ByteBuffer = ByteBuffer.allocate(4096)

    override fun configure(input: AudioFormat): AudioFormat {
        targetBytes = if (delayMs > 0) delayMs * input.sampleRate / 1000 * input.bytesPerFrame else 0
        return input
    }

    override fun isActive() = targetBytes > 0

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val copy = ByteBuffer.allocate(input.remaining()).put(input).flip() as ByteBuffer
        held.add(copy)
        received += copy.remaining()
        drain()
    }

    override fun queueEndOfStream() {
        inputEnded = true
        drain(all = true)
    }

    private fun drain(all: Boolean = false) {
        // Emit the oldest held bytes, keeping targetBytes of audio behind the playhead.
        var moved = false
        while (held.isNotEmpty()) {
            val oldest = held.first()
            val allowed = if (all) oldest.remaining() else (received - emitted - targetBytes).toInt()
            if (allowed <= 0) break
            val take = minOf(oldest.remaining(), allowed)
            if (buffer.capacity() - buffer.position() < take) {
                buffer = ByteBuffer.allocate(maxOf(buffer.position() + take, 4096)).put(buffer.array(), 0, buffer.position())
            }
            buffer.put(oldest.array(), oldest.arrayOffset() + oldest.position(), take)
            oldest.position(oldest.position() + take)
            emitted += take
            moved = true
            if (!oldest.hasRemaining()) held.removeFirst()
        }
        if (moved) {
            buffer.flip()
            output = buffer
            buffer = ByteBuffer.allocate(4096)
        }
    }

    override fun getOutput(): ByteBuffer = output

    // Only after end of stream: the pipeline skips a processor that reports ended and never
    // feeds it input, so "nothing held yet" must not read as ended.
    override fun isEnded() = inputEnded && !output.hasRemaining() && held.isEmpty()

    override fun flush() {
        // A discontinuity (seek, new stream): drop held audio so stale sound never plays.
        held.clear(); received = 0; emitted = 0; output = EMPTY; buffer = ByteBuffer.allocate(4096); inputEnded = false
    }

    override fun reset() {
        flush(); targetBytes = 0
    }

    private companion object {
        /** A heap empty buffer, matching [buffer]: direct buffers refuse [ByteBuffer.array]. */
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
