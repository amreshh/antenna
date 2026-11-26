package com.antenna.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antenna.data.Channel
import com.antenna.data.FeedRepository
import com.antenna.data.ForYouFeedbackStore
import com.antenna.data.GuessReason
import com.antenna.data.HiddenChannel
import com.antenna.data.NotInterested
import com.antenna.data.Outcome
import com.antenna.data.Prefetcher
import com.antenna.data.Recommender
import com.antenna.data.ShownInForYou
import com.antenna.data.SubscriptionStore
import com.antenna.data.VideoItem
import com.antenna.data.WatchEntry
import com.antenna.data.WatchHistoryStore
import com.antenna.data.WatchLaterStore
import com.antenna.data.WatchLogStore
import com.antenna.data.forgetWatched
import com.antenna.data.videoIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ForYouViewModel(app: Application) : AndroidViewModel(app) {

    private val history = WatchHistoryStore.get(app)
    private val watchLog = WatchLogStore.get(app)
    private val subscriptions = SubscriptionStore.get(app)
    private val feedbackStore = ForYouFeedbackStore.get(app)
    private val shownStore = ShownInForYou.get(app)

    /** The list as it last loaded ([ListLoad]); null until the history, the watch log and what was shown are read. */
    private val load = MutableStateFlow<ListLoad?>(null)

    /** Recent uploads from the channels you watch most (Recommender.favouriteChannels). */
    private val channelUploads = MutableStateFlow<List<VideoItem>>(emptyList())
    private var uploadsFetchedAt = 0L
    private var uploadsJob: Job? = null

    /** When the tab went off the screen (another tab, or another app); 0 while it's on. */
    private var awaySince = 0L

    /** The list loaded afresh: Prefetcher guesses from its top once it's built. (Not as the app starts: it guesses then anyway.) */
    @Volatile
    private var guessOnLoad = false

    /** What the list is built from, passed on only when that changes the list ([sameForRecommendations]). */
    private val inputs = combine(load, history.entries, watchLog.spans) { l, h, spans ->
        l?.let { ListInputs(h, Recommender.outcomes(h, spans, System.currentTimeMillis()), it) }
    }.distinctUntilChanged(::sameForRecommendations)

    /** The recommendations; null until the history has loaded. */
    val items: StateFlow<List<VideoItem>?> =
        combine(inputs, subscriptions.channels, channelUploads, feedbackStore.feedback) { input, channels, uploads, feedback ->
            input?.let {
                Recommender.build(
                    it.history, channels.map { c -> c.name }.toSet(), it.load.seed, uploads,
                    outcomes = it.outcomes, feedback = feedback, shown = it.load.shown,
                ).withAvatars(it.history, channels)
            }
        }
            // What's at the top is what you're likely to tap: guesses for Prefetcher.
            .onEach { list ->
                if (list == null) return@onEach
                Prefetcher.get(app).shows(GuessReason.ForYou, list)
                if (guessOnLoad) {
                    guessOnLoad = false
                    Prefetcher.get(app).onForYouLoaded(list)
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            // All of it first: a list built from part of it would change as the rest came in.
            history.awaitLoaded()
            watchLog.awaitLoaded()
            shownStore.awaitLoaded()
            reload(guess = false)
            history.entries.first { it.isNotEmpty() }
            fetchChannelUploads()
        }
    }

    /** Pull to refresh: the list loads afresh, and new uploads are fetched if the last fetch is a while ago. */
    fun reshuffle() {
        if (load.value != null) reload()
        fetchChannelUploads()
    }

    /** A new shuffle, with what you've passed over so far counting against it; [guess]: and Prefetcher guesses from its top. */
    private fun reload(guess: Boolean = true) {
        guessOnLoad = guess
        load.value = ListLoad(seed = System.currentTimeMillis(), shown = shownStore.counts())
    }

    /** The tab is on the screen. Back after [RELOAD_AFTER_MS] or more, the list loads afresh, like YouTube's home page. */
    fun onTabShown() {
        val away = awaySince
        awaySince = 0
        if (away > 0 && System.currentTimeMillis() - away >= RELOAD_AFTER_MS) reshuffle()
    }

    /** The tab went off the screen: another tab, or another app. */
    fun onTabHidden() {
        awaySince = System.currentTimeMillis()
    }

    /** [video] came on the screen: passed over, unless it's opened. */
    fun onShown(video: VideoItem) {
        videoIdOf(video.url)?.let(shownStore::shown)
    }

    fun notInterested(video: VideoItem): NotInterested? = feedbackStore.notInterested(video)

    fun hideChannel(video: VideoItem): HiddenChannel? = feedbackStore.hideChannel(video)

    fun undo(said: NotInterested) = feedbackStore.forget(said)

    fun undo(said: HiddenChannel) = feedbackStore.showAgain(said)

    /**
     * New uploads from your favourite channels you're not subscribed to, from their RSS
     * feeds (one small request each, like the Subscriptions tab). A failed channel is just
     * left out.
     */
    private fun fetchChannelUploads() {
        val now = System.currentTimeMillis()
        if (uploadsJob?.isActive == true || now - uploadsFetchedAt < UPLOADS_MAX_AGE_MS) return
        uploadsJob = viewModelScope.launch {
            val subscribed = subscriptions.channels.value.map { it.url }.toSet()
            val favourites = withContext(Dispatchers.Default) {
                val h = history.entries.value
                Recommender.favouriteChannels(
                    h, now, exclude = subscribed,
                    outcomes = Recommender.outcomes(h, watchLog.spans.value, now),
                    feedback = feedbackStore.feedback.value,
                )
            }
            if (favourites.isEmpty()) return@launch
            channelUploads.value = FeedRepository.fetch(favourites).items
            uploadsFetchedAt = now
        }
    }

    private companion object {
        const val UPLOADS_MAX_AGE_MS = 30 * 60 * 1000L
        const val RELOAD_AFTER_MS = 30 * 60 * 1000L
    }
}

