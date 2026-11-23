package com.example.antenna.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.antenna.data.videoIdOf
import com.example.antenna.data.watchUrl
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.WatchLaterStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.antenna.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.antenna.data.Channel
import com.example.antenna.data.VideoItem

/**
 * The app's only screen: the tabs, with the player drawn over them (PlayerSheet). Minimizing
 * the player keeps it playing in a bar above the tabs while you browse.
 *
 * Also opens YouTube video links shared from or opened in other apps (singleTask, so they
 * play here rather than in a second copy of the app). Rotation, resizing and dark mode are
 * handled by the screen itself (see the manifest), so they don't interrupt the video.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        val YOUTUBE_URL = Regex("""https?://(www\.|m\.)?(youtube\.com|youtu\.be)/\S+""")
    }

    private val player: PlayerViewModel by viewModels()

    @kotlin.OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again when the screen is recreated, or reopened from Recents with the link
        // that first opened it: the player remembers what was playing.
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) openLink(intent, fromAnotherVideo = false)

        setContent {
            AntennaTheme {
                val dock = remember { MiniPlayerDock { player.currentUrl != null } }
                val config = LocalConfiguration.current
                // A phone on its side has no room for anything but the video. A tablet held
                // sideways does, so only go fullscreen there when asked to.
                val autoFullscreen = config.orientation == Configuration.ORIENTATION_LANDSCAPE && isShortWindow()
                val open = player.currentUrl != null
                val fullscreen = open && player.expanded && (player.userFullscreen || autoFullscreen)
                LaunchedEffect(fullscreen) { applyImmersive(fullscreen) }
                // Phones turn sideways for fullscreen video; tablets already have the room, so leave them be.
                val phone = config.smallestScreenWidthDp < 600
                LaunchedEffect(player.userFullscreen, phone) {
                    requestedOrientation = if (player.userFullscreen && phone) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }

                CompositionLocalProvider(LocalMiniPlayerDock provides dock) {
                    // Test tags are resource ids for UI Automator (the smoke test).
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        // The channel page shown over the tabs (opened from search, the feed or
                        // the player); here so the player can open one too.
                        var channel by rememberSaveable(stateSaver = ChannelSaver) { mutableStateOf<Channel?>(null) }
                        MainScreen(onOpenVideo = player::open, channel = channel, onChannelChange = { channel = it })
                        if (open) {
                            PlayerSheet(
                                vm = player,
                                onOpenChannel = {
                                    player.minimize()
                                    channel = it
                                },
                                fullscreen = fullscreen,
                                // can't leave fullscreen while the window is too short for the normal layout
                                showFullscreenButton = player.userFullscreen || !autoFullscreen,
                            )
                        }
                    }
                }
            }
        }

        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { player.runWhileStarted() } }
    }

    /** A link shared to the app, or opened with it, while it's already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent, fromAnotherVideo = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge() // the status bar icons follow dark mode
    }

    override fun onStop() {
        super.onStop()
        player.onStop(changingConfigurations = isChangingConfigurations)
    }

    /**
     * Plays the video in a link from another app. Other apps can send anything, so only the
     * video id is taken from it, and the link is rebuilt from that. With [fromAnotherVideo],
     * Back returns to the video that was playing.
     */
    private fun openLink(intent: Intent, fromAnotherVideo: Boolean) {
        val link = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { YOUTUBE_URL.find(it)?.value }
            else -> return // opened from the launcher
        }
        val url = link?.let(::videoIdOf)?.let(::watchUrl)
        when {
            url == null -> Toast.makeText(this, "Not a YouTube video link", Toast.LENGTH_SHORT).show()
            fromAnotherVideo -> player.openLink(url)
            else -> player.open(url)
        }
    }

    private fun applyImmersive(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private enum class Tab(val label: String) {
    ForYou("For you"), Subscriptions("Subscriptions"), WatchLater("Watch later"), History("History"), Analytics("Analytics")
}
private enum class Overlay { Settings, Channels }

/** Remembers an opened channel across rotation and process death. */
internal val ChannelSaver = listSaver<Channel?, String?>(
    save = { c -> if (c == null) emptyList() else listOf(c.url, c.name, c.avatarUrl) },
    restore = { l -> if (l.size == 3) Channel(l[0]!!, l[1]!!, l[2]) else null },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenVideo: (String) -> Unit, channel: Channel?, onChannelChange: (Channel?) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.ForYou) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    /** Search (the magnifier in the top bar): shown in place of the current tab. */
    var searching by rememberSaveable { mutableStateOf(false) }
    // Keeps each tab's saved state (how far down its list is scrolled) while another tab or
    // screen replaces it, so Back from a channel returns to the same spot in the results.
    val tabsState = rememberSaveableStateHolder()
    var confirmClearHistory by remember { mutableStateOf(false) }
    val context = LocalContext.current

    when (overlay) {
        Overlay.Settings -> {
            BackHandler { overlay = null }
            SettingsScreen(onBack = { overlay = null })
            return
        }
        Overlay.Channels -> {
            BackHandler { overlay = null }
            ChannelsScreen(vm = viewModel<FeedViewModel>(), onBack = { overlay = null })
            return
        }
        null -> Unit
    }
    // A channel's page, shown instead of the tabs.
    channel?.let { c ->
        BackHandler { onChannelChange(null) }
        ChannelScreen(viewModel<ChannelViewModel>(), c, onBack = { onChannelChange(null) }, onOpenVideo = onOpenVideo)
        return
    }
    BackHandler(enabled = searching) { searching = false }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear watch history?") },
            text = { Text("This also resets your For you recommendations.") },
            confirmButton = {
                TextButton(onClick = {
                    WatchHistoryStore.get(context).clear()
                    confirmClearHistory = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            if (searching) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searching = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { SearchField(viewModel<SearchViewModel>()) },
                )
                return@Scaffold
            }
            TopAppBar(
                title = { AntennaLogo() },
                actions = {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    when (tab) {
                        Tab.Subscriptions -> IconButton(onClick = { overlay = Overlay.Channels }) {
                            Icon(Icons.Default.Edit, contentDescription = "Manage subscriptions")
                        }
                        Tab.History -> IconButton(onClick = { confirmClearHistory = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear history")
                        }
                        Tab.WatchLater -> TextButton(onClick = {
                            val history = WatchHistoryStore.get(context)
                            val removed = WatchLaterStore.get(context).removeWatched { history.entryFor(it.url)?.isWatched == true }
                            val message = if (removed == 0) "No watched videos to remove" else "Removed $removed watched video${if (removed == 1) "" else "s"}"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }) { Text("Remove watched") }
                        Tab.ForYou, Tab.Analytics -> Unit
                    }
                    IconButton(onClick = { overlay = Overlay.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                MiniPlayerSlot()
                // the page's own colour, not Material's grey, so the bar blends in
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = !searching && tab == t,
                            onClick = {
                                tab = t
                                searching = false
                            },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.ForYou -> Icons.Default.Home
                                        Tab.Subscriptions -> Icons.AutoMirrored.Filled.List
                                        Tab.WatchLater -> BookmarkIcon
                                        Tab.History -> Icons.Default.DateRange
                                        Tab.Analytics -> AnalyticsIcon
                                    },
                                    contentDescription = null,
                                )
                            },
                            // Five tabs on a phone: a long name ("Subscriptions") shrinks a
                            // little to fit rather than being cut off.
                            label = {
                                Text(
                                    t.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        tabsState.SaveableStateProvider(if (searching) "search" else tab.name) {
            if (searching) {
                SearchResults(viewModel<SearchViewModel>(), onOpenVideo, onOpenChannel = onChannelChange, modifier)
                return@SaveableStateProvider
            }
            when (tab) {
                Tab.ForYou -> ForYouTab(viewModel<ForYouViewModel>(), onOpenVideo, modifier)
                Tab.Subscriptions -> FeedTab(
                    vm = viewModel<FeedViewModel>(),
                    onOpenVideo = onOpenVideo,
                    onOpenChannel = onChannelChange,
                    onManage = { overlay = Overlay.Channels },
                    modifier = modifier,
                )
                Tab.WatchLater -> WatchLaterTab(onOpenVideo, modifier)
                Tab.History -> HistoryTab(onOpenVideo, modifier)
                Tab.Analytics -> {
                    val search = viewModel<SearchViewModel>()
                    AnalyticsTab(
                        onOpenChannel = onChannelChange,
                        // a topic: search for it, as if typed
                        onSearch = { topic ->
                            search.searchFor(topic)
                            searching = true
                        },
                        modifier = modifier,
                    )
                }
            }
        }
    }
}

/** The logo, in the top bar like YouTube's. */
@Composable
private fun AntennaLogo() {
    // No background behind the TV; its cream lines turn near-black on the white bar.
    val logo = if (isSystemInDarkTheme()) R.drawable.ic_logo_dark else R.drawable.ic_logo_light
    Image(painterResource(logo), contentDescription = "Antenna", modifier = Modifier.size(32.dp))
}

/** The search's error message, for the smoke test (it tells YouTube's limits from bugs). */
internal const val TAG_SEARCH_ERROR = "search_error"

/** YouTube's suggestions for what's being typed; tapping one searches for it. */
@Composable
private fun Suggestions(suggestions: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    LazyColumn(modifier) {
        items(suggestions) { text ->
            Row(
                Modifier
                    .widthIn(max = 776.dp)
                    .fillMaxWidth()
                    .clickable { onPick(text) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The search box, in the top bar while searching. It takes the focus (and brings up the
 * keyboard) when there are no results yet.
 */
@Composable
private fun SearchField(vm: SearchViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.items.isEmpty()) focusRequester.requestFocus() }

    TextField(
        value = state.query,
        onValueChange = vm::onQueryChange,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(end = 16.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { vm.onEditingChange(it.isFocused) }
            // A hardware keyboard's Enter: search when it's let go. Searching when it's
            // pressed moved the focus to the Back button, which then got the key's release
            // as a click and closed search.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    vm.search()
                    focus.clearFocus()
                }
                true
            },
        singleLine = true,
        placeholder = { Text("Search YouTube") },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = {
                    vm.onQueryChange("")
                    focusRequester.requestFocus()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            vm.search()
            focus.clearFocus()
        }),
    )
}

@Composable
private fun SearchResults(
    vm: SearchViewModel,
    onOpenVideo: (String) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    modifier: Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    if (state.editing && state.query.isNotBlank() && state.suggestions.isNotEmpty()) {
        Suggestions(state.suggestions, modifier) { picked ->
            vm.searchFor(picked)
            focus.clearFocus()
        }
        return
    }

    Column(modifier) {
        if (state.error != null && state.items.isEmpty()) {
            Text(
                state.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp).testTag(TAG_SEARCH_ERROR),
            )
        }

        VideoGrid(Modifier.fillMaxSize()) { wide ->
            videoItems(state.items, wide, onClick = { onOpenVideo(it.url) }, onChannelClick = onOpenChannel)
            if (state.loading || state.canLoadMore) {
                fullWidthItem {
                    if (state.canLoadMore && !state.loading) {
                        // reached the bottom: fetch the next page
                        LaunchedEffect(state.items.size) { vm.loadMore() }
                    }
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoRow(
    item: VideoItem,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onChannelClick: (() -> Unit)? = null,
) {
    // Watch progress comes straight from the history store, so every list
    // (search, feed, For you, up next) shows the red bar like YouTube does.
    val entry = rememberHistoryEntry(item)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoThumbnail(item, entry, Modifier.width(160.dp))
        VideoText(item, entry, Modifier.weight(1f), onChannelClick = onChannelClick)
        trailing?.invoke()
    }
}
