package com.edgestream.tv.ui.shell

import com.edgestream.tv.data.PublicApis
import com.edgestream.tv.data.Sports
import com.edgestream.tv.data.Trackers
import com.edgestream.tv.data.db.ChannelEntity
import com.edgestream.tv.data.db.ProgressEntity
import com.edgestream.tv.data.db.VodEntity

/** One card in the preview band. [id] is stable for list keys and focus restore. */
sealed interface BandItem {
    val id: String
    data class Game(val game: Sports.Game) : BandItem { override val id get() = "game:${game.id}" }
    data class Channel(val channel: ChannelEntity, val now: String?, val row: List<ChannelEntity>) : BandItem {
        override val id get() = "ch:${channel.key}"
    }
    data class Resume(val p: ProgressEntity) : BandItem { override val id get() = "resume:${p.key}" }
    data class Vod(val item: VodEntity) : BandItem { override val id get() = "vod:${item.key}" }
    data class Alert(val warning: Trackers.Warning) : BandItem { override val id get() = "alert:${warning.title}" }
    data class Day(val day: PublicApis.DayPoint) : BandItem { override val id get() = "day:${day.date}" }
    data class Watch(val item: com.edgestream.tv.data.WatchItem) : BandItem { override val id get() = "watch:${item.id}" }
}

data class BandRow(val title: String, val items: List<BandItem>)

/** Everything the band can show, gathered from the view model's existing flows (no new network calls). */
data class BandInputs(
    val games: List<Sports.Game>,
    val favourites: List<ChannelEntity>,
    val recent: List<ChannelEntity>,
    val nowTitle: (ChannelEntity) -> String?,
    val resume: List<ProgressEntity>,
    val latestMovies: List<VodEntity>,
    val latestSeries: List<VodEntity>,
    val alert: Trackers.Warning?,
    val weather: PublicApis.WeatherDetail?,
    /** The short forecast — shown only in the Weather band (one home per fact). */
    val forecast: List<PublicApis.DayPoint> = emptyList(),
    /** The active profile's saved titles (On Demand band, first row). */
    val watchList: List<com.edgestream.tv.data.WatchItem> = emptyList(),
)

private const val GAMES_PER_ROW = 10
private const val CARDS_PER_ROW = 12

/** The rows the band shows for a dock tile. Each thing appears in exactly one tile's band. */
fun bandRows(tile: Tile, i: BandInputs): List<BandRow> = when (tile) {
    Tile.LIVE -> {
        val (title, list) = if (i.favourites.isNotEmpty()) "Favourites" to i.favourites else "Recently watched" to i.recent
        val row = list.take(CARDS_PER_ROW)
        listOf(BandRow(title, row.map { BandItem.Channel(it, i.nowTitle(it), row) }))
    }
    Tile.SPORTS -> listOf(
        BandRow("On Now", i.games.filter { it.state == Sports.State.LIVE }.take(GAMES_PER_ROW).map(BandItem::Game)),
        BandRow(
            "Upcoming",
            i.games.filter { it.state == Sports.State.UPCOMING }.sortedBy { it.startMs }.take(GAMES_PER_ROW).map(BandItem::Game),
        ),
    )
    Tile.MOVIES -> listOf(
        BandRow("My List", i.watchList.take(CARDS_PER_ROW).map(BandItem::Watch)),
        BandRow("Continue watching", i.resume.take(CARDS_PER_ROW).map(BandItem::Resume)),
        BandRow("New movies", i.latestMovies.take(CARDS_PER_ROW).map(BandItem::Vod)),
        BandRow("New shows", i.latestSeries.take(CARDS_PER_ROW).map(BandItem::Vod)),
    )
    Tile.WEATHER -> listOf(
        BandRow("Warnings", listOfNotNull(i.alert?.let(BandItem::Alert))),
        BandRow("Forecast", i.forecast.take(5).map(BandItem::Day)),
    )
    Tile.SEARCH, Tile.SETTINGS, Tile.PHONE, Tile.APPS, Tile.CALENDAR -> emptyList()
}.filter { it.items.isNotEmpty() }
