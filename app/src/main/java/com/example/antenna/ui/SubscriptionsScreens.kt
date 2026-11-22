package com.example.antenna.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.antenna.data.Prefs
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.videoIdOf

/** The Subscriptions tab: newest uploads from every channel you follow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTab(
    vm: FeedViewModel,
    onOpenVideo: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val channels by vm.channels.collectAsState()
    val context = LocalContext.current
    // re-read each time the tab is shown, so the Settings toggle applies immediately
    val hideWatched = remember { Prefs(context).hideWatched }
    val history = remember(context) { WatchHistoryStore.get(context) }
    val watched by history.entries.collectAsState()
    val visible = remember(state.items, watched, hideWatched) {
        if (!hideWatched) state.items
        else state.items.filterNot { v -> videoIdOf(v.url)?.let { watched[it]?.isWatched } == true }
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

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = modifier) {
        VideoGrid(Modifier.fillMaxSize()) { wide ->
            state.progress?.let { (done, total) ->
                fullWidthItem {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                fullWidthItem { Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            }
            if (state.failedChannels.isNotEmpty()) {
                fullWidthItem {
                    Text(
                        "Couldn't load ${state.failedChannels.size} channel(s): " +
                            state.failedChannels.take(5).joinToString() +
                            if (state.failedChannels.size > 5) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            videoItems(visible, wide, onClick = { onOpenVideo(it.url) })
        }
    }
}

/** List of subscribed channels, with unsubscribe and Google Takeout import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: FeedViewModel, onBack: () -> Unit) {
    val channels by vm.channels.collectAsState()
    val context = LocalContext.current

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val message = runCatching {
            val csv = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val added = vm.importTakeout(csv)
            if (added == 0) "No new channels found in that file" else "Imported $added channels"
        }.getOrElse { "Couldn't read that file: ${it.message}" }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                },
            )
        },
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
