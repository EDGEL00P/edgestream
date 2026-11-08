package com.edgestream.tv.data

/** Free public RSS feeds (ported from EdgeLauncher). Headline titles only. */
enum class NewsSource(val country: String, val label: String, val url: String, val sport: Boolean = false) {
    AbcAu("AU", "ABC News", "https://www.abc.net.au/news/feed/51120/rss.xml"),
    AbcSport("AU", "ABC Sport", "https://www.abc.net.au/news/feed/2942460/rss.xml", sport = true),
    Rnz("NZ", "RNZ News", "https://www.rnz.co.nz/rss/national.xml"),
    RnzSport("NZ", "RNZ Sport", "https://www.rnz.co.nz/rss/sport.xml", sport = true),
    BbcNews("GB", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
    BbcSport("GB", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml", sport = true),
    Cbc("CA", "CBC News", "https://www.cbc.ca/webfeed/rss/rss-topstories"),
    CbcSports("CA", "CBC Sports", "https://www.cbc.ca/webfeed/rss/rss-sports", sport = true),
    Npr("US", "NPR News", "https://feeds.npr.org/1001/rss.xml"),
    Espn("US", "ESPN", "https://www.espn.com/espn/rss/news", sport = true),
}

data class Headline(val source: String, val title: String)

object News {
    private val block = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val title = Regex("<title\\b[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val cdata = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val numeric = Regex("&#(x?)([0-9a-fA-F]+);")

    /** Item/entry titles from an RSS or Atom document; anything else yields nothing. */
    fun parseTitles(xml: String): List<String> = block.findAll(xml).mapNotNull { m ->
        title.find(m.groupValues[2])?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() }
    }.toList()

    private fun clean(raw: String): String {
        val text = cdata.replace(raw) { it.groupValues[1] }.replace(Regex("<[^>]+>"), "")
        return numeric.replace(text) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1] == "x") 16 else 10) ?: return@replace m.value
            String(Character.toChars(code))
        }.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()
    }

    /** The country's general and sport feeds (Australia when unknown). */
    fun sourcesFor(countryCode: String?): List<NewsSource> =
        NewsSource.entries.filter { it.country == countryCode?.uppercase() }.ifEmpty { NewsSource.entries.filter { it.country == "AU" } }

    suspend fun fetch(source: NewsSource): List<Headline> = Http.openSuspend(source.url) { input ->
        parseTitles(Http.maybeGunzip(input).reader().readText()).take(12).map { Headline(source.label, it) }
    }
}
