package com.edgestream.tv.data

import com.edgestream.tv.remote.VoiceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini as a fallback for commands the built-in rules can't read ("put the Chiefs game on").
 * Only the spoken or typed sentence is sent. The model picks one of the app's own actions, which
 * is turned back into a plain phrase and run through [VoiceCommand.parse], so an AI command
 * behaves exactly like a typed one. No key, no network or no confident answer: null, and the
 * caller keeps its search.
 */
object AiAssist {
    /** Stable, low-latency Flash-Lite model (ai.google.dev, checked 2026-11-09). */
    const val MODEL = "gemini-3.5-flash-lite"
    private const val URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val PLACES = listOf("home", "tv guide", "movies", "shows", "sports", "weather", "planner", "odds", "apps", "settings", "search")
    private val ACTIONS = listOf("tune", "watch", "open", "whatson", "games", "team", "search")

    private val INSTRUCTION = """
        You turn a TV viewer's spoken or typed request into exactly one action for the EdgeStream TV app.
        Actions and their value:
        - tune: a channel number, digits only ("channel one oh one" -> 101)
        - watch: a channel or programme to start playing ("put the Chiefs game on" -> "Chiefs")
        - open: one of ${PLACES.joinToString(", ")}
        - whatson: a channel name, or empty for what is on generally
        - games: empty; today's games and scores
        - team: a team name, when asking when it plays or its score
        - search: what to look for, when nothing else fits or you are unsure
        Keep names as the viewer said them; never invent channels.
    """.trimIndent()

    private val client by lazy { Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build() }

    /** What [text] most likely asks for, or null when Gemini can't help (or only says "search"). */
    suspend fun interpret(text: String, apiKey: String): VoiceCommand? {
        if (apiKey.isBlank() || text.isBlank()) return null
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(URL)
                    .header("x-goog-api-key", apiKey)
                    .post(requestJson(text).toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }?.let(::phraseFrom)?.let(VoiceCommand::parse)?.takeUnless { it is VoiceCommand.Search && it.guessed }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun requestJson(text: String): String {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(ACTIONS)))
                .put("value", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action", "value")))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", INSTRUCTION))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text.take(200))))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseJsonSchema", schema)
                .put("temperature", 0))
            .toString()
    }

    /** The model's choice as a phrase the app's own parser reads; null for "search" or nonsense. */
    internal fun phraseFrom(response: String): String? = runCatching {
        val text = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val choice = JSONObject(text)
        val value = choice.optString("value").trim()
        when (choice.optString("action")) {
            "tune" -> value.filter(Char::isDigit).takeIf { it.isNotEmpty() && it.length <= 4 }?.let { "channel $it" }
            "watch" -> value.takeIf { it.isNotEmpty() }?.let { "watch $it" }
            "open" -> value.lowercase().takeIf { it in PLACES }?.let { "open $it" }
            "whatson" -> if (value.isEmpty()) "what's on" else "what's on $value"
            "games" -> "today's games"
            "team" -> value.takeIf { it.isNotEmpty() }?.let { "when do $it play" }
            else -> null
        }
    }.getOrNull()
}