/**
 * One load of the For you list: its shuffle, and on how many days each video had been shown
 * when it loaded. Both change only when it loads again (the app starting, pulling down, or
 * coming back to the tab after a while), so the videos you scroll past don't move the list
 * under your finger.
 */
private class ListLoad(val seed: Long, val shown: Map<String, Int>)

/** What the For you list is built from: the history, how each video in it went, and the load. */
private class ListInputs(val history: Map<String, WatchEntry>, val outcomes: Map<String, Outcome>, val load: ListLoad)

/**
 * Whether two inputs give the same recommendations: the same load, videos, up next lists, and
 * the same outcome for each video (finished, left early…). How far into a video you are
 * moves its suggestions' scores only a little, and rebuilding For you on it (every few seconds
 * while a video plays) reshuffled the list under your finger.
 */
private fun sameForRecommendations(a: ListInputs?, b: ListInputs?): Boolean {
    if (a == null || b == null) return a === b
    return a.load === b.load && a.history.size == b.history.size &&
        a.history.all { (id, e) -> b.history[id]?.related === e.related && a.outcomes[id]?.watched == b.outcomes[id]?.watched }
}

/**
 * These videos with their channel's avatar where the list they came from had none (the
 * channels' feeds don't): from your subscriptions, or another video of the channel in your
 * history or its up next lists.
 */
private fun List<VideoItem>.withAvatars(history: Map<String, WatchEntry>, subscriptions: List<Channel>): List<VideoItem> {
    if (all { it.channelAvatarUrl != null || it.channelUrl == null }) return this
    val known = HashMap<String, String>()
    fun note(v: VideoItem) {
        val url = v.channelUrl ?: return
        v.channelAvatarUrl?.let { known.putIfAbsent(url, it) }
    }
    history.values.forEach { e ->
        note(e.video)
        e.related.forEach(::note)
    }
    subscriptions.forEach { c -> c.avatarUrl?.let { known[c.url] = it } }
    return map { v -> if (v.channelAvatarUrl != null) v else v.copy(channelAvatarUrl = v.channelUrl?.let(known::get)) }
}

