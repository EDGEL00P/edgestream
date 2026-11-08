package com.edgestream.tv.data

import java.net.URLDecoder

/** A playable URL plus request headers, parsed from the "url|Header=value&Header2=value" form. */
data class StreamUrl(val url: String, val headers: Map<String, String>) {
    companion object {
        fun parse(raw: String): StreamUrl {
            val pipe = raw.indexOf('|')
            if (pipe < 0) return StreamUrl(raw.trim(), emptyMap())
            val headers = raw.substring(pipe + 1).split('&').mapNotNull { kv ->
                val k = kv.substringBefore('=').trim()
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrDefault(kv.substringAfter('='))
            }.toMap()
            return StreamUrl(raw.substring(0, pipe).trim(), headers)
        }
    }
}
