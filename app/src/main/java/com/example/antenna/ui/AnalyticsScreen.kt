package com.example.antenna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.antenna.data.Analytics
import com.example.antenna.data.Channel
import com.example.antenna.data.ChannelTime
import com.example.antenna.data.GuessReason
import com.example.antenna.data.GuessStats
import com.example.antenna.data.LengthTime
import com.example.antenna.data.OpenSource
import com.example.antenna.data.Prefetcher
import com.example.antenna.data.Prefs
import com.example.antenna.data.SITTING_DAYS
import com.example.antenna.data.SittingStats
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.WatchLogStore
import com.example.antenna.data.guessStats
import com.example.antenna.data.StatsFilter
import com.example.antenna.data.StatsFilter.Kind
import com.example.antenna.data.TopicTime
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.WatchStats
import com.example.antenna.data.analytics
import com.example.antenna.data.channelUrlOfKey
import com.example.antenna.data.formatWatchTime
import com.example.antenna.sponsorblock.SponsorCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Analytics tab: what your watch history says about your watching, worked out on the
 * phone (data/WatchStats.kt). Tiles for the headline numbers, then one chart per question,
 * each a single measure in one colour.
 *
 * Tapping a bar or a row narrows the whole tab to its videos: a day, an hour, a channel, how
 * far you got, a topic, a category, or a kind of segment SponsorBlock skipped. Picks in
 * different cards combine. Each card still lists everything it could pick, from the videos
 * the other picks leave, with its own pick marked; tapping that again, its × in the bar at
 * the top, or Back takes it off.
 */
