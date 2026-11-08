package com.edgestream.tv.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder

/** One parsed phone-remote HTTP request. */
internal class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

/**
 * Reads one request, giving up (null) after [budgetMs] in total, so a client dripping one byte at a
 * time can't hold a worker (the socket timeout only bounds the gap between bytes).
 */
internal fun readRequest(
    input: InputStream,
    clock: () -> Long = System::currentTimeMillis,
    budgetMs: Long = 5_000,
    /** Socket timeout for the next blocking read; set to what is left of the budget before each read. */
    setTimeout: (Int) -> Unit = {},
    /** Largest body accepted for a path; anything longer is cut there. */
    maxBody: (String) -> Int = { 64_000 },
): Request? {
    val start = clock()
    fun late() = clock() - start > budgetMs
    fun bound() = setTimeout((budgetMs - (clock() - start)).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
    val head = ByteArrayOutputStream()
    var last4 = 0
    while (true) {
        bound()
        val b = input.read()
        if (b < 0 || late()) return null
        head.write(b)
        last4 = (last4 shl 8) or b
        if (last4 == 0x0D0A0D0A) break
        if (head.size() > 16_384) return null
    }
    val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
    val (method, target) = lines.first().split(' ').let { if (it.size < 2) return null else it[0] to it[1] }
    val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, maxBody(target.substringBefore('?'))) ?: 0
    val body = ByteArray(length)
    var read = 0
    while (read < length) { if (late()) return null; bound(); val n = input.read(body, read, length - read); if (n < 0) break; read += n }
    val path = target.substringBefore('?')
    val query = target.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    return Request(method, path, query, headers, String(body, 0, read, Charsets.UTF_8))
}
