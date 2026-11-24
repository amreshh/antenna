package com.antenna.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antenna.data.ChannelCheck
import com.antenna.data.CleanupReason
import com.antenna.data.SubscriptionCleanup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "No uploads in" choices, in months. */
private val PERIODS = listOf(3 to "3 months", 6 to "6 months", 12 to "1 year", 24 to "2 years", 36 to "3 years", 60 to "5 years")

/** The cleanup's dialogs: progress while checking, then the confirmation. */
@Composable
fun CleanupDialogs(vm: CleanupViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when (val s = state) {
        CleanupState.Idle -> Unit
        is CleanupState.Checking -> AlertDialog(
            onDismissRequest = {}, // only Cancel stops it
            title = { Text("Checking your channels") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${s.done} of ${s.total}: whether each still exists, and when it last uploaded.")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Cancel") } },
        )
        is CleanupState.Checked -> CleanupReview(
            s,
            onRemove = { urls ->
                vm.remove(urls)
                Toast.makeText(context, "Removed ${urls.size} channel${if (urls.size == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            },
            onCancel = vm::cancel,
        )
    }
}

/**
 * Which channels would be removed, and why, before anything is: gone ones, and ones with no
 * upload in the chosen period. Changing what to remove updates the list without checking
 * again; untick a channel to keep it. Channels that couldn't be checked are listed, and kept.
 */
@Composable
private fun CleanupReview(s: CleanupState.Checked, onRemove: (Set<String>) -> Unit, onCancel: () -> Unit) {
    var removeGone by rememberSaveable { mutableStateOf(true) }
    var removeInactive by rememberSaveable { mutableStateOf(true) }
    var months by rememberSaveable { mutableIntStateOf(12) }
    val candidates = remember(s, removeGone, removeInactive, months) {
        SubscriptionCleanup.candidates(s.channels, s.checks, removeGone, months.takeIf { removeInactive }, s.checkedAt)
    }
    var kept by remember(s) { mutableStateOf(emptySet<String>()) } // unticked, by url
    val toRemove = candidates.filter { it.channel.url !in kept }
    val unknown = remember(s) { s.channels.filter { s.checks[it.url] is ChannelCheck.Unknown } }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (candidates.isEmpty()) "Nothing to remove" else "Remove ${toRemove.size} of ${s.channels.size} channels?") },
        text = {
            Column {
                OptionRow("Channels that don't exist anymore", removeGone) { removeGone = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeInactive, onCheckedChange = { removeInactive = it })
                    Text("No uploads in", Modifier.clickable { removeInactive = !removeInactive })
                    PeriodPicker(months) { months = it }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { it.channel.url }) { c ->
                        val on = c.channel.url !in kept
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { kept = if (on) kept + c.channel.url else kept - c.channel.url }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    reasonText(c.reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        item { Text("Every channel still exists and uploaded in that time.", Modifier.padding(vertical = 8.dp)) }
                    }
                    if (unknown.isNotEmpty()) {
                        item {
                            Text(
                                "Couldn't check ${unknown.size} (kept): " + unknown.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRemove(toRemove.mapTo(HashSet()) { it.channel.url }) },
                enabled = toRemove.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (toRemove.isEmpty()) "Remove" else "Remove ${toRemove.size}") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}

@Composable
private fun PeriodPicker(months: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(PERIODS.firstOrNull { it.first == months }?.second ?: "$months months")
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose the period")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PERIODS.forEach { (m, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onPick(m)
                    open = false
                })
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun reasonText(reason: CleanupReason): String = when (reason) {
    is CleanupReason.Gone -> if (reason.message.isBlank()) "Doesn't exist anymore" else "Doesn't exist anymore: ${reason.message}"
    CleanupReason.NoUploads -> "No public uploads at all"
    is CleanupReason.Inactive -> "No uploads since " + Instant.ofEpochMilli(reason.newestMs).atZone(ZoneId.systemDefault()).format(dateFormat)
}
