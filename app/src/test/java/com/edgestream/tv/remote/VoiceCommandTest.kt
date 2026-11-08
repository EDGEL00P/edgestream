package com.edgestream.tv.remote

import com.edgestream.tv.remote.VoiceCommand.Open
import com.edgestream.tv.remote.VoiceCommand.Search
import com.edgestream.tv.remote.VoiceCommand.Tune
import com.edgestream.tv.remote.VoiceCommand.Watch
import com.edgestream.tv.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test fun questions() {
        assertEquals(VoiceCommand.WhatsOn("Fox League"), VoiceCommand.parse("What's on Fox League?"))
        assertEquals(VoiceCommand.WhatsOn("BBC One"), VoiceCommand.parse("what is on BBC One right now"))
        assertEquals(VoiceCommand.WhatsOn("ESPN"), VoiceCommand.parse("Hey, what's playing on channel ESPN"))
        assertEquals(VoiceCommand.WhatsOn(null), VoiceCommand.parse("What's on?"))
        assertEquals(VoiceCommand.WhatsOn(null), VoiceCommand.parse("what's on tonight"))
        assertEquals(VoiceCommand.Games, VoiceCommand.parse("Today's games"))
        assertEquals(VoiceCommand.Games, VoiceCommand.parse("what's on in sport"))
        assertEquals(VoiceCommand.Games, VoiceCommand.parse("Scores"))
        assertEquals(VoiceCommand.Team("Broncos"), VoiceCommand.parse("When do the Broncos play?"))
        assertEquals(VoiceCommand.Team("Storm"), VoiceCommand.parse("how are the Storm going"))
        assertEquals(VoiceCommand.Team("Lakers"), VoiceCommand.parse("What's the Lakers score"))
        assertEquals(VoiceCommand.Team("Man United"), VoiceCommand.parse("Man United score"))
    }

    @Test fun channelNumbers() {
        assertEquals(Tune(101), VoiceCommand.parse("101"))
        assertEquals(Tune(526), VoiceCommand.parse("Channel 526."))
        assertEquals(Tune(7), VoiceCommand.parse("put on channel 7"))
    }

    @Test fun watchByNameKeepsSpelling() {
        assertEquals(Watch("BBC One"), VoiceCommand.parse("Watch BBC One"))
        assertEquals(Watch("Sky Racing 1"), VoiceCommand.parse("please switch to Sky Racing 1"))
        assertEquals(Watch("ESPN"), VoiceCommand.parse("Hey, play ESPN"))
    }

    @Test fun places() {
        assertEquals(Open(Screen.LIVE), VoiceCommand.parse("open the guide"))
        assertEquals(Open(Screen.SPORTS), VoiceCommand.parse("Sports"))
        assertEquals(Open(Screen.RADAR), VoiceCommand.parse("show me weather"))
        assertEquals(Open(Screen.MOVIES), VoiceCommand.parse("go to movies"))
    }

    @Test fun everythingElseSearches() {
        assertEquals(Search("Brisbane Broncos"), VoiceCommand.parse("search for Brisbane Broncos"))
        assertEquals(Search("The Bear", guessed = true), VoiceCommand.parse("The Bear"))
        assertNull(VoiceCommand.parse("  "))
    }
}
