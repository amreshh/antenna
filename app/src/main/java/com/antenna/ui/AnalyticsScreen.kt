package com.antenna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antenna.data.Analytics
import com.antenna.data.Channel
import com.antenna.data.ChannelTime
import com.antenna.data.ForYouFeedback
import com.antenna.data.ForYouFeedbackStore
import com.antenna.data.GuessMoment
import com.antenna.data.GuessReason
import com.antenna.data.GuessStats
import com.antenna.data.HiddenChannel
import com.antenna.data.NotInterested
import com.antenna.data.OpenSource
import com.antenna.data.Prefetcher
import com.antenna.data.Prefs
import com.antenna.data.SittingStats
import com.antenna.data.StatsFilter
import com.antenna.data.StatsFilter.Kind
import com.antenna.data.StatsRange
import com.antenna.data.SubscriptionStore
import com.antenna.data.WatchHistoryStore
import com.antenna.data.WatchLogStore
import com.antenna.data.WatchStats
import com.antenna.data.analytics
import com.antenna.data.channelUrlOfKey
import com.antenna.data.formatWatchTime
import com.antenna.data.guessStats
import com.antenna.data.hourIndex
import com.antenna.sponsorblock.SponsorCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Analytics tab: what your watch history says about your watching, worked out on the
 * phone (data/WatchStats.kt). A range at the top (7 days, 30 days, all time) that every card
 * follows, four tiles with the headline numbers, then the cards in sections: your viewing,
 * what you watch, how you find videos, and the app's own.
 *
 * Tapping a bar or a row (they have a ›) narrows the whole tab to its videos: a day, an hour,
 * a channel, how far you got, a category, or a kind of segment SponsorBlock skipped. Picks in
 * different cards combine. Each card still lists everything it could pick, from the videos
 * the other picks leave, with its own pick marked; tapping that again, its × in the bar at
 * the top, or Back takes it off.
 */
@Composable
fun AnalyticsTab(onOpenChannel: (Channel) -> Unit, modifier: Modifier = Modifier) {
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
    // What you hid from For you (Not interested, Don't recommend channel), to show again.
    val feedbackStore = remember(context) { ForYouFeedbackStore.get(context) }
    val hidden by feedbackStore.feedback.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(StatsRange.All) }
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
    // The watch time chart picks a day, or an hour of a day: what it picks replaces both.
    val pickTime = remember {
        { picks: List<StatsFilter> -> filters = filters.filter { it.kind != Kind.Day && it.kind != Kind.Hour } + picks }
    }
    // Another range: a day picked in the chart may not be in it.
    val pickRange = remember {
        { r: StatsRange ->
            range = r
            filters = filters.filter { it.kind != Kind.Day && it.kind != Kind.Hour }
        }
    }

    // Worked out off the main thread: it goes through up to 1000 videos (once more for each
    // pick), and the history changes every few seconds while a video plays.
    val numbers by produceState<Analytics?>(null, entries, filters, loaded, spans, channels, range) {
        if (!loaded) return@produceState // not "Nothing to show yet" while the history loads
        value = withContext(Dispatchers.Default) {
            analytics(entries.values, filters, System.currentTimeMillis(), subscriptions = channels, spans = spans, range = range)
        }
    }
    val n = numbers ?: return // a moment, the first time

    if (entries.isEmpty()) {
        EmptyMessage("Nothing to show yet", "Watch a few videos and what you watch, and when, shows up here.", modifier)
        return
    }

    val wide = isWideWindow()
    val grid = rememberLazyStaggeredGridState()
    // Tapping Analytics again: back to the top.
    val scrollToTop = LocalScrollToTop.current
    LaunchedEffect(grid, scrollToTop) { scrollToTop.collect { grid.animateScrollToItem(0) } }
    // Something new picked: show what's picked at the top, and the tiles' new numbers. (Not
    // when a pick is taken off: that keeps you where you are.)
    val seen = remember { HashSet(n.filters) }
    LaunchedEffect(n.filters) {
        if (!seen.containsAll(n.filters)) grid.scrollToItem(0)
        seen.clear()
        seen += n.filters
    }
    // Masonry on big screens: each column stacks its cards, so a tall card doesn't leave a
    // gap beside a short one. The section headings span both columns.
    LazyVerticalStaggeredGrid(
        columns = if (wide) StaggeredGridCells.Adaptive(minSize = 420.dp) else StaggeredGridCells.Fixed(1),
        modifier = modifier,
        state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalItemSpacing = 16.dp,
    ) {
        item(key = "range", span = StaggeredGridItemSpan.FullLine) { RangeBar(range, pickRange, scopeOf(n), wide) }
        if (n.filters.isNotEmpty()) {
            item(key = "filters", span = StaggeredGridItemSpan.FullLine) {
                FilterBar(n.filters, onOpenChannel, onRemove = unpick)
            }
        }
        when {
            n.all.videos == 0 -> item(key = "none", span = StaggeredGridItemSpan.FullLine) {
                Note("Nothing watched in the last ${n.range.days} days. A longer range shows more.")
            }
            n.shown.videos == 0 -> item(key = "none", span = StaggeredGridItemSpan.FullLine) {
                Note("None of your videos match all of these. Take one off to see more.")
            }
            else -> {
                item(key = "tiles", span = StaggeredGridItemSpan.FullLine) { Tiles(n, wide) }

                section("Your viewing")
                // The whole width: the wider, the more there's room for when zoomed out.
                item(key = "watchTime", span = StaggeredGridItemSpan.FullLine) {
                    WatchTimeCard(n.forCard(Kind.Day), n.range, n.picked(Kind.Day), n.picked(Kind.Hour), wide, pickTime)
                }
                item(key = "sittings") { SittingsCard(n.sittings, narrowed = n.filters.isNotEmpty()) }
                item(key = "completion") { CompletionCard(n.forCard(Kind.Completion), n.picked(Kind.Completion), pick) }
                item(key = "length") { LengthCard(n.forCard(Kind.Length), n.picked(Kind.Length), pick) }
                item(key = "age") { AgeCard(n.forCard(Kind.Age), n.picked(Kind.Age), pick) }

                section("What you watch")
                item(key = "channels") { TopChannelsCard(n.forCard(Kind.Channel), n.picked(Kind.Channel), pick) }
                item(key = "categories") { CategoriesCard(n.forCard(Kind.Category), n.picked(Kind.Category), pick) }
                // Not from the history: picks can't narrow it.
                if (n.filters.isEmpty()) {
                    item(key = "notInterested") { NotInterestedCard(hidden, onShowChannel = feedbackStore::showAgain, onShowVideo = feedbackStore::forget) }
                }

                section("How you find videos")
                item(key = "source") { SourceCard(n.forCard(Kind.Source), n.logSince, n.picked(Kind.Source), pick) }
                item(key = "subscriptions") {
                    SubscriptionsCard(
                        n.forCard(Kind.Subscribed),
                        n.picked(Kind.Subscribed),
                        pick,
                        // Which you've stopped watching: about all of them, so not with picks.
                        unwatched = n.unwatchedSubscriptions.takeIf { n.filters.isEmpty() },
                        onSubscribe = { c -> c.url?.let { subscriptions.subscribe(Channel(it, c.name)) } },
                        onUnsubscribe = { c -> subscriptions.unsubscribe(c.url) },
                    )
                }

                section("App")
                item(key = "skipped") {
                    SkippedCard(n.forCard(Kind.Skipped), n.picked(Kind.Skipped), narrowed = n.filters.any { it.kind != Kind.Skipped }, pick)
                }
                // The app's own numbers, not the history's: picks can't narrow them.
                if (n.filters.isEmpty()) item(key = "ready") { ReadyCard(ready, prefetchOn) }
            }
        }
    }
}

