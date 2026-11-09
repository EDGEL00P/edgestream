package com.edgestream.tv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small, self-contained weather radar: RainViewer's observed frames over Esri's real
 * satellite imagery and place-label layer, stitched into one viewport bitmap per layer so the
 * TV only draws a handful of images. The same sources the Edge launcher uses.
 */
object Radar {
    data class Frame(val time: Long, val bitmap: Bitmap)
    data class Data(
        val place: String,
        val base: Bitmap,
        val labels: Bitmap?,
        val frames: List<Frame>,
        val zoom: Int,
        val lat: Double,
        val lon: Double,
        val analysis: Analysis? = null,
    )

    /** Lightweight, honest pixel summary of the newest radar frame. */
    data class Analysis(
        val coveragePct: Int,
        val maxDbz: Int?,
        val nearestKm: Int?,
        val bearing: String?,
        val motionKph: Int?,
        val motionBearing: String?,
        val cells: List<StormCell> = emptyList(),
    )

    data class StormCell(
        val id: Int, val x: Float, val y: Float, val km: Int, val bearing: String,
        val maxDbz: Int, val areaKm2: Int,
    )

    private const val INDEX = "https://api.rainviewer.com/public/weather-maps.json"
    private const val SATELLITE = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile"
    /** Esri's keyless street map: the familiar light radar backdrop. */
    private const val STANDARD = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile"
    private const val LABELS = "https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile"
    private const val TILE = 256
    private const val FRAME_COUNT = 6
    private const val TTL_MS = 90_000L

    /** Radars are chosen by radius, not by an arbitrary tile zoom. */
    fun rangeKm(zoom: Int): Int = when (zoom) {
        7 -> 280
        8 -> 140
        9 -> 70
        else -> 35
    }

    fun rings(zoom: Int): List<Int> = when (zoom) {
        7 -> listOf(80, 160, 280)
        8 -> listOf(40, 80, 140)
        9 -> listOf(20, 40, 70)
        else -> listOf(10, 20, 35)
    }

    fun zoomLabel(zoom: Int): String = "${rangeKm(zoom)} KM"

    /** Step a frame index, clamped to the loop. Null when there is nothing to step yet. */
    fun stepFrame(index: Int, delta: Int, lastIndex: Int): Int? =
        if (lastIndex < 0) null else (index + delta).coerceIn(0, lastIndex)

    /** RainViewer's free radar tiles stop at zoom 7; maps can go closer. */
    private fun radarZoom(zoom: Int): Int = minOf(zoom, 7)

    private data class Key(val lat: Double, val lon: Double, val zoom: Int, val satellite: Boolean, val width: Int, val height: Int)
    @Volatile private var cache: Pair<Long, Pair<Key, Data>>? = null

    /** Small tile cache shared by the base map and every radar frame. */
    private val tiles = object : LruCache<String, Bitmap>(96) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /**
     * Emits twice: satellite + place labels first (fast), then the RainViewer frames.
     * The screen paints the map while the radar loop is still downloading.
     */
    fun stream(
        place: PublicApis.Location,
        zoom: Int,
        width: Int = 960,
        height: Int = 540,
        satellite: Boolean = false,
    ): Flow<Data> = flow {
        val key = Key(place.lat, place.lon, zoom, satellite, width, height)
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < TTL_MS && it.second.first == key }?.let {
            emit(it.second.second)
            return@flow
        }

        val base = stitch(place.lat, place.lon, zoom, width, height) { z, x, y ->
            if (satellite) "$SATELLITE/$z/$y/$x" else "$STANDARD/$z/$y/$x"
        } ?: return@flow
        // Satellite has no place names baked in; the standard street map does.
        val labels = if (satellite) {
            stitch(place.lat, place.lon, zoom, width, height) { z, x, y -> "$LABELS/$z/$y/$x" }
        } else null
        val initial = Data(place.city, base, labels, emptyList(), zoom, place.lat, place.lon)
        emit(initial)

