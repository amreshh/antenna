package com.example.antenna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.antenna.data.WatchEntry
import com.example.antenna.data.VideoItem
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.ageLabel
import com.example.antenna.data.formatDuration
import com.example.antenna.data.videoIdOf

/**
 * Window-size helpers (Material's breakpoints). They read the current window, so they
 * also react to split-screen and foldables, not just "is this a tablet".
 */
@Composable
internal fun isWideWindow(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** Expanded width (tablet landscape, big foldables): room for a second column next to the video. */
@Composable
internal fun isExpandedWindow(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** A phone held sideways: too short for anything but the video. */
@Composable
internal fun isShortWindow(): Boolean = LocalConfiguration.current.screenHeightDp < 480

/**
 * The video list used by every tab: one column of rows on phones, a grid of
 * thumbnail cards on wider screens.
 */
@Composable
internal fun VideoGrid(modifier: Modifier = Modifier, content: LazyGridScope.(wide: Boolean) -> Unit) {
    val wide = isWideWindow()
    LazyVerticalGrid(
        columns = if (wide) GridCells.Adaptive(minSize = 300.dp) else GridCells.Fixed(1),
        modifier = modifier,
        contentPadding = if (wide) PaddingValues(16.dp) else PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 0.dp),
    ) { content(wide) }
}

/** Headers, errors and spinners take up the whole width of the grid. */
internal fun LazyGridScope.fullWidthItem(key: Any? = null, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

internal fun LazyGridScope.videoItems(
    items: List<VideoItem>,
    wide: Boolean,
    onClick: (VideoItem) -> Unit,
    trailing: (@Composable (VideoItem) -> Unit)? = null,
) = items(items, key = { it.url }) { item ->
    val tail: (@Composable () -> Unit)? = trailing?.let { t -> { t(item) } }
    if (wide) VideoCard(item, onClick = { onClick(item) }, trailing = tail)
    else VideoRow(item, onClick = { onClick(item) }, trailing = tail)
}

/** Watch progress for a video, straight from the history store. */
@Composable
internal fun rememberHistoryEntry(item: VideoItem): WatchEntry? {
    val context = LocalContext.current
    val history = remember(context) { WatchHistoryStore.get(context) }
    val entries by history.entries.collectAsStateWithLifecycle()
    return remember(entries, item.url) { videoIdOf(item.url)?.let { entries[it] } }
}

/** Big thumbnail on top, title and channel below, like YouTube's tablet layout. */
@Composable
internal fun VideoCard(
    item: VideoItem,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val entry = rememberHistoryEntry(item)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        VideoThumbnail(item, entry, Modifier.fillMaxWidth(), corner = 12)
        Row(Modifier.padding(top = 8.dp, start = 4.dp)) {
            VideoText(item, entry, Modifier.weight(1f).padding(end = 4.dp), titleLines = 2)
            trailing?.invoke()
        }
    }
}

@Composable
internal fun VideoThumbnail(item: VideoItem, entry: WatchEntry?, modifier: Modifier, corner: Int = 8) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(corner.dp))) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val duration = formatDuration(item.durationSeconds)
        if (duration.isNotEmpty()) {
            Text(
                duration,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 6.dp, end = 4.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (entry != null && entry.fraction > 0.01f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Gray.copy(alpha = 0.6f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (entry.isWatched) 1f else entry.fraction)
                        .background(Color.Red),
                )
            }
        }
    }
}

@Composable
internal fun VideoText(item: VideoItem, entry: WatchEntry?, modifier: Modifier, titleLines: Int = 3) {
    Column(modifier) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOf(item.uploader, item.ageLabel())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (entry?.isWatched == true) {
            Text(
                "WATCHED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
