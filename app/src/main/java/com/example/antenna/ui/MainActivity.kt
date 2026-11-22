package com.example.antenna.ui

import android.os.Bundle
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
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.antenna.data.VideoItem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AntennaTheme {
                MainScreen(onOpenVideo = { url -> startActivity(PlayerActivity.intent(this, url)) })
            }
        }
    }
}

private enum class Tab(val label: String) {
    ForYou("For you"), Search("Search"), Subscriptions("Subscriptions"), History("History")
}
private enum class Overlay { Settings, Channels }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenVideo: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.ForYou) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
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
            TopAppBar(
                title = { Text(if (tab == Tab.ForYou) "Antenna" else tab.label) },
                actions = {
                    when (tab) {
                        Tab.Subscriptions -> IconButton(onClick = { overlay = Overlay.Channels }) {
                            Icon(Icons.Default.Edit, contentDescription = "Manage subscriptions")
                        }
                        Tab.History -> IconButton(onClick = { confirmClearHistory = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear history")
                        }
                        else -> Unit
                    }
                    IconButton(onClick = { overlay = Overlay.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.ForYou -> Icons.Default.Home
                                    Tab.Search -> Icons.Default.Search
                                    Tab.Subscriptions -> Icons.AutoMirrored.Filled.List
                                    Tab.History -> Icons.Default.DateRange
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (tab) {
            Tab.ForYou -> ForYouTab(viewModel<ForYouViewModel>(), onOpenVideo, modifier)
            Tab.Search -> SearchTab(viewModel<SearchViewModel>(), onOpenVideo, modifier)
            Tab.Subscriptions -> FeedTab(
                vm = viewModel<FeedViewModel>(),
                onOpenVideo = onOpenVideo,
                onManage = { overlay = Overlay.Channels },
                modifier = modifier,
            )
            Tab.History -> HistoryTab(onOpenVideo, modifier)
        }
    }
}

@Composable
private fun SearchTab(vm: SearchViewModel, onOpenVideo: (String) -> Unit, modifier: Modifier) {
    val state by vm.state.collectAsState()
    val focus = LocalFocusManager.current

    Column(modifier) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Search YouTube") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                vm.search()
                focus.clearFocus()
            }),
        )

        if (state.error != null && state.items.isEmpty()) {
            Text(
                state.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }

        VideoGrid(Modifier.fillMaxSize()) { wide ->
            videoItems(state.items, wide, onClick = { onOpenVideo(it.url) })
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
        VideoText(item, entry, Modifier.weight(1f))
        trailing?.invoke()
    }
}
