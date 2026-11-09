package com.edgestream.tv.data

import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchKitTest {
    @Test fun suggestionShowsTheCountryChannelNumberNotTheProviders() {
        val skyRacing = ChannelEntity(
            key = "1:L:1", sourceId = 1, name = "AU| SKY RACING 1 HD", groupName = "AU | SPORT ", logo = null,
            streamUrl = "", epgId = "skyracing1.au", number = 7719, tvgId = "skyracing1.au",
        )
        val detail = SearchKit.suggestions("sky racing", listOf(skyRacing), emptyList()).single { it.channelKey == skyRacing.key }.detail
        assertEquals(true, detail.endsWith("526"))
    }
}
