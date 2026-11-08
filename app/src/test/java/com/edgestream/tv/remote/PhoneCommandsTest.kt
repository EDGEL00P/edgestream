package com.edgestream.tv.remote

import com.edgestream.tv.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneCommandsTest {
    private val calls = mutableListOf<String>()
    private val commands = PhoneCommands(object : PhoneCommandTarget {
        override fun playFromPhone(channelKey: String) { calls += "play:$channelKey" }
        override fun openSearch(query: String) { calls += "search:$query" }
        override fun openScreen(screen: Screen) { calls += "open:${screen.name}" }
        override fun tuneNumber(number: Int) { calls += "tune:$number" }
        override fun playNamed(name: String) { calls += "watch:$name" }
    })

    @Test fun play_forwardsKey() {
        commands.handle(PhoneLink.Command.Play("k1"))
        assertEquals(listOf("play:k1"), calls)
    }

    @Test fun voice_reachesTheRightAction() {
        listOf("cricket", "channel 526", "watch Fox Cricket", "open the guide").forEach {
            commands.handle(PhoneLink.Command.Voice(VoiceCommand.parse(it)!!))
        }
        assertEquals(listOf("search:cricket", "tune:526", "watch:Fox Cricket", "open:LIVE"), calls)
    }

    @Test fun open_known() {
        commands.handle(PhoneLink.Command.Open("MOVIES"))
        assertEquals(listOf("open:MOVIES"), calls)
    }

    @Test fun open_guide_ignored() {
        commands.handle(PhoneLink.Command.Open("GUIDE"))
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun open_unknown_ignored() {
        commands.handle(PhoneLink.Command.Open("NOPE"))
        commands.handle(PhoneLink.Command.Key(19))
        assertEquals(emptyList<String>(), calls)
    }
}