@Composable
fun AnalyticsTab(onOpenChannel: (Channel) -> Unit, onSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { WatchHistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val loaded by store.loaded.collectAsStateWithLifecycle()
    // How the app's guesses at what you'd open went (Prefetcher), and whether it's on (read
    // each time the tab is shown, so the Settings switch applies straight away).
    val guessLog by remember(context) { Prefetcher.get(context).log }.collectAsStateWithLifecycle()
    val ready = remember(guessLog) { guessStats(guessLog, System.currentTimeMillis()) }
    val prefetchOn = remember(context) { Prefs(context).prefetch }
    // What was actually played, and where each video was opened from (the watch log), and
    // your subscriptions (subscribed or not; the ones you've stopped watching).
    val spans by remember(context) { WatchLogStore.get(context).spans }.collectAsStateWithLifecycle()
    val subscriptions = remember(context) { SubscriptionStore.get(context) }
    val channels by subscriptions.channels.collectAsStateWithLifecycle()
    /** What the tab is narrowed to, in the order picked (Back takes off the last). */
    var filters by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(emptyList<StatsFilter>()) }
    BackHandler(enabled = filters.isNotEmpty()) { filters = filters.dropLast(1) }
    // A pick replaces what its card had picked; picking the same again takes it off.
    val pick = remember {
        { filter: StatsFilter ->
            val others = filters.filter { it.kind != filter.kind }
            filters = if (filters.any { it.kind == filter.kind && it.key == filter.key }) others else others + filter
        }
    }
    val unpick = remember { { filter: StatsFilter -> filters = filters.filter { it.kind != filter.kind } } }

    // Worked out off the main thread: it goes through up to 1000 videos (once more for each
    // pick), and the history changes every few seconds while a video plays.
    val numbers by produceState<Analytics?>(null, entries, filters, loaded, spans, channels) {
        if (!loaded) return@produceState // not "Nothing to show yet" while the history loads
        value = withContext(Dispatchers.Default) {
            analytics(entries.values, filters, System.currentTimeMillis(), subscriptions = channels, spans = spans)
        }
    }
    val n = numbers ?: return // a moment, the first time

    if (n.all.videos == 0) {
        EmptyMessage("Nothing to show yet", "Watch a few videos and what you watch, and when, shows up here.", modifier)
        return
    }

    val wide = isWideWindow()
    val grid = rememberLazyStaggeredGridState()
    // Something new picked: show what's picked at the top, and the tiles' new numbers. (Not
    // when a pick is taken off: that keeps you where you are.)
    val seen = remember { HashSet(n.filters) }
    LaunchedEffect(n.filters) {
        if (!seen.containsAll(n.filters)) grid.scrollToItem(0)
        seen.clear()
        seen += n.filters
    }
    // Masonry on big screens: each column stacks its cards, so a tall card doesn't leave a
    // gap beside a short one.
    LazyVerticalStaggeredGrid(
        columns = if (wide) StaggeredGridCells.Adaptive(minSize = 420.dp) else StaggeredGridCells.Fixed(1),
        modifier = modifier,
        state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalItemSpacing = 16.dp,
    ) {
        if (n.filters.isNotEmpty()) {
            item(key = "filters", span = StaggeredGridItemSpan.FullLine) {
                FilterBar(n.filters, onOpenChannel, onSearch, onRemove = unpick)
            }
        }
        if (n.shown.videos == 0) {
            item(key = "none", span = StaggeredGridItemSpan.FullLine) {
                Text(
                    "None of your videos match all of these. Take one off to see more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            item(key = "tiles", span = StaggeredGridItemSpan.FullLine) { Tiles(n.shown, n.all, filtered = n.filters.isNotEmpty(), wide) }
            item(key = "perDay") { PerDayCard(n.forCard(Kind.Day), n.picked(Kind.Day), pick) }
            item(key = "channels") { TopChannelsCard(n.forCard(Kind.Channel).topChannels, n.picked(Kind.Channel), pick) }
            item(key = "skipped") {
                SkippedCard(n.forCard(Kind.Skipped), n.picked(Kind.Skipped), narrowed = n.filters.any { it.kind != Kind.Skipped }, pick)
            }
            // The app's own numbers, not the history's: picks can't narrow them.
            if (n.filters.isEmpty()) item(key = "ready") { ReadyCard(ready, prefetchOn) }
            item(key = "sittings") { SittingsCard(n.sittings, narrowed = n.filters.isNotEmpty()) }
            item(key = "byHour") { ByHourCard(n.forCard(Kind.Hour).byHour, n.picked(Kind.Hour), pick) }
            item(key = "subscriptions") {
                SubscriptionsCard(
                    n.forCard(Kind.Subscribed),
                    n.picked(Kind.Subscribed),
                    pick,
                    // Which you've stopped watching: about all of them, so not with picks.
                    unwatched = n.unwatchedSubscriptions,
                    showUnwatched = n.filters.isEmpty(),
                    onSubscribe = { c -> c.url?.let { subscriptions.subscribe(Channel(it, c.name)) } },
                    onUnsubscribe = { c -> subscriptions.unsubscribe(c.url) },
                )
            }
            item(key = "source") { SourceCard(n.forCard(Kind.Source), n.picked(Kind.Source), pick) }
            item(key = "length") { LengthCard(n.forCard(Kind.Length).byLength, n.picked(Kind.Length), pick) }
            item(key = "age") { AgeCard(n.forCard(Kind.Age), n.picked(Kind.Age), pick) }
            item(key = "completion") { CompletionCard(n.forCard(Kind.Completion).completion, n.picked(Kind.Completion), pick) }
            item(key = "topics") { TopicsCard(n.forCard(Kind.Topic).topics, n.picked(Kind.Topic), pick) }
            item(key = "categories") { CategoriesCard(n.forCard(Kind.Category), n.picked(Kind.Category), pick) }
            item(key = "about", span = StaggeredGridItemSpan.FullLine) {
                val from = if (n.filters.isEmpty()) "From the last ${n.all.videos} videos in your history"
                else "From the ${videoCount(n.shown.videos)} that match, of the last ${n.all.videos} in your history"
                Text(
                    "$from, on this phone only. Watch time is how far into each video you got, counted on the day " +
                        "you last watched it. Topics come from titles and the uploaders' tags. Sittings, and where you " +
                        "opened videos from, count from when the app started recording them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Keeps the picks across rotation and process death. */
private val FiltersSaver = listSaver<List<StatsFilter>, String>(
    save = { StatsFilter.save(it) },
    restore = { StatsFilter.restore(it) },
)

/**
 * Above the numbers while anything is picked: one row for each pick, saying what it is, with
 * a way there for a channel (its page) or a topic (search for it), and × to take it off.
 */
@Composable
private fun FilterBar(
    filters: List<StatsFilter>,
    onOpenChannel: (Channel) -> Unit,
    onSearch: (String) -> Unit,
    onRemove: (StatsFilter) -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Column {
            filters.forEachIndexed { i, filter ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            filter.kind.title(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(filter.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when (filter.kind) {
                        Kind.Channel -> channelUrlOfKey(filter.key)?.let { url ->
                            TextButton(onClick = { onOpenChannel(Channel(url, filter.label)) }) { Text("Open channel") }
                        }
                        Kind.Topic -> TextButton(onClick = { onSearch(filter.label) }) { Text("Search") }
                        else -> Unit
                    }
                    IconButton(onClick = { onRemove(filter) }) {
                        Icon(Icons.Default.Close, contentDescription = "Show all, not only ${filter.label}")
                    }
                }
            }
        }
    }
}

/** What a pick is, over its name in the bar at the top. */
private fun Kind.title(): String = when (this) {
    Kind.Day -> "Day"
    Kind.Hour -> "Hour"
    Kind.Channel -> "Channel"
    Kind.Subscribed -> "Subscriptions"
    Kind.Completion -> "How far you got"
    Kind.Length -> "Length"
    Kind.Source -> "Opened from"
    Kind.Age -> "How new"
    Kind.Topic -> "Topic"
    Kind.Category -> "Category"
    Kind.Skipped -> "Skipped"
}

// --- Tiles -------------------------------------------------------------------------

/** A headline number: its value, what it is, and how the last 7 days compare with the 7 before. */
private class TileData(val value: String, val label: String, val week: String? = null)

@Composable
private fun Tiles(stats: WatchStats, all: WatchStats, filtered: Boolean, wide: Boolean) {
    val tiles = listOf(
        TileData(stats.videos.toString(), "videos watched", weekTrend(stats.thisWeek.videos.toLong(), stats.lastWeek.videos.toLong()) { "$it" }),
        TileData(formatWatchTime(stats.watchedMs), "watch time", weekTrend(stats.thisWeek.watchedMs, stats.lastWeek.watchedMs, ::formatWatchTime)),
        if (filtered) TileData("${(stats.watchedMs * 100 / all.watchedMs.coerceAtLeast(1))}%", "of all your watch time")
        else TileData(stats.channels.toString(), "channels"),
        TileData("${(stats.finishedShare * 100).toInt()}%", "watched to the end"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(if (wide) 4 else 2).forEach { row ->
            // As tall as the tallest, so the row's edges line up.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun Tile(tile: TileData, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            Text(tile.value, style = MaterialTheme.typography.headlineSmall)
            Text(tile.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tile.week?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** "Last 7 days: 3 h, up 20%": the last 7 days, and how they compare with the 7 before. */
private fun weekTrend(thisWeek: Long, lastWeek: Long, format: (Long) -> String): String {
    val change = when {
        lastWeek <= 0 -> ""
        thisWeek == lastWeek -> ", as the week before"
        thisWeek > lastWeek -> ", up ${(thisWeek - lastWeek) * 100 / lastWeek}%"
        else -> ", down ${(lastWeek - thisWeek) * 100 / lastWeek}%"
    }
    return "Last 7 days: ${format(thisWeek)}$change"
}

/**
 * The cards' colour: the darkest container colour, so they sit in the page (black in dark
 * mode, white in light) rather than as grey boxes; a thin outline marks their edges.
 */
@Composable
private fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainerLowest

@Composable
private fun cardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

// --- Cards ---------------------------------------------------------------------------
//
// Each card gets its numbers (Analytics.forCard), what's picked in it, if anything, and
// onPick for a tap on one of its bars or rows.

@Composable
private fun ChartCard(title: String, caption: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            // The value of the picked bar, or what the chart shows: always one line, so
            // tapping doesn't move the chart.
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
private fun PerDayCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val dayFormat = remember { DateTimeFormatter.ofPattern("MMM d") }
    val fullFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val days = stats.perDay
    fun date(i: Int) = stats.firstDay.plusDays(i.toLong())
    val selected = picked?.key?.toLongOrNull()?.let { (it - stats.firstDay.toEpochDay()).toInt() }?.takeIf { it in days.indices }
    val caption = selected?.let { i -> "${date(i).format(fullFormat)}: ${formatWatchTime(days[i])}" }
        ?: "Total ${formatWatchTime(days.sum())} · busiest day ${formatWatchTime(days.max())}"
    ChartCard("Watch time, last 30 days", caption) {
        BarChart(
            values = days,
            selected = selected,
            onSelect = { i -> onPick(StatsFilter(Kind.Day, date(i).toEpochDay().toString(), date(i).format(fullFormat))) },
            description = "Watch time per day for the last 30 days, ${formatWatchTime(days.sum())} in total",
        )
        AxisLabels(
            listOf(
                stats.firstDay.format(dayFormat),
                stats.firstDay.plusDays(15).format(dayFormat),
                "Today",
            ),
        )
    }
}

@Composable
private fun ByHourCard(byHour: List<Long>, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()?.takeIf { it in byHour.indices }
    val busiest = byHour.indices.maxBy { byHour[it] }
    val caption = selected?.let { h -> "${hourRange(h)}: ${formatWatchTime(byHour[h])}" }
        ?: "Most often around ${hourLabel(busiest)}"
    ChartCard("When you watch", caption) {
        BarChart(
            values = byHour,
            selected = selected,
            onSelect = { h -> onPick(StatsFilter(Kind.Hour, h.toString(), hourRange(h))) },
            description = "Watch time by hour of the day, most around ${hourLabel(busiest)}",
        )
        AxisLabels(listOf("0:00", "6:00", "12:00", "18:00", "24:00"))
    }
}

private fun hourLabel(hour: Int) = "$hour:00"

private fun hourRange(hour: Int) = "${hourLabel(hour)}–${hourLabel((hour + 1) % 24)}"

private fun videoCount(n: Int) = "$n video${if (n == 1) "" else "s"}"

@Composable
private fun TopChannelsCard(channels: List<ChannelTime>, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = channels.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Top channels", if (selected == null) "By watch time · tap one for its numbers" else "Tap it again for all channels") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            channels.forEach { c ->
                HorizontalBar(
                    label = c.name,
                    value = "${formatWatchTime(c.watchedMs)} · ${videoCount(c.videos)}",
                    fraction = c.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Channel, c.key, c.name)) },
                    clickLabel = "Show ${c.name}'s numbers",
                    dimmed = selected != null && selected != c.key,
                )
            }
        }
    }
}

/**
 * What SponsorBlock skipped: the time and segments in all, then by kind (sponsors, self
 * promotion…). A kind picks the videos it was skipped in. YouTube's own ads are never loaded,
 * so these are the only ones there are to count.
 */
@Composable
private fun SkippedCard(stats: WatchStats, picked: StatsFilter?, narrowed: Boolean, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = stats.skips.maxOfOrNull { it.skippedMs }?.coerceAtLeast(1) ?: 1
    val caption = when {
        stats.skips.isEmpty() -> "Time saved by skipping segments"
        selected == null -> "By kind · tap one for its numbers"
        else -> "Tap it again for all videos"
    }
    ChartCard("Skipped with SponsorBlock", caption) {
        if (stats.skips.isEmpty()) {
            Text(
                if (narrowed) "Nothing was skipped in these videos."
                else "Nothing skipped yet. The sponsors and other segments SponsorBlock skips in the videos you watch show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(formatWatchTime(stats.skippedMs), style = MaterialTheme.typography.headlineSmall)
            Text(
                "${stats.skippedSegments} segment${if (stats.skippedSegments == 1) "" else "s"} in ${videoCount(stats.videosWithSkips)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.skips.forEach { s ->
                    val name = SponsorCategory.shortLabelFor(s.category)
                    HorizontalBar(
                        label = name,
                        value = "${formatWatchTime(s.skippedMs)} · ${videoCount(s.videos)}",
                        fraction = s.skippedMs.toFloat() / most,
                        onClick = { onPick(StatsFilter(Kind.Skipped, s.category, name)) },
                        clickLabel = "Show the numbers of the videos with $name skipped",
                        dimmed = selected != null && selected != s.category,
                    )
                }
            }
        }
    }
}

/**
 * How the app's guesses at what you'd open went (Prefetcher): how many of the videos you
 * opened were ready, how often each kind of guess was opened, and what it does now.
 */
@Composable
private fun ReadyCard(stats: GuessStats, on: Boolean) {
    ChartCard("Ready before you tapped", "Last 30 days · guessed when the app opens") {
        if (stats.opened == 0 && stats.fetched == 0) {
            Text(
                if (on) "Nothing yet. When the app opens, it fetches the details of a few videos you're likely to " +
                    "watch, so they start sooner. How often it's right shows up here."
                else "Off in Settings (Get likely videos ready).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("${stats.ready} of ${stats.opened}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "videos you opened were ready" + if (stats.savedMs > 0) ", about ${formatSaved(stats.savedMs)} saved" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // How often each kind of guess was opened, of those fetched.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.byReason.forEach { r ->
                    HorizontalBar(
                        label = r.reason.label(),
                        value = "${r.used} of ${r.fetched} opened",
                        fraction = r.used.toFloat() / r.fetched.coerceAtLeast(1),
                    )
                }
            }
            Text(
                readyStatus(stats, on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun GuessReason.label(): String = when (this) {
    GuessReason.Unfinished -> "Unfinished videos"
    GuessReason.WatchLater -> "Watch later"
    GuessReason.ForYou -> "For you"
    GuessReason.NewUpload -> "New uploads"
    GuessReason.NextInLine -> "Next in line"
}

/** What the guessing does now, and why. */
private fun readyStatus(stats: GuessStats, on: Boolean): String {
    val now = System.currentTimeMillis()
    return when {
        !on -> "Off in Settings: nothing is fetched."
        now < stats.pausedUntil -> {
            val until = Instant.ofEpochMilli(stats.pausedUntil).atZone(ZoneId.systemDefault())
            "Paused until ${until.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))}: YouTube was limiting requests."
        }
        stats.fetching == 0 ->
            "Fetching none for now: ${stats.recentUsed} of its last ${stats.recent} first guesses were opened. " +
                "It keeps guessing without fetching, and starts again when its guesses are opened more."
        stats.fetching == 1 -> "Fetches its likeliest guess when the app opens; more once they're opened often enough."
        else -> "Fetches its ${stats.fetching} likeliest guesses when the app opens, as they're opened often enough."
    }
}

/** "12 s", or "2 min" from a minute on. */
private fun formatSaved(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return if (seconds < 60) "$seconds s" else formatWatchTime(ms)
}

// --- From the watch log, and your subscriptions --------------------------------------------

/**
 * How you sit down to watch, from the watch log: sittings (stretches of watching less than
 * half an hour apart), what was actually played, days in a row, and videos you played part of
 * again. With picks, those of the videos shown.
 */
@Composable
private fun SittingsCard(s: SittingStats?, narrowed: Boolean) {
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val since = s?.let { Instant.ofEpochMilli(it.since).atZone(ZoneId.systemDefault()).toLocalDate().format(dates) }
    ChartCard("Sittings", if (since != null) "Last $SITTING_DAYS days · recorded since $since" else "Recorded as you watch") {
        if (s == null) {
            Note(
                if (narrowed) "Nothing recorded for these videos yet."
                else "Recorded from now on: how often you sit down to watch, for how long, what you actually play, " +
                    "and the videos you watch again.",
            )
        } else {
            if (s.sittings == 0) {
                Text("None in the last $SITTING_DAYS days", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("${s.sittings} sitting${if (s.sittings == 1) "" else "s"}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${formatWatchTime(s.playedMs / s.sittings)} on average, ${perSitting(s.videos, s.sittings)} each",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact("Actually played", formatWatchTime(s.playedMs))
                Fact("Days in a row", "${s.currentStreak} now · ${s.longestStreak} at most")
                Fact("Played part of again", videoCount(s.rewatched))
            }
        }
    }
}

/** "1 video", "2.4 videos": how many videos a sitting has, on average. */
private fun perSitting(videos: Int, sittings: Int): String {
    val tenths = (videos * 10 + sittings / 2) / sittings
    return if (tenths <= 10) "1 video" else "${tenths / 10}.${tenths % 10} videos"
}

/**
 * Watch time from channels you subscribe to and from the rest (a pick), the channels you watch
 * most without subscribing (to subscribe), and, without picks, the subscriptions you haven't
 * watched in 3 months (to unsubscribe).
 */
@Composable
private fun SubscriptionsCard(
    stats: WatchStats,
    picked: StatsFilter?,
    onPick: (StatsFilter) -> Unit,
    unwatched: List<Channel>?,
    showUnwatched: Boolean,
    onSubscribe: (ChannelTime) -> Unit,
    onUnsubscribe: (Channel) -> Unit,
) {
    val selected = picked?.key
    val total = (stats.subscribed.watchedMs + stats.notSubscribed.watchedMs).coerceAtLeast(1)
    val most = maxOf(stats.subscribed.watchedMs, stats.notSubscribed.watchedMs).coerceAtLeast(1)
    ChartCard("Subscriptions", if (selected == null) "By watch time · tap one for its numbers" else "Tap it again for all channels") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(StatsFilter.SUBSCRIBED, "Channels you subscribe to", stats.subscribed),
                Triple(StatsFilter.NOT_SUBSCRIBED, "Other channels", stats.notSubscribed),
            ).forEach { (key, label, share) ->
                HorizontalBar(
                    label = label,
                    value = "${formatWatchTime(share.watchedMs)} · ${share.watchedMs * 100 / total}%",
                    fraction = share.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Subscribed, key, label)) },
                    clickLabel = "Show their numbers",
                    dimmed = selected != null && selected != key,
                )
            }
        }
        if (stats.unsubscribedChannels.isNotEmpty()) {
            SubHeader("You watch, but don't subscribe to")
            stats.unsubscribedChannels.forEach { c ->
                ActionRow(c.name, "${formatWatchTime(c.watchedMs)} · ${videoCount(c.videos)}", "Subscribe") { onSubscribe(c) }
            }
        }
        if (showUnwatched) {
            SubHeader("Not watched in 3 months")
            when {
                unwatched == null -> Note("Too early to tell: your history doesn't go back 3 months yet.")
                unwatched.isEmpty() -> Note("None: you've watched all of them lately.")
                else -> {
                    var showAll by rememberSaveable { mutableStateOf(false) }
                    (if (showAll) unwatched else unwatched.take(5)).forEach { c ->
                        ActionRow(c.name, null, "Unsubscribe") { onUnsubscribe(c) }
                    }
                    if (!showAll && unwatched.size > 5) {
                        TextButton(onClick = { showAll = true }) { Text("Show all ${unwatched.size}") }
                    }
                }
            }
        }
    }
}

/** Where you opened videos from, by watch time: a pick. Recorded from when the app started keeping it. */
@Composable
private fun SourceCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = stats.bySource.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    val caption = when {
        selected != null -> "Tap it again for all videos"
        stats.withSource == 0 -> "Recorded from now on"
        stats.withSource < stats.videos -> "By watch time · ${stats.withSource} of ${stats.videos} videos"
        else -> "By watch time · tap one for its numbers"
    }
    ChartCard("Where you open videos", caption) {
        if (stats.bySource.isEmpty()) {
            Note("Where you open each video from (For you, Subscriptions, Up next, search…) is recorded from now on.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.bySource.forEach { s ->
                val label = s.source.label()
                HorizontalBar(
                    label = label,
                    value = "${formatWatchTime(s.watchedMs)} · ${videoCount(s.videos)}",
                    fraction = s.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Source, s.source.name, label)) },
                    clickLabel = "Show the numbers of the videos opened from $label",
                    dimmed = selected != null && selected != s.source.name,
                )
            }
        }
    }
}

private fun OpenSource.label(): String = when (this) {
    OpenSource.ForYou -> "For you"
    OpenSource.Subscriptions -> "Subscriptions"
    OpenSource.WatchLater -> "Watch later"
    OpenSource.History -> "History"
    OpenSource.Search -> "Search"
    OpenSource.Channel -> "Channel pages"
    OpenSource.UpNext -> "Up next"
    OpenSource.Link -> "Links"
}

/** Watch time by how long videos are, and how many of each you finish: a pick. */
@Composable
private fun LengthCard(byLength: List<LengthTime>, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()
    val most = byLength.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Video length", if (selected == null) "By watch time · tap one for its numbers" else "Tap it again for all lengths") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            byLength.forEachIndexed { i, l ->
                val label = WatchStats.LENGTH_LABELS[i]
                HorizontalBar(
                    label = label,
                    value = if (l.videos == 0) "none" else "${formatWatchTime(l.watchedMs)} · ${l.finished * 100 / l.videos}% finished",
                    fraction = l.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Length, i.toString(), label)) },
                    clickLabel = "Show the numbers of these videos",
                    dimmed = selected != null && selected != i,
                )
            }
        }
    }
}

