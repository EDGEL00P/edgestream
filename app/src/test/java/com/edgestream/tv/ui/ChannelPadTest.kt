package com.edgestream.tv.ui

import com.edgestream.tv.data.db.ChannelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelPadTest {
    private fun ch(name: String, group: String = "", number: Int = 9000, epg: String? = null) =
        ChannelEntity(
            key = name, sourceId = 1, name = name, groupName = group, logo = null,
            streamUrl = "", epgId = epg, number = number, tvgId = epg,
        )

    private val bbc = ch("UK| BBC 1 HD", "EU | UK GENERAL ", epg = "bbc1.uk")
    private val racing = ch("AU| SKY RACING 1 HD", "AU | SPORT ", number = 7719, epg = "skyracing1.au")

    private fun <T> withPad(delayMs: Long = 60_000, block: suspend (ChannelPad, MutableList<Int>) -> T): T = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val tuned = mutableListOf<Int>()
        try {
            block(ChannelPad(scope, delayMs) { tuned += it }, tuned)
        } finally {
            scope.cancel()
        }
    }

    @Test fun index_mapsUkAndRacingLcns() {
        val index = buildLcnIndex(listOf(bbc, racing))
        assertEquals(bbc, index[101])
        assertEquals(racing, index[526])
    }

    @Test fun append_capsAtFourDigits() = withPad { pad, _ ->
        "12345".forEach(pad::append)
        assertEquals("1234", pad.state.value.digits)
    }

    @Test fun backspace_removesLast() = withPad { pad, _ ->
        pad.append('5'); pad.append('2'); pad.backspace()
        assertEquals("5", pad.state.value.digits)
    }

    @Test fun commit_tunesAndClears() = withPad { pad, tuned ->
        pad.setIndex(buildLcnIndex(listOf(bbc)))
        "101".forEach(pad::append)
        assertEquals(bbc, pad.state.value.match)
        pad.commit()
        assertEquals(listOf(101), tuned)
        assertEquals("", pad.state.value.digits)
    }

    @Test fun autoCommit_afterDelay() = withPad(delayMs = 20) { pad, tuned ->
        "526".forEach(pad::append)
        delay(150)
        assertEquals(listOf(526), tuned)
        assertEquals("", pad.state.value.digits)
    }

    @Test fun emptyIndex_noMatch_noCrash() = withPad { pad, tuned ->
        "101".forEach(pad::append)
        assertNull(pad.state.value.match)
        pad.commit()
        assertEquals(listOf(101), tuned)
    }
}