/**
 * Home tab: recommendations built from YouTube's suggestions for what you've watched. Each
 * video's ⋮ has Not interested and Don't recommend channel, with Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouTab(
    vm: ForYouViewModel,
    onOpenVideo: OpenVideo,
    onOpenChannel: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back after a while, the list loads afresh (ForYouViewModel.onTabShown).
    LifecycleResumeEffect(vm) {
        vm.onTabShown()
        onPauseOrDispose { vm.onTabHidden() }
    }
    val items = vm.items.collectAsStateWithLifecycle().value ?: return // the history is still loading
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /** Says what was done, with Undo. */
    fun done(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    val moreOptions: MenuOptions = { video, close ->
        DropdownMenuItem(
            text = { Text("Not interested") },
            leadingIcon = { Icon(NotInterestedIcon, contentDescription = null) },
            onClick = {
                close()
                vm.notInterested(video)?.let { said -> done("Removed from For you") { vm.undo(said) } }
            },
        )
        if (video.channelUrl != null || video.uploader.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Don't recommend channel") },
                leadingIcon = { Icon(DontRecommendIcon, contentDescription = null) },
                onClick = {
                    close()
                    vm.hideChannel(video)?.let { said ->
                        done("For you won't show ${said.name.ifBlank { "this channel" }} again") { vm.undo(said) }
                    }
                },
            )
        }
    }

    Box(modifier) {
        PullToRefreshBox(isRefreshing = false, onRefresh = vm::reshuffle, modifier = Modifier.fillMaxSize()) {
            if (items.isEmpty()) {
                EmptyMessage(
                    "Nothing here yet",
                    "Watch a few videos and YouTube's suggestions for them will show up here. " +
                        "The more you watch, the better it gets.",
                )
            } else {
                VideoGrid(Modifier.fillMaxSize()) { wide ->
                    videoItems(
                        items,
                        wide,
                        onClick = { onOpenVideo(it.url, positionOf(items, it)) },
                        onChannelClick = onOpenChannel,
                        onShown = vm::onShown,
                        moreOptions = moreOptions,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { data ->
            Snackbar(data, Modifier.widthIn(max = 480.dp))
        }
    }
}

/** Watch later tab: the videos you saved, newest first; ✕ takes one off. */
@Composable
fun WatchLaterTab(onOpenVideo: OpenVideo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { WatchLaterStore.get(context) }
    val videos by store.videos.collectAsStateWithLifecycle()

    if (videos.isEmpty()) {
        EmptyMessage(
            "Nothing saved yet",
            "Save videos to watch later from the ⋮ menu on any video, or with Watch later under the one you're watching.",
            modifier,
        )
        return
    }
    VideoGrid(modifier) { wide ->
        videoItems(
            videos,
            wide,
            onClick = { onOpenVideo(it.url, positionOf(videos, it)) },
            trailing = { video ->
                IconButton(onClick = { store.remove(video.url) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove from Watch later")
                }
            },
        )
    }
}

/** History tab: everything you've opened, newest first. */
@Composable
fun HistoryTab(onOpenVideo: OpenVideo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { WatchHistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val loaded by store.loaded.collectAsStateWithLifecycle()
    val sorted = remember(entries) { entries.values.sortedByDescending { it.lastWatchedAt } }
    if (!loaded) return // not "No history yet" while it's still loading

    if (sorted.isEmpty()) {
        EmptyMessage("No history yet", "Videos you watch will show up here.", modifier)
        return
    }
    VideoGrid(modifier) { wide ->
        val videos = sorted.map { it.video }
        videoItems(
            videos,
            wide,
            onClick = { onOpenVideo(it.url, positionOf(videos, it)) },
            trailing = { video ->
                // With what else was recorded about it (the watch log, the app's guesses).
                IconButton(onClick = { forgetWatched(context, video.url) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove from history")
                }
            },
        )
    }
}

@Composable
internal fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}
