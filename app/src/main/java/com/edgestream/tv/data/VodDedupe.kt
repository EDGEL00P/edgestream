package com.edgestream.tv.data

/**
 * One entry per title (providers list the same film in several categories, sometimes with and
 * sometimes without a year). Different years stay separate films; a copy with no year is dropped
 * when a dated copy of the same title exists. First occurrence wins, order is kept.
 */
fun <T> dedupeTitles(items: List<T>, title: (T) -> String, year: (T) -> String?): List<T> {
    if (items.size < 2) return items
    val key = { t: T -> NameCleaner.tidyTitle(title(t)).lowercase() }
    val dated = items.filter { year(it) != null }.mapTo(HashSet()) { key(it) }
    val seen = HashSet<Pair<String, String?>>(items.size)
    return items.filter { t ->
        val k = key(t); val y = year(t)
        (y != null || k !in dated) && seen.add(k to y)
    }
}
