package com.edgestream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSubtitlesTest {
    private val reply = """{"subtitles":[
        {"id":"1","url":"https://subs5.strem.io/en/download/a","lang":"eng"},
        {"id":"2","url":"https://subs5.strem.io/fr/download/b","lang":"fre"},
        {"id":"3","url":"https://subs5.strem.io/en/download/a","lang":"eng"},
        {"id":"4","url":"http://insecure/c","lang":"eng"},
        {"id":"5","url":"https://subs5.strem.io/en/download/d","lang":"eng"}
    ]}"""

    @Test fun keepsTheWantedLanguageOnceEachOverHttpsInRankOrder() {
        val subs = OnlineSubtitles.parse(reply, "eng", "en", limit = 3)
        assertEquals(listOf("https://subs5.strem.io/en/download/a", "https://subs5.strem.io/en/download/d"), subs.map { it.url })
        assertEquals(listOf("English · Online", "English · Online 2"), subs.map { it.label })
        assertEquals("en", subs.first().language)
    }

    @Test fun unreadableReplyGivesNothing() {
        assertEquals(emptyList<OnlineSubtitles.Sub>(), OnlineSubtitles.parse("<html>", "eng", "en", 3))
    }
}
