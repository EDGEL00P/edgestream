package com.edgestream.tv.data.parse

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?,
    val catchup: String?,
    val catchupDays: Int,
    val catchupSource: String?,
) {
    /** Xtream-style M3U exports put movies and series episodes under /movie/ and /series/. */
    val isVod: Boolean
        get() {
            val path = url.substringBefore('?').lowercase()
            return "/movie/" in path || "/series/" in path ||
                VOD_EXTENSIONS.any { path.endsWith(it) }
        }

    companion object {
        private val VOD_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm")
    }
}

class M3uResult(val epgUrls: List<String>, val entries: List<M3uEntry>)

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(input: InputStream): M3uResult {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val epgUrls = mutableListOf<String>()
        val entries = ArrayList<M3uEntry>()
        var pendingInf: String? = null
        var pendingGroup: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()

        reader.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    attr.findAll(line).forEach { m ->
                        val key = m.groupValues[1].lowercase()
                        if (key == "url-tvg" || key == "x-tvg-url" || key == "tvg-url") {
                            m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach(epgUrls::add)
                        }
                    }
                }
                line.startsWith("#EXTINF") -> { pendingInf = line; pendingGroup = null; pendingHeaders.clear() }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) && "stream_headers=" in line -> {
                    line.substringAfter("stream_headers=").split('&').forEach { kv ->
                        val k = kv.substringBefore('=').trim()
                        if (k.isNotEmpty()) pendingHeaders[k] = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                    }
                }
                line.startsWith("#EXTGRP:") -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // #EXTVLCOPT, #KODIPROP and friends
                else -> {
                    val inf = pendingInf
                    if (inf != null) entries += toEntry(inf, pendingGroup, withHeaders(line, inf, pendingHeaders))
                    pendingInf = null
                    pendingGroup = null
                    pendingHeaders.clear()
                }
            }
        }
        return M3uResult(epgUrls.distinct(), entries)
    }

    /** Folds header hints from #EXTVLCOPT / #EXTINF attributes into the "url|Header=value" form. */
    private fun withHeaders(url: String, inf: String, headers: Map<String, String>): String {
        val all = LinkedHashMap(headers)
        attr.findAll(inf).forEach { m ->
            when (m.groupValues[1].lowercase()) {
                "http-user-agent", "user-agent" -> all.putIfAbsent("User-Agent", m.groupValues[2])
                "http-referrer", "http-referer", "referrer", "referer" -> all.putIfAbsent("Referer", m.groupValues[2])
            }
        }
        if (all.isEmpty() || '|' in url) return url
        return url + "|" + all.entries.joinToString("&") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
    }

    private fun toEntry(inf: String, extGroup: String?, url: String): M3uEntry {
        val attrs = HashMap<String, String>()
        // Attributes live between "#EXTINF:-1" and the display name after the last unquoted comma.
        val nameStart = findNameComma(inf)
        val head = if (nameStart >= 0) inf.substring(0, nameStart) else inf
        attr.findAll(head).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
        val title = (if (nameStart >= 0) inf.substring(nameStart + 1) else "").trim()
        val name = title.ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { "Channel" }
        val days = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0
        return M3uEntry(
            name = name,
            url = url,
            group = (attrs["group-title"] ?: extGroup).orEmpty().ifBlank { "Uncategorised" },
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
            catchup = attrs["catchup"] ?: attrs["catchup-type"],
            catchupDays = days,
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
        )
    }

    /** The display name follows the first comma that is not inside a quoted attribute. */
    private fun findNameComma(inf: String): Int {
        var inQuotes = false
        for (i in inf.indices) {
            when (inf[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
