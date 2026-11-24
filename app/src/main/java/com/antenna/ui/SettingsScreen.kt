package com.antenna.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.antenna.data.Prefs
import com.antenna.sponsorblock.SponsorCategory

private val QUALITIES = listOf(360, 480, 720, 1080, 1440, 2160)
private val METERED_QUALITIES = listOf(360, 480, 720, 1080, Prefs.SAME_AS_WIFI)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var categories by remember { mutableStateOf(prefs.skipCategories) }
    var maxHeight by remember { mutableIntStateOf(prefs.maxHeight) }
    var maxHeightMetered by remember { mutableIntStateOf(prefs.maxHeightMetered) }
    var hideWatched by remember { mutableStateOf(prefs.hideWatched) }
    var reportSkips by remember { mutableStateOf(prefs.reportSkips) }
    var prefetch by remember { mutableStateOf(prefs.prefetch) }

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
        // room for the mini player while a video plays
        bottomBar = { MiniPlayerSlot(Modifier.navigationBarsPadding()) },
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

            item { Header("Starting videos") }
            item {
                SettingRow(
                    "Get likely videos ready",
                    detail = "When the app opens, it fetches the details of up to 3 videos you're likely to " +
                        "watch, so they start sooner: a few small requests, never any video. It fetches " +
                        "fewer, or none, when its guesses aren't opened. Analytics shows how it's doing.",
                    onClick = {
                        prefetch = !prefetch
                        prefs.prefetch = prefetch
                    },
                ) {
                    Switch(checked = prefetch, onCheckedChange = null)
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

            item { Header("Max video quality on mobile data") }
            item {
                Text(
                    "Also on other metered networks, like a phone's hotspot. Video is nearly all the data " +
                        "the app uses: 480p is several times less than 1080p.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(METERED_QUALITIES) { q ->
                SettingRow(if (q == Prefs.SAME_AS_WIFI) "Same as on Wi-Fi" else "${q}p", onClick = {
                    maxHeightMetered = q
                    prefs.maxHeightMetered = q
                }) {
                    RadioButton(selected = maxHeightMetered == q, onClick = null)
                }
            }

            item { Header("About") }
            item { InfoRow("Version", remember(context) { appVersion(context) }) }
            item { InfoRow("Source code", "github.com/amreshh/antenna", onClick = { openLink(context, REPO) }) }
            item { InfoRow("Releases", "The latest version, and what changed", onClick = { openLink(context, "$REPO/releases") }) }
            item { InfoRow("License", "GNU GPL 3.0 or later", onClick = { openLink(context, "$REPO/blob/main/LICENSE") }) }
            item {
                Text(
                    "Videos come from YouTube through NewPipeExtractor, and play straight from their stream " +
                        "URLs, so YouTube's ads are never loaded. Sponsor segments are crowdsourced by " +
                        "sponsor.ajay.app (CC BY-NC-SA 4.0). Your history, subscriptions and stats stay on " +
                        "this phone. Not affiliated with YouTube or Google.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private const val REPO = "https://github.com/amreshh/antenna"

/** "0.4.2 (4002)", with " · debug build" for one that isn't a release. */
private fun appVersion(context: Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        ?: return "Unknown"
    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})" + if (debug) " · debug build" else ""
}

/** Opens [url] in the browser. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

/** A line of information: what it is, and its value under it; with [onClick], a link. */
@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
