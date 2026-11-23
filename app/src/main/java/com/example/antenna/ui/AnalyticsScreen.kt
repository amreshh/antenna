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
import com.example.antenna.data.Channel
import com.example.antenna.data.ChannelTime
import com.example.antenna.data.TopicTime
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.WatchStats
import com.example.antenna.data.channelKey
import com.example.antenna.data.formatWatchTime
import com.example.antenna.data.watchStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** All of the history's numbers, and those of the channel picked in Top channels (if any). */
private class AnalyticsNumbers(val all: WatchStats, val shown: WatchStats, val channel: ChannelTime?)

/**
 * The Analytics tab: what your watch history says about your watching, worked out on the
 * phone (data/WatchStats.kt). Tiles for the headline numbers, then one chart per question,
 * each a single measure in one colour. Tap a bar for its value, a channel in Top channels for
 * that channel's numbers alone (Back, or the bar at the top, shows everything again).
 */
@Composable
fun AnalyticsTab(onOpenChannel: (Channel) -> Unit, onSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { WatchHistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    /** The picked channel's key (ChannelTime.key), or null for every channel. */
    var channelKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = channelKey != null) { channelKey = null }

    // Worked out off the main thread: it goes through up to 1000 videos, and the history
    // changes every few seconds while a video plays.
    val numbers by produceState<AnalyticsNumbers?>(null, entries, channelKey) {
        value = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val all = watchStats(entries.values, now)
            val channel = channelKey?.let { key -> all.topChannels.firstOrNull { it.key == key } }
            val shown = if (channel == null) all else watchStats(entries.values.filter { it.channelKey() == channel.key }, now)
            AnalyticsNumbers(all, shown, channel)
        }
    }
    val n = numbers ?: return // a moment, the first time

    if (n.all.videos == 0) {
        EmptyMessage("Nothing to show yet", "Watch a few videos and what you watch, and when, shows up here.", modifier)
        return
    }

    val wide = isWideWindow()
    val stats = n.shown
    val grid = rememberLazyStaggeredGridState()
    // Picking a channel adds the bar above everything else: show it, and the new numbers.
    LaunchedEffect(n.channel?.key) { grid.scrollToItem(0) }
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
        n.channel?.let { channel ->
            item(key = "channel", span = StaggeredGridItemSpan.FullLine) {
                ChannelFilterBar(
                    channel,
                    onOpenChannel = channel.url?.let { url -> { onOpenChannel(Channel(url, channel.name)) } },
                    onClear = { channelKey = null },
                )
            }
        }
        item(key = "tiles", span = StaggeredGridItemSpan.FullLine) { Tiles(stats, n.all, n.channel != null, wide) }
        item(key = "perDay") { PerDayCard(stats) }
        item(key = "channels") {
            TopChannelsCard(n.all.topChannels, selected = n.channel?.key) { key ->
                channelKey = key.takeIf { it != channelKey } // tapping the picked one shows everything again
            }
        }
        item(key = "byHour") { ByHourCard(stats.byHour) }
        item(key = "completion") { CompletionCard(stats.completion) }
        item(key = "topics") { TopicsCard(stats.topics, onSearch) }
        item(key = "categories") { CategoriesCard(stats.categories, withCategory = stats.withCategory, videos = stats.videos) }
        item(key = "about", span = StaggeredGridItemSpan.FullLine) {
            Text(
                (if (n.channel != null) "From the ${stats.videos} videos by ${n.channel.name} in your history" else "From the last ${stats.videos} videos in your history") +
                    ", on this phone only. Watch time is how far into each video you got, counted on the day " +
                    "you last watched it. Topics come from titles and the uploaders' tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Above the numbers while one channel's are shown: which, its page, and back to all. */
@Composable
private fun ChannelFilterBar(channel: ChannelTime, onOpenChannel: (() -> Unit)?, onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only this channel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOpenChannel != null) TextButton(onClick = onOpenChannel) { Text("Open channel") }
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Show all channels") }
        }
    }
}

// --- Tiles -------------------------------------------------------------------------

