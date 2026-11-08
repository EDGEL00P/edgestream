package com.edgestream.tv.data

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * PuntersEdge Australian/NZ racing feed. The keyless demo is enough to prove the racing lane:
 * three next-to-go races, five runners each and the best three books per runner. A free key
 * later unlocks the full card; the parser already accepts both the demo envelope and the
 * keyed bare array.
 */
object Racing {
    data class Book(val key: String, val price: Double)

    data class Runner(val number: Int?, val name: String, val books: List<Book>) {
        /** Highest win price across the books quoting this runner. */
        val best: Double? = books.maxByOrNull { it.price }?.price
    }

    data class Race(
        val id: String,
        val venue: String,
        val number: Int,
        val category: String,
        val startMs: Long,
        val country: String,
        val booksQuoting: Int,
        val runners: List<Runner>,
    )

    private const val DEMO_URL = "https://api.puntersedge.online/v1/demo/racing/next-to-go"
    private const val TTL_MS = 60_000L

    @Volatile private var cache: Pair<Long, List<Race>>? = null

    suspend fun nextToGo(force: Boolean = false): List<Race> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) cache?.takeIf { now - it.first < TTL_MS }?.let { return@withContext it.second }
        val fresh = runCatching { fetch() }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) cache = now to fresh
        fresh.ifEmpty { cache?.second.orEmpty() }
    }

    private fun fetch(): List<Race> {
        val request = Request.Builder()
            .url(DEMO_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "EdgeStream/1.0 (Android TV)")
            .build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("PuntersEdge HTTP ${response.code}")
            response.body.string()
        }
        val trimmed = body.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray("races") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { parseRace(array.optJSONObject(index) ?: return@mapNotNull null) }.getOrNull()
        }
    }

    private fun parseRace(o: JSONObject): Race? {
        val venue = o.optString("venue").trim().ifBlank { return null }
        val startMs = runCatching { Instant.parse(o.optString("start_time")).toEpochMilli() }.getOrDefault(0L)
        val runnersArray = o.optJSONArray("runners") ?: JSONArray()
        val runners = (0 until runnersArray.length()).mapNotNull { i ->
            val r = runnersArray.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("name").trim()
            if (name.isBlank() || name.equals("Vacant Box", ignoreCase = true)) return@mapNotNull null
            val booksArray = r.optJSONArray("bookmakers") ?: JSONArray()
            val books = (0 until booksArray.length()).mapNotNull { b ->
                val book = booksArray.optJSONObject(b) ?: return@mapNotNull null
                val price = book.optDouble("win_price", Double.NaN)
                if (price.isNaN() || price <= 0.0) return@mapNotNull null
                Book(book.optString("key"), price)
            }
            if (books.isEmpty()) return@mapNotNull null
            Runner(
                number = if (r.has("number") && !r.isNull("number")) r.optInt("number") else null,
                name = name,
                books = books,
            )
        }
        if (runners.isEmpty()) return null
        val number = o.optInt("race_number")
        return Race(
            id = o.optString("race_id").ifBlank { "$venue-$number-$startMs" },
            venue = venue,
            number = number,
            category = o.optString("category").ifBlank { "race" },
            startMs = startMs,
            country = o.optString("country"),
            booksQuoting = o.optInt("books_quoting", 0),
            runners = runners,
        )
    }
}
