package com.edgestream.tv.ui

import com.edgestream.tv.data.Catalog
import com.edgestream.tv.data.db.VodKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A tile in a Movies/Series row: a title from a curated list, matched to the library if it's there. */
data class BrowseItem(
    val id: String,
    val title: String,
    val year: String?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: String?,
    val releaseDate: String?,
    /** The provider's copy, when the library has this title (shown with a ★); otherwise it plays through TorBox. */
    val vodKey: String?,
    /** IMDb id from the catalogue, so TorBox lookups skip the name search. */
    val imdbId: String? = null,
)

data class BrowseShelf(val title: String, val items: List<BrowseItem>, val comingSoon: Boolean = false)

data class BrowsePage(
    val marquee: List<BrowseItem>,
    val shelves: List<BrowseShelf>,
    /** Universal collections from TMDB, by group (streaming services, studios, franchises...). */
    val catalogCollections: List<Pair<Catalog.Group, List<Catalog.Collection>>> = emptyList(),
    /** The viewer's own collections, narrowed to this side (films or shows). */
    val groups: List<com.edgestream.tv.data.CuratedCollections.Group> = emptyList(),
)

/** Builds the one-page Movies/Series layout from the universal catalogue (Cinemeta, plus TMDB with a key). */
object BrowseData {

    suspend fun load(vm: AppViewModel, kind: VodKind): BrowsePage = coroutineScope {
        val s = vm.settings.value
        val key = s.tmdbApiKey
        val token = s.tmdbReadToken
        val region = vm.countryCode
        val shelves = Catalog.shelves(movies = kind == VodKind.MOVIE, tmdbKey = key.isNotBlank() || token.isNotBlank())
        val cache = com.edgestream.tv.EdgeStreamApp.instance.cacheDir
        // One regional Discover row, driven by the viewer's region/language/certification.
        val targetRegion = s.contentRegion.ifBlank { region.orEmpty() }
        val regional = if (targetRegion.isBlank() || (key.isBlank() && token.isBlank())) null else async {
            BrowseShelf(
                "Popular in ${targetRegion.uppercase()}",
                Catalog.discover(
                    kind == VodKind.MOVIE, key, token, targetRegion,
                    s.contentLanguage, s.contentCertification, cache,
                ).map(::item),
            )
        }
        val servers = async {
            com.edgestream.tv.data.MediaServers.rows(kind == VodKind.MOVIE).map { r ->
                BrowseShelf(r.title, r.items.map { t -> BrowseItem("imdb:${t.imdbId}", t.name, t.year, t.poster, t.backdrop, t.overview, null, null, vodKey = null, imdbId = t.imdbId) })
            }
        }
        val movies = kind == VodKind.MOVIE
        val groups = if (key.isBlank()) emptyList() else com.edgestream.tv.data.CuratedCollections.load(cache).mapNotNull { g ->
            val folders = g.folders.mapNotNull { f -> f.copy(sources = f.sources.filter { it.movies == movies }).takeIf { it.sources.isNotEmpty() } }
            if (folders.isEmpty()) null else g.copy(folders = folders)
        }
        // The viewer's own collections are the page: generic catalogue rows only stand in when
        // they can't load (no TMDB key, offline with no cached copy).
        val curated = if (groups.isNotEmpty()) emptyList() else shelves.map { s -> async { s to Catalog.load(s, key, region, cache) } }.awaitAll()
            .mapNotNull { (shelf, titles) ->
                if (titles.isEmpty()) return@mapNotNull null
                // The catalogue's own order: it's ranked by popularity, rating or date already.
                BrowseShelf(shelf.label, titles.map(::item), comingSoon = shelf.comingSoon)
            }
        // The banner: the first collection folder's titles with wide art (The Spotlight leads).
        val marquee = if (groups.isEmpty()) curated.filterNot { it.comingSoon }.take(2).flatMap { it.items }
        else groups.first().folders.first().sources.first().let { com.edgestream.tv.data.CuratedCollections.titles(it, key, cache) }.map(::item)
        BrowsePage(
            marquee = marquee.filter { it.backdrop != null }.distinctBy { it.id }.take(10),
            // The viewer's own servers lead: their Continue Watching is the most personal row.
            shelves = servers.await() + listOfNotNull(regional?.await()?.takeIf { it.items.isNotEmpty() }) + curated,
            // The viewer's own collections replace the built-in ones when they load.
            catalogCollections = if (key.isBlank() || groups.isNotEmpty()) emptyList()
            else Catalog.collections(movies, region).groupBy { it.group }.toList(),
            groups = groups,
        )
    }

    /** Every title in a universal collection. */
    suspend fun collectionItems(vm: AppViewModel, kind: VodKind, c: Catalog.Collection): List<BrowseItem> =
        Catalog.loadCollection(c, kind == VodKind.MOVIE, vm.settings.value.tmdbApiKey, com.edgestream.tv.EdgeStreamApp.instance.cacheDir).map(::item)

    private fun item(t: Catalog.Title) =
        BrowseItem(t.id, t.title, t.year, t.poster, t.backdrop, t.overview, t.rating, t.releaseDate, vodKey = null, imdbId = t.imdbId)
}
