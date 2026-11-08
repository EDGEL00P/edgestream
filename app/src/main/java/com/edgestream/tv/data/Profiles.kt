package com.edgestream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * "Who's watching": each profile keeps its own watch list, followed teams, remembered channels
 * and calendar. The lineup, favourites, continue-watching and settings are shared. A PIN is
 * stored only as a salted SHA-256 hash.
 */
data class Profile(val id: String, val name: String, val pinHash: String? = null) {
    val locked get() = pinHash != null
}

object ProfileCodec {
    fun hash(id: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("edgestream:$id:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun pinMatches(p: Profile, pin: String): Boolean = p.pinHash == null || p.pinHash == hash(p.id, pin)

    fun encode(list: List<Profile>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("p", it.pinHash ?: JSONObject.NULL)) }
    }.toString()

    fun decode(json: String?): List<Profile> = try {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Profile(it.getString("id"), it.getString("n"), if (it.isNull("p")) null else it.getString("p")) }
    } catch (_: JSONException) { emptyList() }

    /** Prefs/file name for per-profile data; the first ("main") profile keeps the original names, so nothing existing moves. */
    fun scoped(base: String, profileId: String): String = if (profileId == MAIN) base else "${base}_$profileId"

    const val MAIN = "main"
}

object Profiles {
    private var prefs: android.content.SharedPreferences? = null
    private val _all = MutableStateFlow(listOf(Profile(ProfileCodec.MAIN, "Me")))
    val all: StateFlow<List<Profile>> = _all
    private val _active = MutableStateFlow(ProfileCodec.MAIN)
    val active: StateFlow<String> = _active

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("edgestream", Context.MODE_PRIVATE)
        // Names are sealed at rest; a list saved before that passes through Secrets unchanged.
        ProfileCodec.decode(prefs?.getString("profiles", null)?.let(Secrets::openOrBlank)).takeIf { it.isNotEmpty() }?.let { _all.value = it }
        _active.value = prefs?.getString("activeProfile", ProfileCodec.MAIN)?.takeIf { id -> _all.value.any { it.id == id } } ?: ProfileCodec.MAIN
    }

    private fun save(list: List<Profile>) {
        _all.value = list
        prefs?.edit()?.putString("profiles", Secrets.seal(ProfileCodec.encode(list)))?.apply()
    }

    fun add(name: String): Profile = Profile(java.util.UUID.randomUUID().toString().take(8), name.trim().ifEmpty { "Guest" }).also { save(_all.value + it) }

    fun rename(id: String, name: String) = save(_all.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })

    fun setPin(id: String, pin: String?) = save(_all.value.map { if (it.id == id) it.copy(pinHash = pin?.let { p -> ProfileCodec.hash(id, p) }) else it })

    /** The main profile can't be removed; removing the active one falls back to main. */
    fun remove(id: String) {
        if (id == ProfileCodec.MAIN) return
        save(_all.value.filterNot { it.id == id })
        if (_active.value == id) switchTo(ProfileCodec.MAIN)
    }

    fun switchTo(id: String) {
        _active.value = id
        prefs?.edit()?.putString("activeProfile", id)?.apply()
        SportsFollows.reload()
        ChannelPins.reload()
    }
}