        val frames = loadFrames(place, zoom, width, height)
        val full = initial.copy(
            frames = frames,
            analysis = analyse(frames, place.lat, zoom, rangeKm(zoom)),
        )
        cache = now to (key to full)
        emit(full)
    }.flowOn(Dispatchers.IO)

    private suspend fun loadFrames(
        place: PublicApis.Location,
        zoom: Int,
        width: Int,
        height: Int,
    ): List<Frame> {
        val body = runCatching { get(INDEX) }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val host = root.optString("host").ifBlank { "https://tilecache.rainviewer.com" }
        val past = root.optJSONObject("radar")?.optJSONArray("past") ?: JSONArray()
        val wanted = (maxOf(0, past.length() - FRAME_COUNT) until past.length()).mapNotNull { i ->
            past.optJSONObject(i)?.let { f ->
                val path = f.optString("path")
                if (path.isBlank()) null else f.optLong("time") to "$host$path"
            }
        }
        return coroutineScope {
            val tileZoom = radarZoom(zoom)
            wanted.map { (time, prefix) ->
                async(Dispatchers.IO) {
                    stitchScaled(place.lat, place.lon, zoom, tileZoom, width, height) { z, x, y ->
                        // NEXRAD palette (6): green → yellow → red, the broadcast radar look.
                        "$prefix/256/$z/$x/$y/6/1_0.png"
                    }?.let { Frame(time, it) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private data class Scan(
        val count: Int, val total: Int, val maxDbz: Int?,
        val nearestKm: Int?, val bearing: String?, val cx: Double, val cy: Double,
    )

    private data class Palette(val dbz: Int, val r: Int, val g: Int, val b: Int)

    /** RainViewer's NEXRAD ramp. Nearest-colour matching beats guessing from red/green levels. */
    private val NEXRAD = listOf(
        Palette(5, 0x00, 0xEC, 0xEC),
        Palette(10, 0x01, 0xA0, 0xF6),
        Palette(15, 0x00, 0xA0, 0x00),
        Palette(20, 0x00, 0xF0, 0x00),
        Palette(25, 0x00, 0xC8, 0x00),
        Palette(30, 0xFF, 0xFF, 0x00),
        Palette(35, 0xE7, 0xC0, 0x00),
        Palette(40, 0xFF, 0x90, 0x00),
        Palette(45, 0xFF, 0x00, 0x00),
        Palette(50, 0xD6, 0x00, 0x00),
        Palette(55, 0xC0, 0x00, 0x00),
        Palette(60, 0xFF, 0x00, 0xFF),
        Palette(65, 0x99, 0x55, 0xC9),
    )

    /** Summary measured inside the selected radar radius, not across the whole rectangular map. */
    private fun analyse(frames: List<Frame>, lat: Double, zoom: Int, rangeKm: Int): Analysis? {
        if (frames.isEmpty()) return null
        val kmPerPx = 156.543 * kotlin.math.cos(Math.toRadians(lat)) / (1 shl zoom)
        val radiusPx = rangeKm / kmPerPx
        val sampleStep = (1 shl (zoom - radarZoom(zoom))).coerceIn(2, 16)
        // One scratch buffer reused for every read: three full-frame IntArrays per refresh adds
        // several megabytes of GC pressure on a TV box.
        val newest = frames.last().bitmap
        val scratch = IntArray(newest.width * newest.height)
        val newestScan = scan(newest, kmPerPx, radiusPx, scratch)
        if (newestScan.count == 0) return Analysis(0, null, null, null, null, null)
        val oldestScan = scan(frames.first().bitmap, kmPerPx, radiusPx, scratch)
        val minutes = ((frames.last().time - frames.first().time) / 60.0).coerceAtLeast(1.0)
        val motion = if (oldestScan.count > 50 && newestScan.count > 50) {
            val dx = (newestScan.cx - oldestScan.cx) * kmPerPx
            val dy = (newestScan.cy - oldestScan.cy) * kmPerPx
            val kph = (kotlin.math.hypot(dx, dy) / minutes * 60).toInt()
            // Sparse centroids can jump between frames; only trust plausible storm motion.
            if (kph in 1..120) kph to compass(Math.toDegrees(kotlin.math.atan2(dx, -dy))) else null
        } else null
        return Analysis(
            coveragePct = (newestScan.count * 100 / newestScan.total.coerceAtLeast(1)).coerceIn(0, 100),
            maxDbz = newestScan.maxDbz,
            nearestKm = newestScan.nearestKm,
            bearing = newestScan.bearing,
            motionKph = motion?.first,
            motionBearing = motion?.second,
            cells = findCells(newest, kmPerPx, sampleStep, rangeKm, scratch),
        )
    }

    /** 0 means "not a radar echo"; only colours close to the NEXRAD ramp count. */
    internal fun reflectivity(argb: Int): Int = dbzOf(argb)

    private fun dbzOf(p: Int): Int {
        if ((p ushr 24) < 60) return 0
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        var best = 0
        var bestDistance = 14_000
        for (colour in NEXRAD) {
            val dr = r - colour.r
            val dg = g - colour.g
            val db = b - colour.b
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = colour.dbz
            }
        }
        return best
    }

    /** Connected rain clusters in the latest frame, sampled at the radar's native resolution. */
    private fun findCells(bitmap: Bitmap, kmPerPx: Double, step: Int, rangeKm: Int, pixels: IntArray): List<StormCell> {
        val w = bitmap.width
        val h = bitmap.height
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        val rain = BooleanArray(gw * gh)
        val dbz = IntArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val x = gx * step
                val y = gy * step
                if (x >= w || y >= h) continue
                val strength = dbzOf(pixels[y * w + x])
                if (strength == 0) continue
                rain[gy * gw + gx] = true
                dbz[gy * gw + gx] = strength
            }
        }
        val seen = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        val out = mutableListOf<StormCell>()
        for (start in rain.indices) {
            if (!rain[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var count = 0
            var sumX = 0L
            var sumY = 0L
            var strongest = 0
            while (head < tail) {
                val idx = queue[head++]
                val gx = idx % gw
                val gy = idx / gw
                count++
                sumX += gx.toLong() * step
                sumY += gy.toLong() * step
                if (dbz[idx] > strongest) strongest = dbz[idx]
                val neighbours = intArrayOf(idx - 1, idx + 1, idx - gw, idx + gw)
                for (n in neighbours) {
                    if (n !in rain.indices || seen[n] || !rain[n]) continue
                    if ((n == idx - 1 && gx == 0) || (n == idx + 1 && gx == gw - 1)) continue
                    seen[n] = true
                    queue[tail++] = n
                }
            }
            if (count < 3) continue
            val x = (sumX.toDouble() / count).toFloat()
            val y = (sumY.toDouble() / count).toFloat()
            val dx = (x - w / 2f) * kmPerPx
            val dy = (y - h / 2f) * kmPerPx
            val km = kotlin.math.hypot(dx, dy)
            if (km > rangeKm) continue
            out += StormCell(
                id = 0,
                x = x, y = y,
                km = km.roundToInt(),
                bearing = compass(Math.toDegrees(kotlin.math.atan2(dx, -dy))),
                maxDbz = strongest,
                areaKm2 = (count * step * step * kmPerPx * kmPerPx).roundToInt(),
            )
        }
        return out.sortedWith(compareByDescending<StormCell> { it.maxDbz * 1000 + it.areaKm2 })
            .take(4)
            .mapIndexed { i, c -> c.copy(id = i + 1) }
    }

    private fun scan(bitmap: Bitmap, kmPerPx: Double, radiusPx: Double, pixels: IntArray): Scan {
        val w = bitmap.width
        val h = bitmap.height
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val cx = w / 2.0
        val cy = h / 2.0
        val maxDistanceSq = radiusPx * radiusPx
        var count = 0
        var total = 0
        var maxDbz = 0
        var nearest = Double.MAX_VALUE
        var bestX = 0.0
        var bestY = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumW = 0.0
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val dxPx = x - cx
                val dyPx = y - cy
                if (dxPx * dxPx + dyPx * dyPx > maxDistanceSq) continue
                total++
                val dbz = dbzOf(pixels[y * w + x])
                if (dbz == 0) continue
                count++
                if (dbz > maxDbz) maxDbz = dbz
                val dx = dxPx * kmPerPx
                val dy = dyPx * kmPerPx
                val d = kotlin.math.hypot(dx, dy)
                if (d < nearest) {
                    nearest = d
                    bestX = dx
                    bestY = dy
                }
                sumX += x * dbz
                sumY += y * dbz
                sumW += dbz
            }
        }
        return Scan(
            count = count, total = total,
            maxDbz = maxDbz.takeIf { count > 0 },
            nearestKm = if (nearest == Double.MAX_VALUE) null else nearest.toInt(),
            bearing = if (nearest == Double.MAX_VALUE) null else compass(Math.toDegrees(kotlin.math.atan2(bestX, -bestY))),
            cx = if (sumW > 0) sumX / sumW else cx,
            cy = if (sumW > 0) sumY / sumW else cy,
        )
    }

    private fun compass(degrees: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val i = (((degrees % 360 + 360) % 360) / 45).toInt().coerceIn(0, 7)
        return names[i]
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "EdgeStream/1.0 (Android TV)").build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Radar HTTP ${response.code}")
            response.body.string()
        }
    }

    private fun tile(url: String): Bitmap? {        tiles.get(url)?.let { return it }
        val bytes = runCatching {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", "EdgeStream/1.0 (Android TV)").build())
                .execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
        }.getOrNull() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        tiles.put(url, bitmap)
        return bitmap
    }

    /** One viewport-sized bitmap from the Web Mercator tiles around [lat]/[lon]. */
    private fun stitch(
        lat: Double,
        lon: Double,
        zoom: Int,
        width: Int,
        height: Int,
        url: (z: Int, x: Int, y: Int) -> String,
    ): Bitmap? = stitchScaled(lat, lon, zoom, zoom, width, height, url)

    /**
     * Satellite/labels use [baseZoom] for a sharp close view; RainViewer's free radar tiles stop
     * at zoom 7, so [tileZoom] can be lower and the tiles are scaled up to the same viewport.
     */
    private fun stitchScaled(
        lat: Double,
        lon: Double,
        baseZoom: Int,
        tileZoom: Int,
        width: Int,
        height: Int,
        url: (z: Int, x: Int, y: Int) -> String,
    ): Bitmap? {
        val factor = 1 shl (baseZoom - tileZoom)
        val n = 1 shl tileZoom
        val world = TILE.toDouble() * (1 shl baseZoom)
        val cLat = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(cLat)
        val cx = (lon + 180.0) / 360.0 * world
        val cy = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * world
        val left = cx - width / 2.0
        val top = cy - height / 2.0
        val leftTile = left / factor
        val topTile = top / factor
        val widthTile = width.toDouble() / factor
        val heightTile = height.toDouble() / factor

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var drew = false
        val x0 = floor(leftTile / TILE).toInt()
        val x1 = floor((leftTile + widthTile - 1) / TILE).toInt()
        val y0 = floor(topTile / TILE).toInt().coerceAtLeast(0)
        val y1 = floor((topTile + heightTile - 1) / TILE).toInt().coerceAtMost(n - 1)
        if (factor > 1) canvas.scale(factor.toFloat(), factor.toFloat())
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val wrapped = Math.floorMod(tx, n)
                val bmp = tile(url(tileZoom, wrapped, ty)) ?: continue
                canvas.drawBitmap(bmp, (tx * TILE - leftTile).toFloat(), (ty * TILE - topTile).toFloat(), paint)
                drew = true
            }
        }
        if (!drew) {
            out.recycle()
            return null
        }
        return out
    }
}