@Composable
private fun Tiles(stats: WatchStats, all: WatchStats, oneChannel: Boolean, wide: Boolean) {
    val tiles = listOf(
        stats.videos.toString() to "videos watched",
        formatWatchTime(stats.watchedMs) to "watch time",
        if (oneChannel) "${(stats.watchedMs * 100 / all.watchedMs.coerceAtLeast(1))}%" to "of all your watch time"
        else stats.channels.toString() to "channels",
        "${(stats.finishedShare * 100).toInt()}%" to "watched to the end",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(if (wide) 4 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (value, label) -> Tile(value, label, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

@Composable
private fun ChartCard(title: String, caption: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardColor(), border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            // The value of the tapped bar, or what the chart shows: always one line, so
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
private fun PerDayCard(stats: WatchStats) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("MMM d") }
    val fullFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val days = stats.perDay
    val caption = selected?.let { i ->
        "${stats.firstDay.plusDays(i.toLong()).format(fullFormat)}: ${formatWatchTime(days[i])}"
    } ?: "Total ${formatWatchTime(days.sum())} · busiest day ${formatWatchTime(days.max())}"
    ChartCard("Watch time, last 30 days", caption) {
        BarChart(
            values = days,
            selected = selected,
            onSelect = { selected = it.takeIf { i -> i != selected } },
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
private fun ByHourCard(byHour: List<Long>) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val busiest = byHour.indices.maxBy { byHour[it] }
    val caption = selected?.let { h -> "${hourLabel(h)}–${hourLabel((h + 1) % 24)}: ${formatWatchTime(byHour[h])}" }
        ?: "Most often around ${hourLabel(busiest)}"
    ChartCard("When you watch", caption) {
        BarChart(
            values = byHour,
            selected = selected,
            onSelect = { selected = it.takeIf { i -> i != selected } },
            description = "Watch time by hour of the day, most around ${hourLabel(busiest)}",
        )
        AxisLabels(listOf("0:00", "6:00", "12:00", "18:00", "24:00"))
    }
}

private fun hourLabel(hour: Int) = "$hour:00"

/** Your top channels (of all of them, even while one is picked); tapping one picks it. */
@Composable
private fun TopChannelsCard(channels: List<ChannelTime>, selected: String?, onSelect: (String) -> Unit) {
    val most = channels.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Top channels", if (selected == null) "By watch time · tap one for its numbers" else "Tap it again for all channels") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            channels.forEach { c ->
                HorizontalBar(
                    label = c.name,
                    value = "${formatWatchTime(c.watchedMs)} · ${c.videos} video${if (c.videos == 1) "" else "s"}",
                    fraction = c.watchedMs.toFloat() / most,
                    onClick = { onSelect(c.key) },
                    clickLabel = "Show ${c.name}'s numbers",
                    dimmed = selected != null && selected != c.key,
                )
            }
        }
    }
}

@Composable
private fun TopicsCard(topics: List<TopicTime>, onSearch: (String) -> Unit) {
    val most = topics.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    ChartCard("Topics", "What you watch about, by watch time · tap one to search for it") {
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
                    value = "${formatWatchTime(t.watchedMs)} · ${t.videos} videos",
                    fraction = t.watchedMs.toFloat() / most,
                    onClick = { onSearch(t.name) },
                    clickLabel = "Search for ${t.name}",
                )
            }
        }
    }
}

@Composable
private fun CategoriesCard(categories: List<TopicTime>, withCategory: Int, videos: Int) {
    val most = categories.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    val caption = if (withCategory == videos) "YouTube's categories, by watch time"
    else "YouTube's categories, by watch time · $withCategory of $videos videos have one"
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
                    value = "${formatWatchTime(c.watchedMs)} · ${c.videos} video${if (c.videos == 1) "" else "s"}",
                    fraction = c.watchedMs.toFloat() / most,
                )
            }
        }
    }
}

@Composable
private fun CompletionCard(completion: List<Int>) {
    val total = completion.sum().coerceAtLeast(1)
    val most = completion.max().coerceAtLeast(1)
    ChartCard("How much you finish", "Videos by how far you got into them") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            completion.forEachIndexed { i, n ->
                HorizontalBar(
                    label = WatchStats.COMPLETION_LABELS[i],
                    value = "$n · ${n * 100 / total}%",
                    fraction = n.toFloat() / most,
                )
            }
        }
    }
}

// --- Marks -----------------------------------------------------------------------------

/**
 * Vertical bars of one measure: thin, 2 dp apart, rounded only at the top (the data end),
 * standing on a faint baseline. Tapping a bar selects it (the others dim) and the card's
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
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    val i = (offset.x / (size.width.toFloat() / values.size)).toInt()
                    if (i in values.indices) onSelect(i)
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
