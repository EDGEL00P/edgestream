package com.edgestream.tv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Proxy

class XmltvParserTest {
    private data class Ev(val type: Int, val name: String? = null, val attrs: Map<String, String> = emptyMap(), val text: String? = null)

    /** Scripted fake XmlPullParser: next() walks the events, nextText() returns the current text. */
    private fun fakeParser(events: List<Ev>): XmlPullParser {
        var pos = 0
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "getEventType" -> events[pos].type
                "getName" -> events[pos].name
                "getAttributeValue" -> {
                    val attr = (args?.getOrNull(1) as? String) ?: ""
                    events[pos].attrs[attr]
                }
                "nextText" -> {
                    val t = events[pos].text.orEmpty()
                    pos++ // consume the text, landing on the element's END_TAG
                    t
                }
                "next" -> {
                    pos++
                    events.getOrNull(pos)?.type ?: XmlPullParser.END_DOCUMENT
                }
                else -> {
                    val rt = method.returnType
                    when {
                        rt == Boolean::class.javaPrimitiveType -> false
                        rt == Int::class.javaPrimitiveType -> 0
                        else -> null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(XmlPullParser::class.java), handler) as XmlPullParser
    }

    @Test fun channelIconsAreCaptured() {
        val s = XmlPullParser.START_TAG; val e = XmlPullParser.END_TAG; val d = XmlPullParser.END_DOCUMENT
        val events = listOf(
            Ev(s, "tv"),
            Ev(s, "channel", mapOf("id" to "bbc1.uk")),
            Ev(s, "display-name", text = "BBC One"),
            Ev(e, "display-name"),
            Ev(s, "icon", mapOf("src" to "https://cdn.example/bbc.png")),
            Ev(e, "icon"),
            Ev(e, "channel"),
            Ev(s, "channel", mapOf("id" to "itv.uk")),
            Ev(s, "display-name", text = "ITV"),
            Ev(e, "display-name"),
            Ev(s, "icon", mapOf("src" to "http://insecure.example/itv.png")),
            Ev(e, "icon"),
            Ev(e, "channel"),
            Ev(s, "programme", mapOf("channel" to "bbc1.uk", "start" to "20261106080000 +0000", "stop" to "20261106090000 +0000")),
            Ev(s, "title", text = "News"),
            Ev(e, "title"),
            Ev(e, "programme"),
            Ev(e, "tv"),
            Ev(d),
        )
        var channels: Map<String, List<String>>? = null
        var icons: Map<String, String>? = null
        var programmes = 0
        XmltvParser.parseWith(
            fakeParser(events),
            onChannels = { channels = it },
            accept = { _, _, _ -> true },
            onProgramme = { programmes++ },
            onChannelIcons = { icons = it },
        )
        assertEquals(mapOf("bbc1.uk" to listOf("BBC One"), "itv.uk" to listOf("ITV")), channels)
        // Only https icons are kept; http is dropped.
        assertEquals(mapOf("bbc1.uk" to "https://cdn.example/bbc.png"), icons)
        assertEquals(1, programmes)
    }
}
