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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.antenna.data.WatchEntry
import com.example.antenna.data.VideoItem
import com.example.antenna.data.Channel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
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

/**
 * With [onChannelClick], each video shows its channel's avatar (when its channel is known),
 * and tapping the avatar opens the channel.
 */
internal fun LazyGridScope.videoItems(
    items: List<VideoItem>,
    wide: Boolean,
    onClick: (VideoItem) -> Unit,
    trailing: (@Composable (VideoItem) -> Unit)? = null,
    onChannelClick: ((Channel) -> Unit)? = null,
) = items(items, key = { it.url }) { item ->
    val tail: (@Composable () -> Unit)? = trailing?.let { t -> { t(item) } }
    val channel = item.channelUrl?.let { url -> Channel(url, item.uploader, item.channelAvatarUrl) }
    val openChannel: (() -> Unit)? = onChannelClick?.let { open -> channel?.let { c -> { open(c) } } }
    if (wide) VideoCard(item, onClick = { onClick(item) }, trailing = tail, onChannelClick = openChannel)
    else VideoRow(item, onClick = { onClick(item) }, trailing = tail, onChannelClick = openChannel)
}

/** On a video's channel avatar (or channel line) that opens the channel: for the smoke test. */
internal const val TAG_CHANNEL_AVATAR = "channel_avatar"

/** A channel's round picture; grey until it loads, or if there is none. */
@Composable
internal fun ChannelAvatar(url: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Watch progress for a video, straight from the history store. */
@Composable
internal fun rememberHistoryEntry(item: VideoItem): WatchEntry? {
    val context = LocalContext.current
    val history = remember(context) { WatchHistoryStore.get(context) }
    val entries by history.entries.collectAsStateWithLifecycle()
    return remember(entries, item.url) { videoIdOf(item.url)?.let { entries[it] } }
}

/**
 * Big thumbnail on top, title and channel below, like YouTube's tablet layout. With
 * [onChannelClick], the channel's avatar sits next to the title and opens the channel.
 */
@Composable
internal fun VideoCard(
    item: VideoItem,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onChannelClick: (() -> Unit)? = null,
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
            if (onChannelClick != null) {
                ChannelAvatar(
                    item.channelAvatarUrl,
                    Modifier
                        .padding(end = 12.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = "Open the channel", onClick = onChannelClick)
                        .testTag(TAG_CHANNEL_AVATAR),
                )
            }
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

/**
 * Title, then channel and age. With [onChannelClick] (phone rows) the channel line gets the
 * channel's avatar in front and opens the channel.
 */
@Composable
internal fun VideoText(
    item: VideoItem,
    entry: WatchEntry?,
    modifier: Modifier,
    titleLines: Int = 3,
    onChannelClick: (() -> Unit)? = null,
) {
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
        if (onChannelClick != null) {
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Open the channel", onClick = onChannelClick)
                    .testTag(TAG_CHANNEL_AVATAR)
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatar(item.channelAvatarUrl, Modifier.size(24.dp))
                Text(meta, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        } else {
            Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
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
