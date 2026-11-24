package com.antenna.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.antenna.data.SeenUploads
import com.antenna.data.channelsWithNewUploads
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import com.antenna.data.ChannelTopics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.antenna.data.Channel
import com.antenna.data.Prefs
import com.antenna.data.VideoItem
import com.antenna.data.WatchHistoryStore
import com.antenna.data.videoIdOf

/** How the channel list next to the feed is ordered. */
private enum class ChannelSort(val label: String) {
    /** Newest upload first, like YouTube's "New activity". */
    NewActivity("New activity"),
    Alphabetical("A–Z"),

    /** Grouped by what they're about (ChannelTopics), the biggest group first. */
    Topic("Topic"),
}

/**
 * The Subscriptions tab: newest uploads from every channel you follow. On big screens, the
 * channels are listed on the left (newest activity or A–Z); tapping one shows only its videos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTab(
    vm: FeedViewModel,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // re-read each time the tab is shown, so the Settings toggle applies immediately
    val hideWatched = remember { Prefs(context).hideWatched }
    val history = remember(context) { WatchHistoryStore.get(context) }
    // Only which videos are finished: the history changes every few seconds while one plays.
    val watched by remember(history) {
        history.entries.map { entries -> entries.filterValues { it.isWatched }.keys }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    var sort by rememberSaveable { mutableStateOf(ChannelSort.NewActivity) }
    /** The channel picked in the list (its url), or null for all of them. */
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    // One you've unsubscribed from since counts as none.
    val selected = picked?.takeIf { url -> channels.any { it.url == url } }
    /** Shorts instead of the other videos (the chips above the feed). */
    var showShorts by rememberSaveable { mutableStateOf(false) }

    // Channels with an upload you haven't seen get a blue dot in the list. The channel open in
    // it has its uploads seen, and any that come in while it's open.
    val seenUploads = remember(context) { SeenUploads.get(context) }
    val seen by seenUploads.seen.collectAsStateWithLifecycle()
    val withNew = remember(state.items, seen) { channelsWithNewUploads(state.items, seen, System.currentTimeMillis()) }
    val selectedIds = remember(state.items, selected) {
        selected?.let { c -> state.items.filter { it.channelUrl == c }.mapNotNullTo(HashSet()) { videoIdOf(it.url) } }
    }
    LaunchedEffect(selected, selectedIds) {
        if (selected != null && selectedIds != null) seenUploads.markSeen(selected, selectedIds)
    }

    val visible = remember(state.items, watched, hideWatched, selected, channels) {
        // The feed doesn't come with avatars, but the subscriptions mostly have them.
        val avatars = channels.associate { it.url to it.avatarUrl }
        state.items
            .filter { selected == null || it.channelUrl == selected }
            .filterNot { v -> hideWatched && videoIdOf(v.url)?.let { it in watched } == true }
            .map { v -> if (v.channelAvatarUrl != null) v else v.copy(channelAvatarUrl = avatars[v.channelUrl]) }
    }
    // Shorts on their own, so they don't crowd out the videos.
    val (shorts, videos) = remember(visible) { visible.partition { it.isShort } }
    val feed = @Composable { m: Modifier ->
        FeedGrid(
            state, videos, shorts,
            showShorts = showShorts && shorts.isNotEmpty(),
            onShowShorts = { showShorts = it },
            onRefresh = vm::refresh, onOpenVideo = onOpenVideo, onOpenChannel = onOpenChannel,
            onVideoShown = vm::lengthWanted,
            modifier = m,
        )
    }
    // The watched videos that have a category, for the Topic order: it changes only when one
    // is added, not with every position saved while a video plays.
    val categorized by remember(history) {
        history.entries.map { entries -> entries.values.filter { it.category != null } }
            .distinctUntilChanged { a, b -> a.size == b.size && a.mapTo(HashSet()) { it.video.url } == b.mapTo(HashSet()) { it.video.url } }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val topics by produceState<Map<String, String>?>(null, sort, channels, state.items, categorized) {
        value = if (sort != ChannelSort.Topic) null
        else withContext(Dispatchers.Default) { ChannelTopics.of(channels, state.items, categorized) }
    }
    /** For the Topic order: (topic, its channels A–Z), the biggest group first and Other last. */
    val groups = remember(topics, channels) {
        topics?.let { t ->
            channels.groupBy { t[it.url] ?: ChannelTopics.OTHER }.entries
                .sortedWith(compareBy<Map.Entry<String, List<Channel>>> { it.key == ChannelTopics.OTHER }.thenByDescending { it.value.size }.thenBy { it.key })
                .map { (topic, list) -> topic to list.sortedBy { it.name.lowercase() } }
        }
    }
    val sortedChannels = remember(channels, state.items, sort, groups) {
        when (sort) {
            ChannelSort.Topic -> groups?.flatMap { it.second } ?: channels.sortedBy { it.name.lowercase() }
            ChannelSort.Alphabetical -> channels.sortedBy { it.name.lowercase() }
            ChannelSort.NewActivity -> {
                val newest = HashMap<String, Long>()
                state.items.forEach { v ->
                    val url = v.channelUrl ?: return@forEach
                    if (v.uploadedAtMs > (newest[url] ?: 0L)) newest[url] = v.uploadedAtMs
                }
                channels.sortedWith(compareByDescending<Channel> { newest[it.url] ?: 0L }.thenBy { it.name.lowercase() })
            }
        }
    }

    if (channels.isEmpty()) {
        Column(
            modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Open any video and tap Subscribe, or import your existing YouTube " +
                    "subscriptions from Google Takeout.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onManage) { Text("Import subscriptions") }
        }
        return
    }

    if (isWideWindow()) {
        Row(modifier) {
            ChannelList(
                sortedChannels,
                groups = groups.takeIf { sort == ChannelSort.Topic },
                sort = sort,
                onSort = { sort = it },
                selected = selected,
                onSelect = { picked = it },
                withNew = withNew,
                modifier = Modifier.width(280.dp).fillMaxHeight(),
            )
            feed(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        feed(modifier)
    }
}

/**
 * The channels you follow, beside the feed: tap one to see only its videos. A blue dot marks
 * the ones with uploads you haven't seen ([withNew]).
 */
@Composable
private fun ChannelList(
    channels: List<Channel>,
    /** The Topic order's groups: shown under their topic's heading. */
    groups: List<Pair<String, List<Channel>>>?,
    sort: ChannelSort,
    onSort: (ChannelSort) -> Unit,
    selected: String?,
    onSelect: (String?) -> Unit,
    withNew: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.padding(start = 8.dp, top = 4.dp)) {
            TextButton(onClick = { menu = true }) {
                Text(sort.label, style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Sort channels")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ChannelSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSort(option)
                            menu = false
                        },
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            item(key = "all") {
                ChannelEntry(
                    name = "All channels",
                    selected = selected == null,
                    onClick = { onSelect(null) },
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            @Composable
            fun Entry(channel: Channel) = ChannelEntry(
                name = channel.name,
                selected = selected == channel.url,
                onClick = { onSelect(channel.url) },
                newUploads = channel.url in withNew,
            ) {
                ChannelAvatar(channel.avatarUrl, Modifier.size(32.dp))
            }
            if (groups != null) {
                groups.forEach { (topic, list) ->
                    item(key = "topic:$topic") {
                        Text(
                            topic,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.url }) { Entry(it) }
                }
            } else {
                items(channels, key = { it.url }) { Entry(it) }
            }
        }
    }
}

@Composable
private fun ChannelEntry(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    newUploads: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) background else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { if (newUploads) stateDescription = "New uploads" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            icon()
            if (newUploads) NewUploadsDot(ring = background, Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * YouTube's blue dot for a channel with new uploads, on its avatar's corner: ringed with the
 * row's own colour ([ring]), so it stands apart from the picture.
 */
@Composable
private fun NewUploadsDot(ring: Color, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier
            .size(12.dp)
            .background(ring, CircleShape)
            .padding(2.dp)
            .background(if (dark) Color(0xFF3EA6FF) else Color(0xFF065FD4), CircleShape),
    )
}

/**
 * The feed, newest first: its videos, or its Shorts (picked in the chips above, when there are
 * any), with the refresh progress and any errors at the top. [onVideoShown] hears of each
 * video as it comes on screen (to fill in its length).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedGrid(
    state: FeedUiState,
    videos: List<VideoItem>,
    shorts: List<VideoItem>,
    showShorts: Boolean,
    onShowShorts: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onVideoShown: (VideoItem) -> Unit,
    modifier: Modifier,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // Kept in place above the list, whichever it shows.
            if (shorts.isNotEmpty()) VideosOrShorts(showShorts, onShowShorts, Modifier.padding(horizontal = 16.dp))
            // The status lines in line with the list: phone rows have no room around them,
            // the grids' cards do.
            if (showShorts) {
                ShortsGrid(Modifier.weight(1f)) {
                    feedStatus(state, inset = 0.dp)
                    shortItems(shorts, onClick = { onOpenVideo(it.url) })
                }
            } else {
                VideoGrid(Modifier.weight(1f)) { wide ->
                    feedStatus(state, inset = if (wide) 0.dp else 16.dp)
                    videoItems(videos, wide, onClick = { onOpenVideo(it.url) }, onChannelClick = onOpenChannel, onShown = onVideoShown)
                }
            }
        }
    }
}

/** How the refresh is going, and what went wrong, above the feed; [inset] from the sides. */
private fun LazyGridScope.feedStatus(state: FeedUiState, inset: Dp) {
    state.progress?.let { (done, total) ->
        fullWidthItem {
            Column(Modifier.padding(horizontal = inset, vertical = 8.dp)) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Checking channels $done / $total",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    state.error?.let { err ->
        fullWidthItem {
            Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = inset, vertical = 16.dp))
        }
    }
    if (state.failedChannels.isNotEmpty()) {
        fullWidthItem {
            Text(
                "Couldn't load ${state.failedChannels.size} channel(s): " +
                    state.failedChannels.take(5).joinToString() +
                    if (state.failedChannels.size > 5) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = inset, vertical = 4.dp),
            )
        }
    }
}

/** Videos or Shorts: the feed shows one or the other, so Shorts don't crowd out the rest. */
@Composable
private fun VideosOrShorts(showShorts: Boolean, onShowShorts: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !showShorts, onClick = { onShowShorts(false) }, label = { Text("Videos") })
        FilterChip(selected = showShorts, onClick = { onShowShorts(true) }, label = { Text("Shorts") })
    }
}

