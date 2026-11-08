package com.edgestream.tv.ui

import com.edgestream.tv.data.ChannelTaxonomy
import com.edgestream.tv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapListsTest {
    private fun ch(name: String, group: String, epg: String? = null) =
        ChannelEntity(
            key = name, sourceId = 1, name = name, groupName = group, logo = null,
            streamUrl = "", epgId = epg, number = 9000, tvgId = epg,
        )

    private val news1 = ch("UK| SKY NEWS HD", "EU | UK NEWS ;VIP", "skynews.uk")
    private val news2 = ch("UK| BBC NEWS HD", "EU | UK NEWS ", "bbcnews.uk")
    private val news3 = ch("UK| GB NEWS", "Extra News", "gbnews.uk")

    // Built the way AppViewModel.byGroup builds it: one taxonomy bucket per channel.
    private val groups = listOf(news1, news2, news3).groupBy { ChannelTaxonomy.groupKey(it) }

    @Test fun multiGroupName_usesTaxonomyBucket() {
        val list = zapListFor(news1, groups)
        assertTrue(list.size > 1)
        assertTrue(list.any { it.key == news1.key })
    }

    @Test fun rawGroupName_wouldBeEmpty_taxonomyIsNot() {
        assertNull(groups[news2.groupName])
        assertTrue(zapListFor(news2, groups).size > 1)
    }

    @Test fun hiddenChannel_fallsBackToSelf() {
        val hidden = ch("UK| HIDDEN NEWS", "EU | UK NEWS ")
        assertEquals(listOf(hidden), zapListFor(hidden, emptyMap()))
    }

    @Test fun sameListFromAnyEntry() {
        assertSame(zapListFor(news1, groups), zapListFor(news1, groups))
    }
}
