package com.edgestream.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A programme the viewer asked to be told about when it starts. */
data class Reminder(
    val channelKey: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
) {
    val id get() = "$channelKey@$start"
}

/** Reminders kept in a small JSON file; ones for programmes that have ended are dropped. */
class ReminderStore(private val file: File) {
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Reminder>> = _all

    fun has(channelKey: String, start: Long) = _all.value.any { it.channelKey == channelKey && it.start == start }

    /** Adds the reminder, or removes it if already set. Returns true when it is now set. */
    fun toggle(r: Reminder): Boolean {
        val exists = has(r.channelKey, r.start)
        save(if (exists) _all.value.filterNot { it.id == r.id } else (_all.value + r).sortedBy { it.start })
        return !exists
    }

    fun remove(r: Reminder) = save(_all.value.filterNot { it.id == r.id })

    /** Reminders whose programme has started (within the last [graceMs]) and not yet ended. */
    fun due(now: Long, graceMs: Long = 10 * 60_000L): List<Reminder> =
        _all.value.filter { it.start <= now + 30_000 && it.start > now - graceMs && it.stop > now }

    private fun save(list: List<Reminder>) {
        val now = System.currentTimeMillis()
        val kept = list.filter { it.stop > now }
        _all.value = kept
        runCatching {
            file.writeText(JSONArray().apply {
                kept.forEach { r ->
                    put(JSONObject().put("k", r.channelKey).put("n", r.channelName).put("t", r.title).put("s", r.start).put("e", r.stop))
                }
            }.toString())
        }
    }

    private fun load(): List<Reminder> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Reminder(it.getString("k"), it.getString("n"), it.getString("t"), it.getLong("s"), it.getLong("e"))
        }.filter { it.stop > System.currentTimeMillis() }
    }.getOrElse { emptyList() }
}
