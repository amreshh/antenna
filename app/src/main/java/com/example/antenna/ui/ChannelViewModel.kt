package com.example.antenna.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antenna.data.Channel
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.VideoItem
import com.example.antenna.data.YouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

data class ChannelUiState(
    /** The channel shown; null before one is opened. */
    val channel: Channel? = null,
    /** "1.2M subscribers", empty while loading or when the channel hides it */
    val subscribers: String = "",
    val items: List<VideoItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val canLoadMore: Boolean = false,
)

/** One channel's page (ChannelScreen): its videos, loaded a page at a time as you scroll. */
class ChannelViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SubscriptionStore.get(app)
    val subscriptions: StateFlow<List<Channel>> = store.channels

    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    private var videosTab: ListLinkHandler? = null
    private var nextPage: Page? = null
    private var job: Job? = null

    /**
     * Shows [channel]: straight away with what the list it was tapped in knew (name and
     * avatar), then with its page once that loads. Opening the one already shown keeps it,
     * unless it failed to load.
     */
    fun open(channel: Channel) {
        val current = _state.value
        if (current.channel?.url == channel.url && current.error == null) return
        job?.cancel()
        videosTab = null
        nextPage = null
        _state.value = ChannelUiState(channel = channel, loading = true)
        job = viewModelScope.launch {
            try {
                val page = YouTubeRepository.channel(channel.url)
                videosTab = page.videosTab
                nextPage = page.nextPage
                _state.update {
                    it.copy(
                        channel = page.channel,
                        subscribers = formatCount(page.subscriberCount, "subscriber"),
                        items = page.videos,
                        loading = false,
                        canLoadMore = page.nextPage != null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    fun loadMore() {
        val channel = _state.value.channel ?: return
        val tab = videosTab ?: return
        val page = nextPage ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            try {
                val (items, next) = YouTubeRepository.channelMore(channel, tab, page)
                nextPage = next
                _state.update { s ->
                    s.copy(
                        items = (s.items + items).distinctBy { it.url },
                        loading = false,
                        canLoadMore = next != null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, canLoadMore = false, error = e.readable()) }
            }
        }
    }

    fun toggleSubscribe() {
        val channel = _state.value.channel ?: return
        if (store.isSubscribed(channel.url)) store.unsubscribe(channel.url) else store.subscribe(channel)
    }
}