/**
 * List of subscribed channels, with unsubscribe, Google Takeout import and export, and the
 * cleanup of channels that are gone or haven't uploaded for a while.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: FeedViewModel, onBack: () -> Unit) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cleanup = viewModel<CleanupViewModel>()
    CleanupDialogs(cleanup)

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val app = context.applicationContext
        vm.exportTakeout(uri) { message -> Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }
    var menu by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The application context: the import can outlive this screen.
        val app = context.applicationContext
        vm.importTakeout(uri) { message -> Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions (${channels.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        importer.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
                    }) { Text("Import") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export as CSV") },
                                enabled = channels.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    exporter.launch("subscriptions.csv")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clean up…") },
                                enabled = channels.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    cleanup.start()
                                },
                            )
                        }
                    }
                },
            )
        },
        // room for the mini player while a video plays
        bottomBar = { MiniPlayerSlot(Modifier.navigationBarsPadding()) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "To bring over your YouTube subscriptions: go to takeout.google.com, " +
                            "select only \"YouTube and YouTube Music\", and under " +
                            "\"All YouTube data included\" pick only subscriptions. Download it, " +
                            "unzip it, then tap Import and choose subscriptions.csv.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (channels.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {
                            importer.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
                        }) { Text("Import subscriptions.csv") }
                    }
                }
            }
            items(channels, key = { it.url }) { channel ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = channel.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                    )
                    Text(channel.name, Modifier.weight(1f))
                    IconButton(onClick = { vm.unsubscribe(channel) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Unsubscribe from ${channel.name}")
                    }
                }
            }
        }
    }
}
