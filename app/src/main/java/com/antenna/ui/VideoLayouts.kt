package com.antenna.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import com.antenna.data.WatchLaterStore
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.antenna.data.WatchEntry
import com.antenna.data.VideoItem
import com.antenna.data.Channel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import com.antenna.data.WatchHistoryStore
import com.antenna.data.ageLabel
import com.antenna.data.formatDuration
import com.antenna.data.videoIdOf

/**
 * Window-size helpers (Material's breakpoints). They read the app's window (its bounds, as
 * Material's window size classes measure them), so they also react to split-screen,
 * freeform windows and foldables, not just "is this a tablet".
 */
@Composable
internal fun isWideWindow(): Boolean = LocalWindowInfo.current.containerDpSize.width >= 600.dp

/** Expanded width (tablet landscape, big foldables): room for a second column next to the video. */
@Composable
internal fun isExpandedWindow(): Boolean = LocalWindowInfo.current.containerDpSize.width >= 840.dp

/** A phone held sideways: too short for anything but the video. */
@Composable
internal fun isShortWindow(): Boolean = LocalWindowInfo.current.containerDpSize.height < 480.dp

/**
 * Taps on the tab that's already showing (MainScreen): the list on screen scrolls back to the
 * top, like YouTube's. Only lists that are showing hear of it, so the other tabs keep where
 * they were.
 */
internal val LocalScrollToTop = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

/** Scrolls [state]'s list back to the top when its tab is tapped again ([LocalScrollToTop]). */
@Composable
internal fun ScrollsToTop(state: LazyGridState) {
    val requests = LocalScrollToTop.current
    LaunchedEffect(state, requests) { requests.collect { state.animateScrollToItem(0) } }
}

/**
 * The video list used by every tab: one column of rows on phones, a grid of
 * thumbnail cards on wider screens.
 */
@Composable
internal fun VideoGrid(modifier: Modifier = Modifier, content: LazyGridScope.(wide: Boolean) -> Unit) {
    val wide = isWideWindow()
    val state = rememberLazyGridState()
    ScrollsToTop(state)
    LazyVerticalGrid(
        columns = if (wide) GridCells.Adaptive(minSize = 300.dp) else GridCells.Fixed(1),
        modifier = modifier,
        state = state,
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
 * and tapping the avatar opens the channel. Without a [trailing] of its own, each video gets
 * the ⋮ menu ([VideoMenu]), with [moreOptions] at its end. [onShown] hears of each video as it
 * comes on screen.
 */
internal fun LazyGridScope.videoItems(
    items: List<VideoItem>,
    wide: Boolean,
    onClick: (VideoItem) -> Unit,
    trailing: (@Composable (VideoItem) -> Unit)? = null,
    onChannelClick: ((Channel) -> Unit)? = null,
    onShown: ((VideoItem) -> Unit)? = null,
    moreOptions: MenuOptions? = null,
) = items(items, key = { it.url }) { item ->
    if (onShown != null) LaunchedEffect(item.url) { onShown(item) }
    val tail: @Composable () -> Unit = trailing?.let { t -> { t(item) } } ?: { VideoMenu(item, moreOptions) }
    val channel = item.channelUrl?.let { url -> Channel(url, item.uploader, item.channelAvatarUrl) }
    val openChannel: (() -> Unit)? = onChannelClick?.let { open -> channel?.let { c -> { open(c) } } }
    if (wide) VideoCard(item, onClick = { onClick(item) }, trailing = tail, onChannelClick = openChannel)
    else VideoRow(item, onClick = { onClick(item) }, trailing = tail, onChannelClick = openChannel)
}

/**
 * Opens the video at [url], tapped at [position] in its list (0 at the top; -1 when it isn't
 * known): Prefetcher learns how far down each list you tap.
 */
internal typealias OpenVideo = (url: String, position: Int) -> Unit

/** Where [item] is in [items] when it's tapped (-1 if it's gone meanwhile): for [OpenVideo]. */
internal fun positionOf(items: List<VideoItem>, item: VideoItem): Int = items.indexOfFirst { it.url == item.url }

/** A list's own items at the end of a video's ⋮ menu: given the video, and what closes the menu. */
internal typealias MenuOptions = @Composable (item: VideoItem, close: () -> Unit) -> Unit

/** ⋮ on a video: save it to Watch later, or take it off; then the list's [moreOptions]. */
@Composable
internal fun VideoMenu(item: VideoItem, moreOptions: MenuOptions? = null) {
    val context = LocalContext.current
    val watchLater = remember(context) { WatchLaterStore.get(context) }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val saved = remember { watchLater.contains(item.url) }
            DropdownMenuItem(
                text = { Text(if (saved) "Remove from Watch later" else "Save to Watch later") },
                leadingIcon = { Icon(if (saved) BookmarkIcon else BookmarkBorderIcon, contentDescription = null) },
                onClick = {
                    open = false
                    val now = watchLater.toggle(item)
                    Toast.makeText(context, if (now) "Saved to Watch later" else "Removed from Watch later", Toast.LENGTH_SHORT).show()
                },
            )
            moreOptions?.invoke(item) { open = false }
        }
    }
}