/** How new videos were when you first watched them, by how many: a pick. */
@Composable
private fun AgeCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()
    val most = stats.byAge.maxOfOrNull { it.videos }?.coerceAtLeast(1) ?: 1
    val median = stats.medianAgeMs
    val caption = when {
        selected != null -> "Tap it again for all videos"
        median == null -> "From when videos came out"
        else -> "Usually ${formatAge(median)} old when you watch"
    }
    ChartCard("How new", caption) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.byAge.forEachIndexed { i, a ->
                val label = WatchStats.AGE_LABELS[i]
                HorizontalBar(
                    label = label,
                    value = "${videoCount(a.videos)} · ${formatWatchTime(a.watchedMs)}",
                    fraction = a.videos.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Age, i.toString(), label)) },
                    clickLabel = "Show the numbers of these videos",
                    dimmed = selected != null && selected != i,
                )
            }
        }
    }
}

/** "5 hours", "2 days", "3 months": how old a video was. */
private fun formatAge(ms: Long): String {
    val hours = ms / 3_600_000
    val days = hours / 24
    fun count(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"}"
    return when {
        hours < 1 -> "under an hour"
        days < 1 -> count(hours, "hour")
        days < 30 -> count(days, "day")
        days < 365 -> count(days / 30, "month")
        else -> count(days / 365, "year")
    }
}

/** A heading inside a card, over a few rows. */
@Composable
private fun SubHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))