/** Keeps the picks across rotation and process death. */
private val FiltersSaver = listSaver<List<StatsFilter>, String>(
    save = { StatsFilter.save(it) },
    restore = { StatsFilter.restore(it) },
)

/** A heading over a few cards, the width of the grid. */
private fun LazyStaggeredGridScope.section(title: String) =
    item(key = "section:$title", span = StaggeredGridItemSpan.FullLine) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
    }

// --- The range, and the picks ------------------------------------------------------------

private val shortDate = DateTimeFormatter.ofPattern("MMM d")

/** What the numbers cover: "52 videos since Nov 22 · this phone only · …", under the range. */
private fun scopeOf(n: Analytics): String {
    val since = n.firstWatched?.let { " since ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(shortDate)}" }.orEmpty()
    val videos = if (n.filters.isEmpty()) videoCount(n.all.videos) else "${n.shown.videos} of ${videoCount(n.all.videos)}"
    return "$videos$since · this phone only · every card below uses this range"
}

/** 7 days, 30 days or all time, and what that covers. */
@Composable
private fun RangeBar(range: StatsRange, onRange: (StatsRange) -> Unit, scope: String, wide: Boolean) {
    val picker = @Composable {
        SingleChoiceSegmentedButtonRow {
            StatsRange.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = r == range,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = StatsRange.entries.size),
                    icon = {},
                ) { Text(r.label, maxLines = 1) }
            }
        }
    }
    val text = @Composable { m: Modifier ->
        Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = m)
    }
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            picker()
            text(Modifier.padding(start = 16.dp))
        }
    } else {
        Column {
            picker()
            text(Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Above the numbers while anything is picked: one row for each pick, saying what it is, with
 * a way to a channel's page for a channel, and × to take it off.
 */
@Composable
private fun FilterBar(
    filters: List<StatsFilter>,
    onOpenChannel: (Channel) -> Unit,
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
                    if (filter.kind == Kind.Channel) {
                        channelUrlOfKey(filter.key)?.let { url ->
                            TextButton(onClick = { onOpenChannel(Channel(url, filter.label)) }) { Text("Open channel") }
                        }
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
    Kind.Category -> "Category"
    Kind.Skipped -> "Skipped"
}

// --- Tiles -------------------------------------------------------------------------

/** A headline number: what it is, its value, and one line more. */
private class TileData(val label: String, val value: String, val line: String)

/** Watch time, videos, channels and the day streak, for the range and the picks. */
@Composable
private fun Tiles(n: Analytics, wide: Boolean) {
    val s = n.shown
    val sittings = n.sittings
    val tiles = listOf(
        TileData(
            "Watch time",
            formatWatchTime(s.watchedMs),
            trendOf(n) ?: sittings?.sittings?.takeIf { it > 0 }?.let { "across $it sitting${if (it == 1) "" else "s"}" }
                ?: "in ${videoCount(s.videos)}",
        ),
        TileData("Videos", s.videos.toString(), "${s.completion[0]} watched to the end"),
        TileData("Channels", s.channels.toString(), s.topChannels.firstOrNull()?.let { "${it.name} leads" }.orEmpty()),
        when {
            sittings == null -> TileData("Day streak", "–", "recorded as you watch")
            else -> TileData(
                "Day streak",
                dayCount(sittings.currentStreak),
                if (sittings.currentStreak >= sittings.longestStreak) "your best so far" else "best: ${dayCount(sittings.longestStreak)}",
            )
        },
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
            Text(tile.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tile.value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                tile.line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "+12% vs the 7 days before": watch time against as many days before the range, when the history goes back that far. */
private fun trendOf(n: Analytics): String? {
    val days = n.range.days ?: return null
    val before = n.previous?.watchedMs?.takeIf { it > 0 } ?: return null
    val change = ((n.shown.watchedMs - before) * 100.0 / before).roundToInt()
    return when {
        change == 0 -> "as much as the $days days before"
        change > 0 -> "+$change% vs the $days days before"
        else -> "−${-change}% vs the $days days before"
    }
}

private fun dayCount(n: Int) = "$n day${if (n == 1) "" else "s"}"

private fun videoCount(n: Int) = "$n video${if (n == 1) "" else "s"}"

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
// onPick for a tap on one of its rows.

@Composable
private fun ChartCard(title: String, caption: String?, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (caption != null) {
                // Always one line, so picking doesn't move what's under it.
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Column(Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

/**
 * Watch time, and when you watch, in one chart (WatchTimeChart): by day at first, from the
 * start of the range (or of your history, if that's later) to today. Pinching zooms in to the
 * hours of a few days, or out to weeks; dragging goes back in time, within the range. Tapping
 * a day picks it; tapping an hour picks that day and that hour of the day (two picks, so
 * taking off the day leaves that hour of every day); tapping a week zooms in to it. Beside
 * the chart (under it on phones): the busiest day, the daily average, and when you usually
 * watch.
 */
@Composable
private fun WatchTimeCard(
    stats: WatchStats,
    range: StatsRange,
    day: StatsFilter?,
    hour: StatsFilter?,
    wide: Boolean,
    onPick: (List<StatsFilter>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toEpochDay() }
    val series = remember(stats.hourly) { TimeSeries(stats.hourly) }
    val last = (today + 1) * 24.0
    // No empty stretch before the history starts: from the range's first day, or the first day watched in it.
    val firstDay = listOfNotNull(range.days?.let { today - it + 1 }, series.firstDay).maxOrNull() ?: today
    val first = firstDay * 24.0
    val span = (last - first).coerceIn(MIN_SPAN_HOURS, MAX_SPAN_HOURS)
    val window = rememberSaveable(saver = TimeWindow.Saver) { TimeWindow(last - span, span) }
    // A new range, or a history that now starts on another day: all of it. (Not on rotation:
    // then the window comes back as it was.)
    var shownFor by rememberSaveable { mutableStateOf<String?>(null) }
    val showing = "$range:$firstDay"
    LaunchedEffect(first, last, showing) {
        window.setBounds(first, last)
        if (shownFor != showing) {
            window.show(last - span, span)
            shownFor = showing
        }
    }
    // What's in view, worked out again only when that changes, not with every move of a finger.
    val view by remember(series, window) { derivedStateOf { viewOf(series, window, today) } }
    val atStart by remember(window, span) { derivedStateOf { window.showsLast(span) } }

    val pickedDay = day?.key?.toLongOrNull()
    val pickedHour = hour?.key?.toIntOrNull()?.takeIf { it in 0..23 }
    val fullDate = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val busiestDate = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }
    fun date(d: Long) = LocalDate.ofEpochDay(d)
    fun dayPick(d: Long) = StatsFilter(Kind.Day, d.toString(), date(d).format(fullDate))
    fun hourPick(h: Int) = StatsFilter(Kind.Hour, h.toString(), hourRange(h))

    val caption = when {
        pickedDay != null && pickedHour != null ->
            "${date(pickedDay).format(shortDate)}, ${hourRange(pickedHour)}: " +
                formatWatchTime(series.value(Step.Hour, hourIndex(pickedDay, pickedHour)))
        pickedDay != null -> "${date(pickedDay).format(fullDate)}: ${formatWatchTime(series.value(Step.Day, pickedDay * 24))}"
        pickedHour != null -> "${hourRange(pickedHour)}, every day: ${formatWatchTime(stats.byHour[pickedHour])}"
        else -> "${rangeLabel(view, today)} · ${formatWatchTime(view.watchedMs)}"
    }

    val chart = @Composable {
        WatchTimeChart(
            series = series,
            window = window,
            picked = TimePick(pickedDay, pickedHour),
            onTap = { step, start ->
                val d = Math.floorDiv(start, 24L)
                when (step) {
                    Step.Hour -> {
                        val h = Math.floorMod(start, 24L).toInt()
                        onPick(if (pickedDay == d && pickedHour == h) emptyList() else listOf(dayPick(d), hourPick(h)))
                    }
                    Step.Day -> onPick(if (pickedDay == d && pickedHour == null) emptyList() else listOf(dayPick(d)))
                    Step.Week -> scope.launch { window.animateTo(start.toDouble(), Step.Week.hours.toDouble()) }
                }
            },
            description = "Watch time chart, ${rangeLabel(view, today)}: " +
                "${formatWatchTime(view.watchedMs)}. Its actions zoom in and out, and show earlier or later.",
        )
    }
    val hint = @Composable {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Pinch to zoom, drag to scroll",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!atStart) TextButton(onClick = { scope.launch { window.animateTo(last - span, span) } }) { Text("Show all") }
        }
    }
    val facts = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            series.days.maxByOrNull { it.value }?.let { (busiest, ms) ->
                SideFact("Busiest day", date(busiest).format(busiestDate), formatWatchTime(ms))
            }
            SideFact("Daily average", formatWatchTime(stats.watchedMs / (today - firstDay + 1).coerceAtLeast(1)), null)
            stats.byHour.indices.maxByOrNull { stats.byHour[it] }?.takeIf { stats.byHour[it] > 0 }?.let { h ->
                SideFact("You usually watch around", aroundLabel(h), null)
            }
        }
    }
    ChartCard("Watch time", caption) {
        if (wide) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f)) {
                    chart()
                    hint()
                }
                VerticalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                facts(Modifier.width(200.dp))
            }
        } else {
            Column {
                chart()
                facts(Modifier.padding(top = 16.dp))
                hint()
            }
        }
    }
}

/** A number beside the chart: what it is, the number, and a line more. */
@Composable
private fun SideFact(label: String, value: String, detail: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * What the watch time chart shows: the hours [from] to [to] (hour indexes, [to] not included),
 * whether that's up to now, and the watch time in it.
 */
private data class TimeView(val from: Long, val to: Long, val toNow: Boolean, val watchedMs: Long)

private fun viewOf(series: TimeSeries, window: TimeWindow, today: Long): TimeView {
    val from = floor(window.start).toLong()
    val to = min(ceil(window.end).toLong(), (today + 1) * 24)
    var ms = 0L
    // The days in view hold every hour in view: add up those.
    var d = Math.floorDiv(from, 24L)
    while (d * 24 < to) {
        if (series.value(Step.Day, d * 24) > 0) {
            for (h in max(from, d * 24) until min(to, d * 24 + 24)) ms += series.value(Step.Hour, h)
        }
        d++
    }
    return TimeView(from, to, toNow = window.end >= window.last - 0.5, ms)
}

/**
 * What the chart shows, for its caption. Days: "Last 30 days" or "Today" when it's up to now,
 * otherwise "Nov 1 – Nov 20". Zoomed in to hours (3 days or less), the hours too:
 * "Nov 24, 6:00–12:00", "Nov 23, 9:00 – Nov 24, 7:00". With the year when it isn't this one.
 */
private fun rangeLabel(view: TimeView, today: Long): String {
    val thisYear = LocalDate.ofEpochDay(today).year
    fun date(d: Long): String {
        val date = LocalDate.ofEpochDay(d)
        return date.format(DateTimeFormatter.ofPattern(if (date.year == thisYear) "MMM d" else "MMM d, yyyy"))
    }
    val first = Math.floorDiv(view.from, 24L)
    val last = Math.floorDiv(view.to - 1, 24L)
    val wholeDays = Math.floorMod(view.from, 24L) == 0L && Math.floorMod(view.to, 24L) == 0L
    if (view.to - view.from <= 72 && !wholeDays) {
        val start = "${date(first)}, ${hourLabel(Math.floorMod(view.from, 24L).toInt())}"
        val endHour = Math.floorMod(view.to - 1, 24L).toInt() + 1 // the end of its last hour: up to 24:00
        return if (first == last) "$start–$endHour:00" else "$start – ${date(last)}, $endHour:00"
    }
    return when {
        view.toNow && first == today -> "Today"
        view.toNow -> "Last ${today - first + 1} days"
        first == last -> date(first)
        else -> "${date(first)} – ${date(last)}"
    }
}

private fun hourLabel(hour: Int) = "$hour:00"

private fun hourRange(hour: Int) = "${hourLabel(hour)}–${hourLabel((hour + 1) % 24)}"

/** "1 am", "noon", "9 pm": the hour you usually watch at, with no doubt about which half of the day. */
private fun aroundLabel(hour: Int): String = when (hour) {
    0 -> "midnight"
    12 -> "noon"
    in 1..11 -> "$hour am"
    else -> "${hour - 12} pm"
}

/**
 * How you sit down to watch, from the watch log: sittings (stretches of watching less than
 * half an hour apart), videos per sitting, videos played again, and what was actually played,
 * against the watch time the rest of the tab counts. With picks, those of the videos shown.
 */
@Composable
private fun SittingsCard(s: SittingStats?, narrowed: Boolean) {
    val since = s?.let { Instant.ofEpochMilli(it.since).atZone(ZoneId.systemDefault()).format(shortDate) }
    ChartCard("Sittings", if (since != null) "Recorded since $since" else "Recorded as you watch") {
        if (s == null) {
            Note(
                if (narrowed) "Nothing recorded for these videos yet."
                else "Recorded from now on: how often you sit down to watch, for how long, what you actually play, " +
                    "and the videos you watch again.",
            )
            return@ChartCard
        }
        Column {
            if (s.sittings == 0) {
                Note("None in this range.")
            } else {
                Headline(s.sittings.toString(), "sitting${if (s.sittings == 1) "" else "s"} · ${formatWatchTime(s.playedMs / s.sittings)} on average")
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.sittings > 0) Fact("Videos per sitting", perSitting(s.videos, s.sittings))
                Fact("Rewatched", videoCount(s.rewatched))
                Fact("Time actually played", formatWatchTime(s.playedMs))
            }
            Text(
                "Watch time counts how far into each video you got, so skipped parts add to it. This counts only what played.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** "1", "2.4": how many videos a sitting has, on average. */
private fun perSitting(videos: Int, sittings: Int): String {
    val tenths = (videos * 10 + sittings / 2) / sittings
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** How far into videos you get: the share watched to the end, then all four in one bar, each a pick. */
@Composable
private fun CompletionCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()
    val counts = stats.completion
    val total = counts.sum().coerceAtLeast(1)
    val colors = completionColors()
    ChartCard("How much you finish", "${videoCount(stats.videos)}, by how far you got") {
        Column {
            Headline("${counts[0] * 100 / total}%", "watched to the end")
            SegmentedBar(
                counts.mapIndexed { i, n -> n.toLong() to colors[i].dimmedUnless(selected == null || selected == i) },
                Modifier.padding(top = 12.dp),
            )
            Column(Modifier.padding(top = 8.dp)) {
                counts.indices.chunked(2).forEach { pair ->
                    Row {
                        pair.forEach { i ->
                            val label = WatchStats.COMPLETION_LABELS[i]
                            LegendItem(
                                colors[i],
                                label,
                                "${videoCount(counts[i])} · ${counts[i] * 100 / total}%",
                                onClick = { onPick(StatsFilter(Kind.Completion, i.toString(), label)) },
                                dimmed = selected != null && selected != i,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Finished, over half, under half, barely started: a strong colour, a lighter one, grey, and amber for the ones dropped. */
@Composable
private fun completionColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    return listOf(primary, primary.copy(alpha = 0.5f), MaterialTheme.colorScheme.outline, Color(0xFFE8912D))
}

/** Watch time by how long videos are, and how many of each you finish: a pick. */
@Composable
private fun LengthCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()
    val most = stats.byLength.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Video length", "Watch time, and how much you finish") {
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            stats.byLength.forEachIndexed { i, l ->
                val label = WatchStats.LENGTH_LABELS[i]
                StatRow(
                    label = label,
                    meta = if (l.videos == 0) null else "${l.finished * 100 / l.videos}% finished",
                    value = if (l.videos == 0) "none" else formatWatchTime(l.watchedMs),
                    fraction = l.watchedMs.toFloat() / most,
                    onClick = { onPick(StatsFilter(Kind.Length, i.toString(), label)) },
                    clickLabel = "Show the numbers of these videos",
                    dimmed = selected != null && selected != i,
                )
            }
        }
    }
}

/** How new videos were when you first watched them: the share of videos (a part of the whole), a pick. */
@Composable
private fun AgeCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key?.toIntOrNull()
    val total = stats.withAge.coerceAtLeast(1)
    val caption = stats.medianAgeMs?.let { "Usually ${formatAge(it)} old when you watch · bars show share of videos" }
        ?: "From when videos came out"
    ChartCard("How new", caption) {
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            stats.byAge.forEachIndexed { i, a ->
                val label = WatchStats.AGE_LABELS[i]
                StatRow(
                    label = label,
                    meta = formatWatchTime(a.watchedMs).takeIf { a.videos > 0 },
                    value = videoCount(a.videos),
                    fraction = a.videos.toFloat() / total,
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

/** The channels you watch most: the top 5, the rest a tap away. A pick. */
@Composable
private fun TopChannelsCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = stats.topChannels.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Top channels", "By watch time") {
        TopRows(stats.topChannels, count = 5, isPicked = { it.key == selected }, seeAll = { "See all $it channels" }) { c ->
            StatRow(
                label = c.name,
                meta = videoCount(c.videos),
                value = formatWatchTime(c.watchedMs),
                fraction = c.watchedMs.toFloat() / most,
                onClick = { onPick(StatsFilter(Kind.Channel, c.key, c.name)) },
                clickLabel = "Show ${c.name}'s numbers",
                dimmed = selected != null && selected != c.key,
            )
        }
    }
}

/** YouTube's categories of what you watch: the top 4, the rest a tap away. A pick. */
@Composable
private fun CategoriesCard(stats: WatchStats, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val categories = stats.categories
    val most = categories.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    val caption = if (stats.withCategory < stats.videos) "${stats.withCategory} of ${videoCount(stats.videos)} have one" else "By watch time"
    ChartCard("Categories", caption) {
        if (categories.isEmpty()) {
            Note("Categories are saved for videos you watch from now on.")
            return@ChartCard
        }
        TopRows(categories, count = 4, isPicked = { it.key == selected }, seeAll = { "See all $it categories" }) { c ->
            StatRow(
                label = c.name,
                meta = videoCount(c.videos),
                value = formatWatchTime(c.watchedMs),
                fraction = c.watchedMs.toFloat() / most,
                onClick = { onPick(StatsFilter(Kind.Category, c.key, c.name)) },
                clickLabel = "Show the numbers for ${c.name}",
                dimmed = selected != null && selected != c.key,
            )
        }
    }
}

/**
 * What you hid from For you: the channels you said not to recommend and the videos you're
 * not interested in, the latest first, each with Show again.
 */
@Composable
private fun NotInterestedCard(
    feedback: ForYouFeedback,
    onShowChannel: (HiddenChannel) -> Unit,
    onShowVideo: (NotInterested) -> Unit,
) {
    val channels = feedback.hiddenChannels
    val videos = remember(feedback) { feedback.notInterested.values.sortedByDescending { it.at } }
    val counts = listOfNotNull(
        channels.size.takeIf { it > 0 }?.let { "$it channel${if (it == 1) "" else "s"}" },
        videos.size.takeIf { it > 0 }?.let { videoCount(it) },
    )
    ChartCard("Not interested", if (counts.isEmpty()) "Hidden from For you" else "Hidden from For you · ${counts.joinToString(", ")}") {
        if (feedback.isEmpty) {
            Note("Nothing hidden. In For you, ⋮ on a video has Not interested and Don't recommend channel.")
            return@ChartCard
        }
        if (channels.isNotEmpty()) {
            Text("Channels you don't want recommended", style = MaterialTheme.typography.titleSmall)
            TopRows(channels, count = 5, isPicked = { false }, seeAll = { "See all $it channels" }) { c ->
                ActionRow(c.name.ifBlank { "A channel" }, "since ${hiddenOn(c.at)}", "Show again", tonal = false) { onShowChannel(c) }
            }
        }
        if (videos.isNotEmpty()) {
            Text(
                "Videos you're not interested in",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = if (channels.isEmpty()) 0.dp else 16.dp),
            )
            TopRows(videos, count = 5, isPicked = { false }, seeAll = { "See all $it videos" }) { v ->
                ActionRow(v.title.ifBlank { "A video" }, listOf(v.channel, hiddenOn(v.at)).filter { it.isNotBlank() }.joinToString(" · "), "Show again", tonal = false) {
                    onShowVideo(v)
                }
            }
        }
    }
}

/** "Nov 26": the day something was hidden. */
private fun hiddenOn(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(shortDate)

/** Where you opened videos from, by watch time: a pick. Recorded from when the app started keeping it ([since]). */
@Composable
private fun SourceCard(stats: WatchStats, since: Long?, picked: StatsFilter?, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = stats.bySource.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    val tracked = since?.let { "tracked since ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(shortDate)}" }
    val caption = when {
        stats.withSource == 0 -> "Recorded from now on"
        stats.withSource < stats.videos -> listOfNotNull("${stats.withSource} of ${videoCount(stats.videos)}", tracked).joinToString(" · ")
        else -> tracked ?: "By watch time"
    }
    ChartCard("Where you open videos", caption) {
        if (stats.bySource.isEmpty()) {
            Note("Where you open each video from (For you, Subscriptions, Up next, search…) is recorded from now on.")
            return@ChartCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            stats.bySource.forEach { s ->
                val label = s.source.label()
                StatRow(
                    label = label,
                    meta = videoCount(s.videos),
                    value = formatWatchTime(s.watchedMs),
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

/**
 * The share of your watch time from channels you subscribe to (each part a pick), the
 * channels you came back to without subscribing (to subscribe), and, without picks, the
 * subscriptions you haven't watched in 3 months (to unsubscribe), once the history goes back
 * that far.
 */
@Composable
private fun SubscriptionsCard(
    stats: WatchStats,
    picked: StatsFilter?,
    onPick: (StatsFilter) -> Unit,
    unwatched: List<Channel>?,
    onSubscribe: (ChannelTime) -> Unit,
    onUnsubscribe: (Channel) -> Unit,
) {
    val selected = picked?.key
    val subscribed = stats.subscribed.watchedMs
    val other = stats.notSubscribed.watchedMs
    val total = (subscribed + other).coerceAtLeast(1)
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outline)
    ChartCard("Subscriptions", "Share of watch time") {
        Column {
            Headline("${subscribed * 100 / total}%", "from channels you follow")
            SegmentedBar(
                listOf(
                    subscribed to colors[0].dimmedUnless(selected == null || selected == StatsFilter.SUBSCRIBED),
                    other to colors[1].dimmedUnless(selected == null || selected == StatsFilter.NOT_SUBSCRIBED),
                ),
                Modifier.padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                listOf(
                    Triple(StatsFilter.SUBSCRIBED, "Subscribed", subscribed),
                    Triple(StatsFilter.NOT_SUBSCRIBED, "Other", other),
                ).forEachIndexed { i, (key, label, ms) ->
                    LegendItem(
                        colors[i],
                        label,
                        formatWatchTime(ms),
                        onClick = { onPick(StatsFilter(Kind.Subscribed, key, if (i == 0) "Channels you subscribe to" else "Other channels")) },
                        dimmed = selected != null && selected != key,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (stats.unsubscribedChannels.isNotEmpty()) {
                SubHeader("Worth subscribing to?")
                val times = WatchStats.MIN_VIDEOS_TO_SUBSCRIBE.let { if (it == 2) "twice" else "$it times" }
                Text(
                    "Channels you came back to at least $times",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stats.unsubscribedChannels.forEach { c ->
                    ActionRow(c.name, "${videoCount(c.videos)} · ${formatWatchTime(c.watchedMs)}", "Subscribe") { onSubscribe(c) }
                }
            }
            if (!unwatched.isNullOrEmpty()) {
                SubHeader("Not watched in 3 months")
                var showAll by rememberSaveable { mutableStateOf(false) }
                (if (showAll) unwatched else unwatched.take(5)).forEach { c ->
                    ActionRow(c.name, null, "Unsubscribe", tonal = false) { onUnsubscribe(c) }
                }
                if (!showAll && unwatched.size > 5) {
                    TextButton(onClick = { showAll = true }) { Text("Show all ${unwatched.size}") }
                }
            }
        }
    }
}

/**
 * What SponsorBlock skipped: the time saved and the segments in all, then by kind (sponsors,
 * self promotion…), each a pick. YouTube's own ads are never loaded, so these are the only
 * ones there are to count.
 */
@Composable
private fun SkippedCard(stats: WatchStats, picked: StatsFilter?, narrowed: Boolean, onPick: (StatsFilter) -> Unit) {
    val selected = picked?.key
    val most = stats.skips.maxOfOrNull { it.skippedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Skipped with SponsorBlock", null) {
        if (stats.skips.isEmpty()) {
            Note(
                if (narrowed) "Nothing was skipped in these videos."
                else "Nothing skipped yet. The sponsors and other segments SponsorBlock skips in the videos you watch show up here.",
            )
            return@ChartCard
        }
        Column {
            val segments = stats.skippedSegments
            Headline(formatSkipped(stats.skippedMs), "saved · $segments skip${if (segments == 1) "" else "s"} in ${videoCount(stats.videosWithSkips)}")
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                stats.skips.forEach { s ->
                    val name = SponsorCategory.shortLabelFor(s.category)
                    StatRow(
                        label = name,
                        meta = videoCount(s.videos),
                        value = formatSkipped(s.skippedMs),
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

/** "3 min 40 s" under 10 minutes (so the bars and the numbers agree), else "12 min", "1 h 5 min". */
private fun formatSkipped(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 600 -> if (seconds % 60 == 0L) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
        else -> formatWatchTime(ms)
    }
}

/**
 * How the app's guesses at what you'd open went (Prefetcher): how many of the videos you
 * opened were ready and the time that saved, by where you opened them, how often each kind
 * of guess it got ready was opened, and what it does now at each moment it guesses.
 */
@Composable
private fun ReadyCard(stats: GuessStats, on: Boolean) {
    ChartCard("Ready before you tapped", "Last 30 days") {
        if (stats.opened == 0 && stats.fetched == 0) {
            Note(
                if (on) "Nothing yet. When you're about to pick a video (the app opening, a video ending, a channel " +
                    "picked, For you loading), it gets the likeliest ready, so they start sooner. How often it's right shows up here."
                else "Off in Settings (Get likely videos ready).",
            )
            return@ChartCard
        }
        val saved = when {
            stats.savedMs >= 500 -> " · about ${formatSaved(stats.savedMs)} saved"
            stats.savedMs > 0 -> " · under a second saved"
            else -> ""
        }
        Headline(
            "${stats.ready} of ${stats.opened}",
            "video${if (stats.opened == 1) "" else "s"} you opened ${if (stats.ready == 1) "was" else "were"} ready$saved",
        )
        if (stats.bySource.isNotEmpty()) {
            SubHeader("By where you opened them")
            Text(
                "How many were ready, and how far down each list you usually tap (in Up next and For you, it guesses that deep)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                stats.bySource.forEach { s ->
                    StatRow(
                        label = s.source.label(),
                        meta = s.usualDepth?.let { if (it == 1) "usually the 1st" else "usually in the top $it" },
                        value = "${s.ready} of ${s.opened} ready",
                        fraction = s.ready.toFloat() / s.opened.coerceAtLeast(1),
                    )
                }
            }
        }
        if (stats.byReason.isNotEmpty()) {
            SubHeader("Its guesses, by kind")
            Text(
                "Of the videos it got ready, how many you opened",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                stats.byReason.forEach { r ->
                    StatRow(
                        label = r.reason.label(),
                        value = "${r.used} of ${r.fetched} opened",
                        fraction = r.used.toFloat() / r.fetched.coerceAtLeast(1),
                    )
                }
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

/**
 * What the guessing does now, and why: the moments it gets videos ready at, and those it's
 * stopped at for now, with how its first guesses there went.
 */
private fun readyStatus(stats: GuessStats, on: Boolean): String {
    val now = System.currentTimeMillis()
    if (!on) return "Off in Settings: nothing is fetched."
    if (now < stats.pausedUntil) {
        val until = Instant.ofEpochMilli(stats.pausedUntil).atZone(ZoneId.systemDefault())
        return "Paused until ${until.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))}: YouTube was limiting requests."
    }
    val (fetching, stopped) = stats.moments.partition { it.fetching > 0 }
    val active = if (fetching.isEmpty()) "Getting none ready for now."
    else "Gets videos ready " + fetching.joinToString(", ") { it.moment.label() }.replaceLast(", ", " and ") + "."
    val why = stopped.joinToString(" ") { m ->
        "Not ${m.moment.label()}: ${m.recentUsed} of its last ${m.recent} first guesses there " +
            "${if (m.recentUsed == 1) "was" else "were"} opened."
    }
    val again = if (stopped.isEmpty()) "" else " It keeps guessing, and starts again where its guesses are opened more."
    return listOf(active, why).filter { it.isNotEmpty() }.joinToString(" ") + again
}

/** "when the app opens", "near the end of a video"…: when a [GuessMoment] is. */
private fun GuessMoment.label(): String = when (this) {
    GuessMoment.AppOpen -> "when the app opens"
    GuessMoment.VideoEnding -> "near the end of a video"
    GuessMoment.ChannelPicked -> "when you pick a channel"
    GuessMoment.ForYouLoaded -> "when For you loads"
}

/** This with the last [old] replaced by [new]: "a, b, c" to "a, b and c". */
private fun String.replaceLast(old: String, new: String): String {
    val i = lastIndexOf(old)
    return if (i < 0) this else substring(0, i) + new + substring(i + old.length)
}

/** "12 s", or "2 min" from a minute on. */
private fun formatSaved(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return if (seconds < 60) "$seconds s" else formatWatchTime(ms)
}

private fun GuessReason.label(): String = when (this) {
    GuessReason.Unfinished -> "Unfinished videos"
    GuessReason.WatchLater -> "Watch later"
    GuessReason.ForYou -> "For you"
    GuessReason.NewUpload -> "New uploads"
    GuessReason.NextInLine -> "Next in line"
    GuessReason.UpNext -> "Up next"
}

// --- Marks -----------------------------------------------------------------------------

/** Between the rows of a card. */
private val RowGap = 10.dp

/** A big number, and what it is beside it on the same line. */
@Composable
private fun Headline(value: String, rest: String) {
    Row {
        Text(value, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.alignByBaseline())
        Text(
            rest,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline().padding(start = 8.dp),
        )
    }
}

/**
 * A row of a card: its name and a detail in grey, its value on the right (figures of the
 * same width, so they line up down the card), a › when it's a pick, and a thin bar under it
 * for [fraction] of the width, on a faint track.
 */
@Composable
private fun StatRow(
    label: String,
    value: String,
    fraction: Float,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    /** Faded, beside the picked one. */
    dimmed: Boolean = false,
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val bar = MaterialTheme.colorScheme.primary.dimmedUnless(!dimmed)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier)
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (meta != null) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp),
                )
            }
        }
        Box(
            Modifier
                .padding(top = 5.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(track),
        ) {
            // Nothing for zero; anything else at least visible.
            if (fraction > 0f) Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
        }
    }
}

/** The parts of a whole in one bar, each as wide as its share. */
@Composable
private fun SegmentedBar(parts: List<Pair<Long, Color>>, modifier: Modifier = Modifier) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.filter { it.first > 0 }.forEach { (n, color) ->
            Box(Modifier.weight(n.toFloat() / total).fillMaxHeight().background(color))
        }
    }
}

/** A part of a [SegmentedBar]: its colour, what it is, and its numbers; a pick. */
@Composable
private fun LegendItem(color: Color, label: String, detail: String, onClick: () -> Unit, dimmed: Boolean, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Show the numbers of these videos", onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color.dimmedUnless(!dimmed)))
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** This colour, or faded when [shown] is false (beside the picked one). */
private fun Color.dimmedUnless(shown: Boolean): Color = if (shown) this else copy(alpha = alpha * 0.35f)

/** The first [count] of [items] (and the picked one, if it's further down), with "See all" for the rest. */
@Composable
private fun <T> TopRows(
    items: List<T>,
    count: Int,
    isPicked: (T) -> Boolean,
    seeAll: (Int) -> String,
    row: @Composable (T) -> Unit,
) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all || items.size <= count) items else items.take(count) + items.drop(count).filter(isPicked)
    Column {
        Column(verticalArrangement = Arrangement.spacedBy(RowGap)) {
            shown.forEach { row(it) }
        }
        if (items.size > count) {
            TextButton(onClick = { all = !all }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (all) "Show fewer" else seeAll(items.size))
            }
        }
    }
}

/** A heading inside a card, over a few rows. */
@Composable
private fun SubHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))

/** A line of small print. */
@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A number in a card: what it is on the left, its value on the right. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium))
    }
}

/** A channel with something to do about it: Subscribe (a tonal button), Unsubscribe (an outlined one). */
@Composable
private fun ActionRow(name: String, detail: String?, action: String, tonal: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val padding = PaddingValues(horizontal = 16.dp)
        if (tonal) {
            FilledTonalButton(onClick = onClick, contentPadding = padding, modifier = Modifier.height(34.dp)) { Text(action) }
        } else {
            OutlinedButton(onClick = onClick, contentPadding = padding, modifier = Modifier.height(34.dp)) { Text(action) }
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
