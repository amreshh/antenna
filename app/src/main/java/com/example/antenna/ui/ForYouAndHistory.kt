package com.example.antenna.ui

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
import com.example.antenna.data.Recommender
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.VideoItem
import com.example.antenna.data.WatchHistoryStore
import kotlinx.coroutines.Dispatchers
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

    val items: StateFlow<List<VideoItem>> =
        combine(history.entries, subscriptions.channels, seed) { h, channels, s ->
            Recommender.build(h, channels.map { it.name }.toSet(), s)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun reshuffle() {
        seed.value = System.currentTimeMillis()
    }
}

/** Home tab: recommendations built from YouTube's suggestions for what you've watched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouTab(vm: ForYouViewModel, onOpenVideo: (String) -> Unit, modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()

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

/** History tab: everything you've opened, newest first. */
@Composable
fun HistoryTab(onOpenVideo: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { WatchHistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val sorted = remember(entries) { entries.values.sortedByDescending { it.lastWatchedAt } }

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
