package com.edgestream.tv.data

import com.edgestream.tv.remote.VoiceCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistTest {
    private fun reply(action: String, value: String) = JSONObject().put(
        "candidates",
        org.json.JSONArray().put(JSONObject().put("content", JSONObject().put("parts",
            org.json.JSONArray().put(JSONObject().put("text", JSONObject().put("action", action).put("value", value).toString()))))),
    ).toString()

    @Test fun eachActionBecomesAPhraseTheAppAlreadyReads() {
        assertEquals("channel 101", AiAssist.phraseFrom(reply("tune", "101")))
        assertEquals("watch Chiefs", AiAssist.phraseFrom(reply("watch", "Chiefs")))
        assertEquals("open sports", AiAssist.phraseFrom(reply("open", "Sports")))
        assertEquals("what's on", AiAssist.phraseFrom(reply("whatson", "")))
        assertEquals("when do Broncos play", AiAssist.phraseFrom(reply("team", "Broncos")))
        // And those phrases parse into real commands.
        assertEquals(VoiceCommand.Tune(101), VoiceCommand.parse(AiAssist.phraseFrom(reply("tune", "101"))!!))
        assertEquals(VoiceCommand.Team("Broncos"), VoiceCommand.parse(AiAssist.phraseFrom(reply("team", "Broncos"))!!))
    }

    @Test fun searchUnknownPlacesAndGarbageAreDropped() {
        assertNull(AiAssist.phraseFrom(reply("search", "anything")))
        assertNull(AiAssist.phraseFrom(reply("open", "the moon")))
        assertNull(AiAssist.phraseFrom(reply("tune", "twelve thousand three")))
        assertNull(AiAssist.phraseFrom("not json"))
        assertNull(AiAssist.phraseFrom("""{"candidates":[]}"""))
    }

    @Test fun requestCarriesOnlyTheSentenceAndAStrictSchema() {
        val body = JSONObject(AiAssist.requestJson("put the chiefs game on"))
        assertEquals("put the chiefs game on", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertTrue(config.getJSONObject("responseJsonSchema").getJSONArray("required").length() == 2)
    }

    @Test fun onlyTheParsersGiveUpIsMarkedAsAGuess() {
        assertTrue((VoiceCommand.parse("put the chiefs game on please mate") as VoiceCommand.Search).guessed)
        assertTrue(!(VoiceCommand.parse("search for batman") as VoiceCommand.Search).guessed)
    }
}
