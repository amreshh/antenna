package com.example.antenna.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antenna.data.Channel
import com.example.antenna.data.FeedRepository
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.VideoItem
import com.example.antenna.data.parseTakeoutCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val items: List<VideoItem> = emptyList(),
    val refreshing: Boolean = false,
    /** done / total channels while a refresh is running */
    val progress: Pair<Int, Int>? = null,
    val failedChannels: List<String> = emptyList(),
    val error: String? = null,
)

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SubscriptionStore.get(app)
    val channels: StateFlow<List<Channel>> = store.channels

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** Channels whose videos are currently in the feed. */
    private var loaded = emptySet<String>()
    private var job: Job? = null

    init {
        refresh()
        // Keep the feed in sync when you subscribe/unsubscribe anywhere in the app.
        viewModelScope.launch { store.channels.collect { sync(it) } }
    }

    fun refresh() = fetch(store.channels.value, replace = true)

    fun unsubscribe(channel: Channel) = store.unsubscribe(channel.url)

    /** Imports a Google Takeout subscriptions.csv. Returns how many channels were new. */
    fun importTakeout(csv: String): Int = store.addAll(parseTakeoutCsv(csv))

    /** Drops removed channels locally and fetches only newly added ones. */
    private fun sync(list: List<Channel>) {
        val current = list.map { it.url }.toSet()
        if (current == loaded || _state.value.refreshing) return
        val removed = loaded - current
        if (removed.isNotEmpty()) {
            _state.update { s -> s.copy(items = s.items.filter { it.channelUrl in current }) }
            loaded = loaded - removed
        }
        val added = list.filter { it.url !in loaded }
        if (added.isNotEmpty()) fetch(added, replace = false)
    }

    private fun fetch(channels: List<Channel>, replace: Boolean) {
        if (channels.isEmpty()) {
            if (replace) {
                loaded = emptySet()
                _state.update { FeedUiState() }
            }
            return
        }
        job?.cancel()
        _state.update { it.copy(refreshing = true, progress = 0 to channels.size, error = null) }
        job = viewModelScope.launch {
            try {
                val result = FeedRepository.fetch(channels) { done, total ->
                    _state.update { it.copy(progress = done to total) }
                }
                val urls = channels.map { it.url }.toSet()
                loaded = if (replace) urls else loaded + urls
                _state.update { s ->
                    val merged = if (replace) result.items
                    else (s.items + result.items).distinctBy { it.url }.sortedByDescending { it.uploadedAtMs }
                    s.copy(
                        items = merged,
                        refreshing = false,
                        progress = null,
                        failedChannels = result.failedChannels,
                    )
                }
                // catch subscriptions that changed while we were loading
                sync(store.channels.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, progress = null, error = e.readable()) }
            }
        }
    }
}
