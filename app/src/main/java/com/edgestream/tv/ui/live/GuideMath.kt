package com.edgestream.tv.ui.live

import com.edgestream.tv.data.db.ProgrammeEntity
import com.edgestream.tv.ui.AppViewModel

/** One cell of a guide row: a programme clipped to the window, or a gap ([programme] = null). */
data class GuideBlock(val start: Long, val end: Long, val programme: ProgrammeEntity?) {
    fun fraction(from: Long, to: Long): Float = if (to <= from) 0f else (end - start).toFloat() / (to - from)
}

/**
 * A row's cells for [from, to): programmes clipped to the window, overlaps trimmed (a later
 * programme starts where the earlier ended), and gaps filled with "no listing" cells so the row
 * always spans the whole window and D-pad focus never falls through a hole.
 */
fun guideBlocks(programmes: List<ProgrammeEntity>, from: Long, to: Long): List<GuideBlock> {
    val out = ArrayList<GuideBlock>()
    var cursor = from
    for (p in programmes.sortedBy { it.start }) {
        val s = maxOf(p.start, cursor)
        val e = minOf(p.stop, to)
        if (e <= s) continue
        if (s > cursor) out += GuideBlock(cursor, s, null)
        out += GuideBlock(s, e, p)
        cursor = e
        if (cursor >= to) break
    }
    if (cursor < to) out += GuideBlock(cursor, to, null)
    return out
}

fun timeLeftLabel(now: Long, stop: Long): String {
    val min = ((stop - now) / 60_000L).toInt()
    return when {
        min <= 0 -> "Ending"
        min < 60 -> "$min min left"
        else -> "${min / 60} h ${min % 60} min left"
    }
}

fun progressOf(now: Long, start: Long, stop: Long): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

/** Filter chips for the guide: never "All channels" (a project rule), never an empty group. */
fun guideChips(groups: List<Pair<String, Int>>): List<Pair<String, Int>> =
    groups.filter { (name, count) -> name != AppViewModel.ALL && count > 0 }
