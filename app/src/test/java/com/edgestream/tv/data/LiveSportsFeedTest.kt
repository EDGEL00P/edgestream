package com.edgestream.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSportsFeedTest {
    // The feed is generic: it never looks inside games or races, so plain strings stand in.
    private val a = listOf("g1", "g2")

    private fun <T> feedTest(block: suspend CoroutineScope.(CoroutineScope) -> T): T = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { block(scope) } finally { scope.cancel() }
    }

    @Test fun emitsFetchedGames() = feedTest { scope ->
        val feed = LiveSportsFeed<String, String>(scope, { a }, { emptyList() }, { emptyList() }, periodMs = 10, initialDelayMs = 0)
        val sub = scope.launch { launch { feed.games.collect {} }; feed.loaded.collect {} }
        delay(60)
        assertEquals(a, feed.games.value)
        assertTrue(feed.loaded.value)
        sub.cancel()
    }

    @Test fun failureKeepsPrevious() = feedTest { scope ->
        var calls = 0
        val feed = LiveSportsFeed<String, String>(scope, { if (calls++ == 0) a else error("offline") }, { emptyList() }, { emptyList() }, periodMs = 10, initialDelayMs = 0)
        val sub = scope.launch { feed.games.collect {} }
        delay(80)
        assertTrue(calls > 1)
        assertEquals(a, feed.games.value)
        sub.cancel()
    }

    @Test fun initialGames_readWhenFeedStarts() = feedTest { scope ->
        var snapshot = emptyList<String>()
        val feed = LiveSportsFeed<String, String>(scope, { error("offline") }, { emptyList() }, { snapshot }, periodMs = 10, initialDelayMs = 0)
        snapshot = listOf("cached")
        val sub = scope.launch { feed.games.collect {} }
        // Wait for the result instead of a fixed sleep: a busy build machine can miss a 40 ms window.
        val seen = withTimeoutOrNull(2_000) { feed.games.first { it == listOf("cached") } }
        assertEquals(listOf("cached"), seen)
        sub.cancel()
    }

    @Test fun noSubscribers_stopsFetching() = feedTest { scope ->
        var calls = 0
        LiveSportsFeed<String, String>(scope, { calls++; a }, { emptyList() }, { emptyList() }, periodMs = 10, initialDelayMs = 0, sharing = SharingStarted.WhileSubscribed(0))
        delay(40)
        assertEquals(0, calls)
    }
}
