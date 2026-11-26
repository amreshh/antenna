package com.antenna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antenna.data.Channel

/**
 * A channel's page, opened from its avatar in the search results: who it is, a Subscribe
 * button, and its videos, newest first, loading more as you scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(vm: ChannelViewModel, channel: Channel, onBack: () -> Unit, onOpenVideo: OpenVideo) {
    LaunchedEffect(channel.url) { vm.open(channel) }
    val state by vm.state.collectAsStateWithLifecycle()
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
    // Until open() has run, the state can still be the previously shown channel's.
    val current = state.channel?.url == channel.url
    val shown = state.channel?.takeIf { current } ?: channel
    val subscribed = subscriptions.any { it.url == shown.url }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shown.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        // room for the mini player while a video plays
        bottomBar = { MiniPlayerSlot(Modifier.navigationBarsPadding()) },
    ) { padding ->
        VideoGrid(Modifier.padding(padding).fillMaxSize()) { wide ->
            fullWidthItem(key = "header") {
                ChannelHeader(
                    shown,
                    subscribers = if (current) state.subscribers else "",
                    subscribed = subscribed,
                    onToggleSubscribe = vm::toggleSubscribe,
                    modifier = Modifier.padding(horizontal = if (wide) 0.dp else 16.dp, vertical = 8.dp),
                )
            }
            if (!current) return@VideoGrid

            val error = state.error
            if (error != null && state.items.isEmpty()) {
                fullWidthItem(key = "error") {
                    Column(Modifier.padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TAG_CHANNEL_ERROR))
                        TextButton(onClick = { vm.open(channel) }) { Text("Try again") }
                    }
                }
            } else if (!state.loading && state.items.isEmpty()) {
                fullWidthItem(key = "empty") {
                    Text("This channel has no videos.", Modifier.padding(16.dp))
                }
            }

            videoItems(state.items, wide, onClick = { onOpenVideo(it.url, positionOf(state.items, it)) })

            if (state.loading || state.canLoadMore) {
                fullWidthItem(key = "more") {
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

/** The channel page's name and error message, for the smoke test. */
internal const val TAG_CHANNEL_NAME = "channel_name"
internal const val TAG_CHANNEL_ERROR = "channel_error"

@Composable
private fun ChannelHeader(
    channel: Channel,
    subscribers: String,
    subscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChannelAvatar(channel.avatarUrl, Modifier.size(72.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TAG_CHANNEL_NAME),
            )
            if (subscribers.isNotEmpty()) {
                Text(
                    subscribers,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (subscribed) {
            OutlinedButton(onClick = onToggleSubscribe) { Text("Subscribed") }
        } else {
            Button(onClick = onToggleSubscribe) { Text("Subscribe") }
        }
    }
}
