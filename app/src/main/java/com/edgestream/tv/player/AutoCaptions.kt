package com.edgestream.tv.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.edgestream.tv.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Live captions generated on the TV from whatever is playing, for streams that carry no
 * subtitle track. ExoPlayer's decoded audio is teed off ([TeeAudioProcessor]), mixed to 16 kHz
 * mono and fed to an offline Vosk speech model on a worker thread; nothing leaves the device.
 *
 * The English model (~40 MB) is downloaded the first time captions are switched on. Audio sent
 * to an AV receiver as a bitstream (Dolby passthrough) never reaches the processor, so captions
 * need PCM output.
 */
@OptIn(UnstableApi::class)
class AutoCaptions(private val context: Context) : TeeAudioProcessor.AudioBufferSink {

    sealed interface Status {
        data object Off : Status
        data class Downloading(val percent: Int) : Status
        data object Listening : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status
    /** The caption on screen: the last finished sentence plus what is being said now. */
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    @Volatile private var enabled = false
    @Volatile private var inRate = 0
    @Volatile private var inChannels = 0
    @Volatile private var inEncoding = C.ENCODING_INVALID
    private var phase = 0.0

    private val queue = ArrayBlockingQueue<ShortArray>(48)
    private var worker: Thread? = null
    /** The in-flight model download, so disabling captions can cancel it mid-stream. */
    @Volatile private var modelCall: okhttp3.Call? = null

    private val modelDir get() = File(context.filesDir, "vosk-en")

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            start()
        } else {
            // OkHttp socket reads ignore Thread.interrupt, so the call itself must be cancelled;
            // otherwise a stalled download would pin a thread and socket forever.
            modelCall?.cancel()
            modelCall = null
            _status.value = Status.Off
            _text.value = ""
            queue.clear()
            worker?.interrupt()
            worker = null
        }
    }

    private fun start() {
        worker = Thread({ run() }, "AutoCaptions").apply { isDaemon = true; start() }
    }

    private fun run() {
        try {
            val dir = ensureModel() ?: return
            // Disabled while the model was downloading or unpacking: leave the Off status alone.
            if (!enabled) return
            Model(dir.absolutePath).use { model ->
                Recognizer(model, TARGET_RATE.toFloat()).use { rec ->
                    _status.value = Status.Listening
                    var committed = ""
                    var lastSpeech = System.currentTimeMillis()
                    while (enabled && !Thread.currentThread().isInterrupted) {
                        val chunk = queue.poll(500, TimeUnit.MILLISECONDS)
                        if (chunk == null) {
                            if (System.currentTimeMillis() - lastSpeech > CLEAR_AFTER_MS) _text.value = ""
                            continue
                        }
                        if (rec.acceptWaveForm(chunk, chunk.size)) {
                            val said = JSONObject(rec.result).optString("text")
                            if (said.isNotBlank()) { committed = sentence(said); lastSpeech = System.currentTimeMillis() }
                            _text.value = committed
                        } else {
                            val partial = JSONObject(rec.partialResult).optString("partial")
                            if (partial.isNotBlank()) {
                                lastSpeech = System.currentTimeMillis()
                                _text.value = tail("$committed ${sentence(partial)}".trim())
                            } else if (System.currentTimeMillis() - lastSpeech > CLEAR_AFTER_MS) _text.value = ""
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w("EdgeStream", "captions failed", t)
            _status.value = Status.Failed(t.message ?: "Captions stopped")
        }
    }

    /** Downloads and unpacks the model once; later launches reuse it. Cancels cleanly with [setEnabled]. */
    private fun ensureModel(): File? {
        val ready = File(modelDir, "READY")
        if (ready.exists()) return modelDir
        _status.value = Status.Downloading(0)
        val tmp = File(context.cacheDir, "vosk-en.zip")
        var failed: String? = null
        val call = Http.client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url(MODEL_URL).build())
        modelCall = call
        try {
            call.execute().use download@{ resp ->
                if (!resp.isSuccessful) { failed = "Model download failed (${resp.code})"; return@download }
                val body = resp.body
                val total = body.contentLength().coerceAtLeast(1)
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var read = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); read += n
                            _status.value = Status.Downloading((read * 100 / total).toInt().coerceIn(0, 99))
                            if (!enabled) return@download
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // A cancelled call throws; only surface real failures.
            if (enabled) failed = "Model download failed"
        } finally {
            // Only clear the reference if it's still ours: a quick off->on could start a new call.
            if (modelCall === call) modelCall = null
        }
        if (!enabled || failed != null) {
            tmp.delete()
            if (failed != null) _status.value = Status.Failed(failed)
            return null
        }
        modelDir.deleteRecursively(); modelDir.mkdirs()
        val base = modelDir.canonicalPath + File.separator
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // The archive has one top-level folder; strip it.
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val dest = File(modelDir, rel)
                require(dest.canonicalPath.startsWith(base)) { "Bad model archive" }
                if (e.isDirectory) dest.mkdirs() else { dest.parentFile?.mkdirs(); dest.outputStream().use { zip.copyTo(it) } }
            }
        }
        tmp.delete()
        ready.writeText("ok")
        return modelDir
    }

    // ---- audio tap (playback thread: keep it cheap, never block) ---------------------------

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        inRate = sampleRateHz; inChannels = channelCount; inEncoding = encoding; phase = 0.0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || _status.value != Status.Listening) return
        val rate = inRate; val ch = inChannels.coerceAtLeast(1)
        if (rate <= 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (inEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frames = b.remaining() / (bytesPerSample * ch)
        if (frames == 0) return
        val step = rate.toDouble() / TARGET_RATE
        val out = ShortArray((frames / step).toInt() + 2)
        var n = 0
        var acc = 0.0; var count = 0
        for (f in 0 until frames) {
            var mono = 0.0
            for (c in 0 until ch) {
                mono += if (bytesPerSample == 2) b.short.toDouble() else b.float * 32767.0
            }
            acc += mono / ch; count++
            phase += 1.0
            if (phase >= step) {
                phase -= step
                // Averaging the frames in each step is a cheap low-pass before decimating.
                out[n++] = (acc / count).toInt().coerceIn(-32768, 32767).toShort()
                acc = 0.0; count = 0
                if (n == out.size) break
            }
        }
        if (n > 0) queue.offer(out.copyOf(n)) // drops audio rather than stalling playback if behind
    }

    private fun sentence(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    /** Two short lines at most, like broadcast captions. */
    private fun tail(s: String) = if (s.length <= MAX_CHARS) s else "…" + s.takeLast(MAX_CHARS).substringAfter(' ')

    private companion object {
        const val TARGET_RATE = 16_000
        const val MAX_CHARS = 90
        const val CLEAR_AFTER_MS = 4_000L
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