/** A line of small print. */
@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A number in a card: what it is on the left, its value on the right. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A channel with something to do about it (Subscribe, Unsubscribe). */
@Composable
private fun ActionRow(name: String, detail: String?, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun TopicsCard(topics: List<TopicTime>, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = topics.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Topics", if (selected == null) "By watch time · tap one for its numbers" else "Tap it again for all topics") {
        if (topics.isEmpty()) {
            Text(
                "No topic has come up in two videos yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            topics.forEach { t ->
                HorizontalBar(
                    label = t.name,
                    value = "${formatWatchTime(t.watchedMs)} · ${videoCount(t.videos)}",
                    fraction = t.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Topic, t.key, t.name)) },
                    clickLabel = "Show the numbers for ${t.name}",
                    dimmed = selected != null && selected != t.key,
                )
            }
        }
    }
}

@Composable
private fun CategoriesCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val categories = stats.categories
    val most = categories.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    val caption = when {
        selected != null -> "Tap it again for all categories"
        stats.withCategory == stats.videos -> "By watch time · tap one for its numbers"
        else -> "By watch time · ${stats.withCategory} of ${stats.videos} videos have one"
    }
    ChartCard("Categories", caption) {
        if (categories.isEmpty()) {
            Text(
                "Categories are saved for videos you watch from now on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            categories.forEach { c ->
                HorizontalBar(
                    label = c.name,
                    value = "${formatWatchTime(c.watchedMs)} · ${videoCount(c.videos)}",
                    fraction = c.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Category, c.key, c.name)) },
                    clickLabel = "Show the numbers for ${c.name}",
                    dimmed = selected != null && selected != c.key,
                )
            }
        }
    }
}

