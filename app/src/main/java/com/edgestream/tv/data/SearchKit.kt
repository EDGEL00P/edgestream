package com.edgestream.tv.data

import android.content.Context
import com.edgestream.tv.data.db.ChannelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Search intelligence for a remote-first UI:
 *  - recent searches that survive process death
 *  - live suggestions from the real channel catalogue (name, group and typo tolerant)
 *  - a single strong hit can be tuned without leaving the keyboard
 */
object SearchKit {
    private const val PREFS = "edgestream"
    private const val KEY_RECENT = "recentSearches"
    private const val SEP = "\u001F"
    private const val MAX_RECENT = 8

    private var prefs: android.content.SharedPreferences? = null
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _recent.value = prefs?.getString(KEY_RECENT, null)
            ?.split(SEP)?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    fun remember(term: String) {
        val clean = term.trim()
        if (clean.length < 2) return
        val next = (listOf(clean) + _recent.value.filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_RECENT)
        if (next == _recent.value) return
        _recent.value = next
        prefs?.edit()?.putString(KEY_RECENT, next.joinToString(SEP))?.apply()
    }

    fun forget(term: String) {
        val next = _recent.value.filterNot { it.equals(term, ignoreCase = true) }
        if (next == _recent.value) return
        _recent.value = next
        prefs?.edit()?.putString(KEY_RECENT, next.joinToString(SEP))?.apply()
    }

    fun clearRecent() {
        if (_recent.value.isEmpty()) return
        _recent.value = emptyList()
        prefs?.edit()?.remove(KEY_RECENT)?.apply()
    }

    /**
     * Ranks real catalogue matches for [query]. Channel hits are returned with their key so a
     * single OK press can tune; group hits set the query so the grouped result list opens.
     * Prefix beats word-start beats substring beats an in-order fuzzy match.
     */
    fun suggestions(
        query: String,
        channels: List<ChannelEntity>,
        groups: List<String>,
        limit: Int = 6,
    ): List<SearchSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) {
            return _recent.value.take(limit).map {
                SearchSuggestion(it, "Recent search", query = it, isRecent = true)
            }
        }
        val out = ArrayList<SearchSuggestion>(limit)
        val seen = HashSet<String>()

        val ranked = ArrayList<Pair<ChannelEntity, Int>>(64)
        var scannedFuzzy = 0
        for (ch in channels) {
            val score = score(q, ch.name)
            if (score > 0) ranked.add(ch to score)
            // A very common prefix can match thousands; the top bucket is all we need.
            if (ranked.size >= 250) break
        }
        if (ranked.isEmpty() && q.length >= 3) {
            // One typo-tolerant pass, bounded so a weak box never stalls while typing.
            for (ch in channels) {
                if (subsequence(q, ch.name)) {
                    ranked.add(ch to 10)
                    if (++scannedFuzzy >= 40) break
                }
            }
        }
        ranked.sortWith(compareByDescending<Pair<ChannelEntity, Int>> { it.second }.thenBy { ChannelNumbers.lcn(it.first) })
        for ((ch, _) in ranked) {
            if (out.size >= limit) break
            val group = NameCleaner.tidyGroup(ch.groupName)
            val detail = if (group.isBlank()) "Channel ${ChannelNumbers.lcn(ch)}" else "$group  ·  ${ChannelNumbers.lcn(ch)}"
            // One suggestion per channel name: providers often carry the same feed many times.
            if (seen.add("n:${ch.name.lowercase()}")) {
                out.add(SearchSuggestion(ch.name, detail, query = ch.name, channelKey = ch.key))
            }
        }
        if (out.size < limit) {
            for (group in groups) {
                if (out.size >= limit) break
                val score = score(q, group)
                if (score < 40) continue
                if (seen.add("g:$group")) {
                    out.add(SearchSuggestion(group, "Channel group", query = group))
                }
            }
        }
        return out
    }

    /** 0 means no match. Exact > prefix > word-start > substring > fuzzy. */
    fun score(query: String, candidate: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val c = candidate.lowercase()
        return when {
            c == q -> 100
            c.startsWith(q) -> 88
            c.split(' ', '-', '·', '|', ':', '/').any { it.startsWith(q) } -> 74
            c.contains(q) -> 55
            q.length >= 3 && subsequence(q, c) -> 18
            else -> 0
        }
    }

    private fun subsequence(q: String, candidate: String): Boolean {
        var i = 0
        for (ch in candidate) {
            if (i < q.length && ch == q[i]) i++
            if (i == q.length) return true
        }
        return false
    }
}

data class SearchSuggestion(
    val label: String,
    val detail: String,
    /** What to put in the search box when this suggestion is chosen. */
    val query: String,
    val channelKey: String? = null,
    val isRecent: Boolean = false,
)
