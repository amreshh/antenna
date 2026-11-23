package com.example.antenna.ui

import com.example.antenna.data.WatchEntry
import kotlinx.coroutines.flow.distinctUntilChanged
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antenna.data.FeedRepository
import com.example.antenna.data.Recommender
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.VideoItem
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.WatchLaterStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class ForYouViewModel(app: Application) : AndroidViewModel(app) {

    private val history = WatchHistoryStore.get(app)
    private val subscriptions = SubscriptionStore.get(app)
    private val seed = MutableStateFlow(System.currentTimeMillis())

    /** Recent uploads from the channels you watch most (Recommender.favouriteChannels). */
    private val channelUploads = MutableStateFlow<List<VideoItem>>(emptyList())
    private var uploadsFetchedAt = 0L
    private var uploadsJob: Job? = null

    /** The recommendations; null until the history has loaded. */
    val items: StateFlow<List<VideoItem>?> =
        combine(
            history.loaded,
            history.entries.distinctUntilChanged(::sameForRecommendations),
            subscriptions.channels,
            seed,
            channelUploads,
        ) { loaded, h, channels, s, uploads ->
            if (!loaded) null else Recommender.build(h, channels.map { it.name }.toSet(), s, uploads)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // History loads from disk in the background: wait for all of it before picking channels.
        viewModelScope.launch {
            history.awaitLoaded()
            history.entries.first { it.isNotEmpty() }
            fetchChannelUploads()
        }
    }

    /** Pull to refresh: reshuffles, and fetches new uploads if the last fetch is a while ago. */
    fun reshuffle() {
        seed.value = System.currentTimeMillis()
        fetchChannelUploads()
    }

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
                Recommender.favouriteChannels(history.entries.value.values, now, exclude = subscribed)
            }
            if (favourites.isEmpty()) return@launch
            channelUploads.value = FeedRepository.fetch(favourites).items
            uploadsFetchedAt = now
        }
    }

    private companion object {
        const val UPLOADS_MAX_AGE_MS = 30 * 60 * 1000L
    }
}

/**
 * Whether two histories give the same recommendations: the same videos, up next lists and
 * finished ones. How far into a video you are moves its suggestions' scores only a little,
 * and rebuilding For you on it (every few seconds while a video plays) reshuffled the list
 * under your finger.
 */
private fun sameForRecommendations(a: Map<String, WatchEntry>, b: Map<String, WatchEntry>): Boolean =
    a.size == b.size && a.all { (id, e) -> b[id]?.let { it.related === e.related && it.isWatched == e.isWatched } == true }

/** Home tab: recommendations built from YouTube's suggestions for what you've watched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouTab(vm: ForYouViewModel, onOpenVideo: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = vm.items.collectAsStateWithLifecycle().value ?: return // the history is still loading

    PullToRefreshBox(isRefreshing = false, onRefresh = vm::reshuffle, modifier = modifier) {
        if (items.isEmpty()) {
            EmptyMessage(
                "Nothing here yet",
                "Watch a few videos and YouTube's suggestions for them will show up here. " +
                    "The more you watch, the better it gets.",
            )
        } else {
            VideoGrid(Modifier.fillMaxSize()) { wide ->
                videoItems(items, wide, onClick = { onOpenVideo(it.url) })
            }
        }
    }
}

/** Watch later tab: the videos you saved, newest first; ✕ takes one off. */
@Composable
fun WatchLaterTab(onOpenVideo: (String) -> Unit, modifier: Modifier = Modifier) {
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
            onClick = { onOpenVideo(it.url) },
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
fun HistoryTab(onOpenVideo: (String) -> Unit, modifier: Modifier = Modifier) {
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
        videoItems(
            sorted.map { it.video },
            wide,
            onClick = { onOpenVideo(it.url) },
            trailing = { video ->
                IconButton(onClick = { store.remove(video.url) }) {
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