/** A 24 dp icon from an SVG path, like Material's. */
private fun svgIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

/** Material's "block": YouTube's Not interested. */
internal val NotInterestedIcon: ImageVector by lazy {
    svgIcon(
        "NotInterested",
        "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 " +
            "1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 " +
            "7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z",
    )
}

/** Material's "remove circle outline": YouTube's Don't recommend channel. */
internal val DontRecommendIcon: ImageVector by lazy {
    svgIcon(
        "DontRecommend",
        "M7 11v2h10v-2H7zm5-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 " +
            "0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
    )
}

/** Material's "bookmark" icons (the core icon set doesn't have them): saved, and not. */
internal val BookmarkIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Bookmark") {
        materialPath {
            moveTo(17f, 3f); horizontalLineTo(7f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(5f, 21f); lineToRelative(7f, -3f); lineToRelative(7f, 3f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
        }
    }
}

internal val BookmarkBorderIcon: ImageVector by lazy {
    materialIcon(name = "Filled.BookmarkBorder") {
        materialPath {
            moveTo(17f, 3f); horizontalLineTo(7f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(5f, 21f); lineToRelative(7f, -3f); lineToRelative(7f, 3f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(17f, 18f); lineToRelative(-5f, -2.18f); lineTo(7f, 18f); verticalLineTo(5f); horizontalLineToRelative(10f); verticalLineToRelative(13f)
            close()
        }
    }
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

/**
 * Watch progress for a video, straight from the history store. Each card follows only its
 * own video's entry: while a video plays the history changes every few seconds, and the
 * cards of every other video don't redraw for it.
 */
@Composable
internal fun rememberHistoryEntry(item: VideoItem): WatchEntry? {
    val context = LocalContext.current
    val history = remember(context) { WatchHistoryStore.get(context) }
    val id = remember(item.url) { videoIdOf(item.url) }
    val entry = remember(history, id) { history.entries.map { if (id == null) null else it[id] }.distinctUntilChanged() }
    // Starting from the entry as it is now, so the progress bar is there from the first frame.
    val now = remember(history, item.url) { history.entryFor(item.url) }
    return entry.collectAsStateWithLifecycle(initialValue = now).value
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
        WatchProgress(entry, Modifier.align(Alignment.BottomStart))
    }
}

/** The red bar along a thumbnail's bottom: how far into the video you got, like YouTube's. */
@Composable
private fun WatchProgress(entry: WatchEntry?, modifier: Modifier) {
    if (entry == null || entry.fraction <= 0.01f) return
    Box(
        modifier
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

/** Shorts: a grid of tall cards, three across on most phones and more on bigger screens. */
@Composable
internal fun ShortsGrid(modifier: Modifier = Modifier, content: LazyGridScope.() -> Unit) {
    val state = rememberLazyGridState()
    ScrollsToTop(state)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

internal fun LazyGridScope.shortItems(items: List<VideoItem>, onClick: (VideoItem) -> Unit) =
    items(items, key = { it.url }) { item -> ShortCard(item, onClick = { onClick(item) }) }

/**
 * A Short: its thumbnail, tall like the video, then the title and channel. The feed's
 * thumbnail is 4:3 with the video upright in the middle; cut to 9:16, it's just the video.
 */
@Composable
internal fun ShortCard(item: VideoItem, onClick: () -> Unit) {
    val entry = rememberHistoryEntry(item)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            )
            WatchProgress(entry, Modifier.align(Alignment.BottomStart))
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
        )
        Text(
            listOf(item.uploader, item.ageLabel()).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
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
