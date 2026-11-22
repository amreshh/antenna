package com.example.antenna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.antenna.data.Prefs
import com.example.antenna.sponsorblock.SponsorCategory

private val QUALITIES = listOf(360, 480, 720, 1080, 1440, 2160)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var categories by remember { mutableStateOf(prefs.skipCategories) }
    var maxHeight by remember { mutableIntStateOf(prefs.maxHeight) }
    var hideWatched by remember { mutableStateOf(prefs.hideWatched) }
    var reportSkips by remember { mutableStateOf(prefs.reportSkips) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { Header("SponsorBlock: auto-skip") }
            items(SponsorCategory.entries) { cat ->
                val on = cat.key in categories
                SettingRow(cat.label, onClick = {
                    categories = if (on) categories - cat.key else categories + cat.key
                    prefs.skipCategories = categories
                }) {
                    Switch(checked = on, onCheckedChange = null)
                }
            }

            item {
                SettingRow(
                    "Count my skips on SponsorBlock",
                    detail = "Adds to the segment's public view count. The report names the " +
                        "segment, so SponsorBlock learns which video you watched.",
                    onClick = {
                        reportSkips = !reportSkips
                        prefs.reportSkips = reportSkips
                    },
                ) {
                    Switch(checked = reportSkips, onCheckedChange = null)
                }
            }

            item { Header("Subscriptions") }
            item {
                SettingRow("Hide watched videos", onClick = {
                    hideWatched = !hideWatched
                    prefs.hideWatched = hideWatched
                }) {
                    Switch(checked = hideWatched, onCheckedChange = null)
                }
            }

            item { Header("Max video quality") }
            items(QUALITIES) { q ->
                SettingRow("${q}p", onClick = {
                    maxHeight = q
                    prefs.maxHeight = q
                }) {
                    RadioButton(selected = maxHeight == q, onClick = null)
                }
            }

            item {
                Text(
                    "Sponsor segments are crowdsourced by sponsor.ajay.app. " +
                        "Ads are never loaded because videos play straight from the stream URLs.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
)

@Composable
private fun SettingRow(
    label: String,
    onClick: () -> Unit,
    detail: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}