@Composable
private fun CompletionCard(completion: List<Int>, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()
    val total = completion.sum().coerceAtLeast(1)
    val most = completion.max().coerceAtLeast(1)
    ChartCard("How much you finish", if (selected == null) "Videos by how far you got into them" else "Tap it again for all videos") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            completion.forEachIndexed { i, n ->
                val label = WatchStats.COMPLETION_LABELS[i]
                HorizontalBar(
                    label = label,
                    value = "$n · ${n * 100 / total}%",
                    fraction = n.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Completion, i.toString(), label)) },
                    clickLabel = "Show the numbers of these videos",
                    dimmed = selected != null && selected != i,
                )
            }
        }
    }
}

// --- Marks -----------------------------------------------------------------------------

/**
 * Vertical bars of one measure: thin, 2 dp apart, rounded only at the top (the data end),
 * standing on a faint baseline. Tapping a bar picks it: the others dim, and the card's
 * caption shows its value.
 */
@Composable
private fun BarChart(
    values: List<Long>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    height: Dp = 140.dp,
) {
    val bar = MaterialTheme.colorScheme.primary
    val dim = bar.copy(alpha = 0.35f)
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val select by rememberUpdatedState(onSelect) // the latest, without restarting the tap detection
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    val i = (offset.x / (size.width.toFloat() / values.size)).toInt()
                    if (i in values.indices) select(i)
                }
            },
    ) {
        val gap = 2.dp.toPx()
        val slot = size.width / values.size
        val radius = 4.dp.toPx().coerceAtMost((slot - gap) / 2)
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val h = (size.height * v / max).coerceAtLeast(2.dp.toPx())
            val color = if (selected == null || selected == i) bar else dim
            topRoundedBar(Offset(i * slot + gap / 2, size.height - h), Size(slot - gap, h), radius, color)
        }
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.topRoundedBar(topLeft: Offset, size: Size, radius: Float, color: Color) {
    val r = CornerRadius(radius, radius)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = topLeft.x, top = topLeft.y, right = topLeft.x + size.width, bottom = topLeft.y + size.height,
                topLeftCornerRadius = r, topRightCornerRadius = r,
                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

/** Labels spread along a chart's bottom edge, first at the left and last at the right. */
@Composable
private fun AxisLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A labelled horizontal bar: the name and its value above (in text colours), a thin bar
 * below, rounded at its end, on a faint track for the whole width.
 */
@Composable
private fun HorizontalBar(
    label: String,
    value: String,
    fraction: Float,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    /** Faded, beside the picked one. */
    dimmed: Boolean = false,
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val bar = MaterialTheme.colorScheme.primary.let { if (dimmed) it.copy(alpha = 0.35f) else it }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "  $value",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(track),
        ) {
            // Nothing for zero; anything else at least visible.
            if (fraction > 0f) Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(bar),
            )
        }
    }
}

/** Material's "bar chart" icon, for the tab (the core icon set doesn't have it). */
internal val AnalyticsIcon: ImageVector by lazy {
    materialIcon(name = "Filled.BarChart") {
        materialPath {
            moveTo(5f, 9.2f); horizontalLineToRelative(3f); verticalLineTo(19f); horizontalLineTo(5f); close()
            moveTo(10.6f, 5f); horizontalLineToRelative(2.8f); verticalLineToRelative(14f); horizontalLineToRelative(-2.8f); close()
            moveTo(16.2f, 13f); horizontalLineTo(19f); verticalLineToRelative(6f); horizontalLineToRelative(-2.8f); close()
        }
    }
}
