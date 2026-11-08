package com.edgestream.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** A non-2xx HTTP response; [code] decides whether a retry can help. */
class HttpStatusException(val code: Int, message: String) : java.io.IOException(message)

object Http {
    const val DEFAULT_USER_AGENT = "EdgeStream/1.0 (Linux; Android TV)"

    @Volatile var userAgent: String = DEFAULT_USER_AGENT

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // HLS fetches playlists and segments in parallel; keep connections warm between them.
        .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 12 })
        .addInterceptor { chain ->
            // A stream's own User-Agent (from the playlist) wins over the app-wide one.
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") != null) req else req.newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /**
     * Transient failures worth retrying. Not 4xx (bad request/auth/not found won't change), not
     * TLS failures, and not cleartext blocked by policy: those fail the same way every time.
     */
    private fun retryable(t: Throwable): Boolean = when (t) {
        is HttpStatusException -> t.code !in 400..499
        is javax.net.ssl.SSLException -> false
        is java.net.UnknownServiceException -> false
        is java.io.IOException -> true
        else -> false
    }

    suspend fun <T> withRetry(
        attempts: Int = 3,
        initialDelayMs: Long = 400,
        maxDelayMs: Long = 3_000,
        block: suspend () -> T,
    ): T {
        var delayMs = initialDelayMs
        repeat(attempts - 1) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (!retryable(t)) throw t
                runCatching { android.util.Log.w("EdgeStream", "retry ${attempt + 1}/$attempts after ${t.message}") }
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
        }
        return block()
    }

    suspend fun <T> openSuspend(url: String, block: (InputStream) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { open(url, block) }

    /** Opens [url] and hands [block] a stream, transparently un-gzipping .gz payloads. */
    fun <T> open(url: String, block: (InputStream) -> T): T {
        val response: Response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw HttpStatusException(it.code, "HTTP ${it.code} for ${redact(url)}")
            val body = it.body.byteStream()
            return block(maybeGunzip(body))
        }
    }

    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
    }

    /** Credential-bearing query keys, matched case-insensitively ("Password", "api_key", "sig"). */
    private val SECRET_PARAM = Regex(
        """(?i)\b(password|passwd|pass|pwd|username|user|login|token|access[_-]?token|refresh[_-]?token|""" +
            """api[_-]?key|apikey|auth|authorization|secret|signature|sig|session|sessionid|key)=([^&#\s]*)""",
    )
    private val CREDENTIALS = Regex("""(?i)(://)[^/@\s]+:[^/@\s]+@""")
    private val STREAM_PATH = Regex("""(?i)/(live|movie|series|timeshift)/[^/?#\s]+/[^/?#\s]+/""")

    /** Hide credentials in URLs before they reach error messages or the UI. */
    fun redact(url: String): String =
        SECRET_PARAM.replace(url) { m -> "${m.groupValues[1]}=***" }
            .replace(CREDENTIALS, "$1***:***@")
            .replace(STREAM_PATH, "/$1/***/***/")
}
