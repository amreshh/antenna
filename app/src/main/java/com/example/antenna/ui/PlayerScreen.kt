package com.example.antenna.ui

import com.example.antenna.data.videoIdOf
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import android.view.View
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.example.antenna.data.Channel
import com.example.antenna.data.VideoItem
import com.example.antenna.data.formatDuration
import com.example.antenna.sponsorblock.Segment
import com.example.antenna.sponsorblock.SponsorCategory
import org.schabi.newpipe.extractor.stream.Description
import kotlin.math.roundToInt

/**
 * Test tags for the player's state, visible to UI Automator as resource ids: the smoke test
 * (.github/scripts/smoke-test.sh) reads them to tell a loaded video from an error, and to
 * find the minimized player.
 */
internal const val TAG_TITLE = "player_title"
internal const val TAG_ERROR = "player_error"
internal const val TAG_MINI = "mini_player"

/** Width of the Up next column next to the video on big screens. */
private val SideWidth = 400.dp

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Error(val message: String) : PlayerUiState
    data class Ready(
        val title: String,
        val uploader: String,
        /** null if the uploader's channel couldn't be identified */
        val channel: Channel?,
        /** "1.2M subscribers", empty if the channel hides it */
        val subscribers: String = "",
        /** null while the SponsorBlock lookup is still running */
        val segments: List<Segment>?,
        /** YouTube's "up next" recommendations for this video */
        val related: List<VideoItem>,
        /** "1.2M views · 3 days ago" */
        val stats: String = "",
        /** As YouTube sends it (HTML with links); shown by [DescriptionBox]. */
        val description: Description? = null,
    ) : PlayerUiState
}

/**
 * The player, drawn over the tabs. Full size it covers the screen; swiped down (or with
 * Back) it shrinks into the mini player above the tabs' navigation bar, where the video
 * keeps playing while you browse. Tap it or swipe it up to bring it back. On phones the mini
 * player is a bar across the screen; on big screens ([isWideWindow]) a floating video card
 * in the bottom corner, over the tabs.
 *
 * Everything in between is one layout driven by [SheetState.progress] (0 = full size,
 * 1 = minimized): the video shrinks from the full width to the mini player's video, the
 * details fade out and the mini player's title and buttons fade in. The progress is
 * read while measuring and drawing, so following a finger doesn't recompose anything.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId
@Composable
internal fun PlayerSheet(
    vm: PlayerViewModel,
    /** The uploader's avatar or name was tapped. */
    onOpenChannel: (Channel) -> Unit,
    fullscreen: Boolean,
    showFullscreenButton: Boolean,
) {
    val player = vm.player
    val state = vm.uiState
    val context = LocalContext.current
    val dock = LocalMiniPlayerDock.current
    val subscribed by vm.subscribedChannels.collectAsStateWithLifecycle()
    val isSubscribed = (state as? PlayerUiState.Ready)?.channel?.url
        ?.let { u -> subscribed.any { it.url == u } } == true
    val watchLaterIds by vm.watchLaterIds.collectAsStateWithLifecycle()
    val savedForLater = vm.currentUrl?.let(::videoIdOf)?.let { it in watchLaterIds } == true

    val sheet = rememberSheetState()
    sheet.draggable = !fullscreen
    sheet.onSettle = { expanded -> if (expanded) vm.expand() else vm.minimize() }
    // Also opens the sheet (it starts out minimized) when a video is first opened.
    LaunchedEffect(vm.expanded) { sheet.animateTo(if (vm.expanded) 0f else 1f) }
    BackHandler(enabled = vm.expanded) { vm.back() }

    // Keep the screen on while a video plays, not while it's paused.
    val view = LocalView.current
    DisposableEffect(view, vm.isPlaying) {
        view.keepScreenOn = vm.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val atTop by remember { derivedStateOf { sheet.progress == 0f } }
    // The quality button shows with the player's own controls (and while its menu is open).
    var controlsVisible by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    val showMini by remember { derivedStateOf { sheet.progress > 0f } }
    val showDetails by remember { derivedStateOf { sheet.progress < 1f } }

    val onLink: (String) -> Unit = { link ->
        vm.openDescriptionLink(link) { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val wide = isExpandedWindow()
    val floating = isWideWindow()
    val related = (state as? PlayerUiState.Ready)?.related.orEmpty()
    val details = @Composable {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                PlayerUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is PlayerUiState.Error -> ErrorMessage(state.message, onRetry = vm::retry)
                is PlayerUiState.Ready -> {
                    // A playback error keeps the details: the video may play after Try again.
                    vm.playbackError?.let { ErrorMessage(it, onRetry = vm::retry, Modifier.padding(bottom = 12.dp)) }
                    VideoDetails(
                        state, isSubscribed, vm::toggleSubscribe, vm::watchSegment, onLink, onOpenChannel,
                        savedForLater = savedForLater, onToggleWatchLater = vm::toggleWatchLater,
                    )
                }
            }
        }
    }
    val upNext = @Composable {
        if (related.isNotEmpty()) {
            Text(
                "Up next",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            related.forEach { item -> VideoRow(item, onClick = { vm.openLink(item.url) }, trailing = { VideoMenu(item) }) }
        }
    }

    val mainScroll = rememberScrollState()
    val sideScroll = rememberScrollState()
    // jump back to the top when a new video opens
    LaunchedEffect(state is PlayerUiState.Loading) {
        if (state is PlayerUiState.Loading) {
            mainScroll.scrollTo(0)
            sideScroll.scrollTo(0)
        }
    }

    val background = MaterialTheme.colorScheme.background
    val miniBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val insets = WindowInsets.safeDrawing
    val navigationBars = WindowInsets.navigationBars
    val fadeOut = { (1f - sheet.progress * 2.5f).coerceIn(0f, 1f) } // gone by 40% of the way down
    val fadeIn = { ((sheet.progress - 0.6f) * 2.5f).coerceIn(0f, 1f) } // there for the last 40%
    // The floating mini player gets its rounded corners and shadow as it shrinks.
    val floatingCard: GraphicsLayerScope.() -> Unit = {
        if (floating && !fullscreen) {
            shape = RoundedCornerShape(FloatingMiniCorner.toPx() * sheet.progress)
            clip = true
        }
    }

    Layout(
        contents = listOf(
            // The sheet's background. It also takes the touches the rest doesn't, so they
            // don't reach the tabs underneath.
            {
                Box(
                    Modifier
                        .graphicsLayer {
                            floatingCard()
                            if (floating && !fullscreen) shadowElevation = FloatingMiniElevation.toPx() * sheet.progress
                        }
                        .drawBehind {
                            val mini = if (floating) Color.Black else miniBackground
                            drawRect(if (fullscreen) Color.Black else lerp(background, mini, sheet.progress))
                        }
                        .sheetDrag(sheet),
                )
            },
            // The video: drag it down to minimize, or (minimized) tap it to bring it back.
            {
                Box(Modifier.graphicsLayer(floatingCard).sheetDrag(sheet, onTap = { (!atTop).also { if (it) vm.expand() } })) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE },
                                )
                            }
                        },
                        update = { view ->
                            // Only the full-size player has controls; minimized, a tap opens it.
                            view.useController = atTop
                            if (!atTop) view.hideController()
                            view.setFullscreenButtonState(fullscreen)
                            view.setFullscreenButtonClickListener(
                                if (showFullscreenButton) PlayerView.FullscreenButtonClickListener { vm.toggleFullscreen() }
                                else null,
                            )
                        },
                        // if the view is ever rebuilt, let go of the player so the new one gets the picture
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (atTop && (controlsVisible || qualityMenu) && vm.qualities.isNotEmpty()) {
                        QualityButton(
                            qualities = vm.qualities,
                            chosen = vm.chosenQuality,
                            playing = vm.playingQuality,
                            menuOpen = qualityMenu,
                            onMenuOpenChange = { qualityMenu = it },
                            onPick = vm::setQuality,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        )
                    }
                }
            },
            // Below the video: the details, and on phones Up next under them.
            {
                if (showDetails && !fullscreen) {
                    Column(Modifier.graphicsLayer { alpha = fadeOut() }.verticalScroll(mainScroll)) {
                        details()
                        if (!wide) upNext()
                    }
                }
            },
            // Big screens: Up next in a column of its own, next to the video.
            {
                if (showDetails && !fullscreen && wide) {
                    Column(Modifier.graphicsLayer { alpha = fadeOut() }.verticalScroll(sideScroll)) {
                        upNext()
                    }
                }
            },
            // The mini player's buttons: over the floating video on big screens, and on
            // phones next to the small video, with the title.
            {
                if (showMini && !fullscreen && floating) {
                    MiniOverlay(
                        showPlayButton = vm.showPlayButton,
                        onExpand = vm::expand,
                        onPlayPause = vm::playPause,
                        onClose = vm::close,
                        modifier = Modifier.graphicsLayer { alpha = fadeIn() }.sheetDrag(sheet),
                    )
                } else if (showMini && !fullscreen) {
                    val (title, subtitle) = when (state) {
                        PlayerUiState.Loading -> "Loading…" to ""
                        is PlayerUiState.Error -> "Can't play this video" to state.message
                        is PlayerUiState.Ready -> state.title to state.uploader
                    }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        MiniControls(
                            title = title,
                            subtitle = subtitle,
                            showPlayButton = vm.showPlayButton,
                            onExpand = vm::expand,
                            onPlayPause = vm::playPause,
                            onClose = vm::close,
                            modifier = Modifier.graphicsLayer { alpha = fadeIn() }.sheetDrag(sheet),
                        )
                    }
                }
            },
            // How far into the video you are, along the bottom of the mini player. The floating
            // one gets it over the whole video, clipped to the card's rounded corners.
            {
                if (showMini && !fullscreen && floating) {
                    Box(Modifier.graphicsLayer(floatingCard)) {
                        MiniProgress(
                            player,
                            Modifier.align(Alignment.BottomStart).height(2.dp).graphicsLayer { alpha = fadeIn() },
                        )
                    }
                } else if (showMini && !fullscreen) {
                    MiniProgress(player, Modifier.graphicsLayer { alpha = fadeIn() })
                }
            },
        ),
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val (backgrounds, videos) = measurables

        if (fullscreen) {
            val full = Constraints.fixed(w, h)
            val placeables = (backgrounds + videos).map { it.measure(full) }
            return@Layout layout(w, h) { placeables.forEach { it.place(0, 0) } }
        }

        val p = sheet.progress
        val miniH = MiniPlayerHeight.roundToPx()
        val left = insets.getLeft(this, layoutDirection)
        val right = insets.getRight(this, layoutDirection)
        val top = insets.getTop(this)
        val bottom = insets.getBottom(this)
        val innerW = (w - left - right).coerceAtLeast(0)
        val sideW = if (wide) SideWidth.roundToPx().coerceAtMost(innerW / 2) else 0
        // The details keep the full-size width all the way down (they're fading out), so
        // their text isn't laid out again on every frame.
        val colW = innerW - sideW
        val lineH = 2.dp.roundToPx()

        if (floating) {
            // The video card in the bottom corner, just above the tabs' navigation bar (the
            // screen's MiniPlayerSlot marks its top) or the system bar.
            val margin = FloatingMiniMargin.roundToPx()
            val navTop = dock.top ?: (h - navigationBars.getBottom(this))
            val cardW = (innerW * 0.3f).roundToInt()
                .coerceIn(FloatingMiniMinWidth.roundToPx(), FloatingMiniMaxWidth.roundToPx())
                .coerceAtMost((innerW - 2 * margin).coerceAtLeast(1))
            val cardH = cardW * 9 / 16
            val cardLeft = w - right - margin - cardW
            val cardTop = (navTop - margin - cardH).coerceIn(0, (h - cardH).coerceAtLeast(0))
            sheet.range = cardTop.coerceAtLeast(1).toFloat()

            // Everything moves from its full-size spot to the card's.
            val videoX = lerp(left, cardLeft, p)
            val videoY = lerp(top, cardTop, p)
            val videoW = lerp(colW, cardW, p)
            val videoH = lerp(colW * 9 / 16, cardH, p)
            val bodyH = (h - top - bottom).coerceAtLeast(0)

            val sheetBackground = backgrounds.map { it.measure(Constraints.fixed(lerp(w, cardW, p), lerp(h, cardH, p))) }
            val video = videos.map { it.measure(Constraints.fixed(videoW, videoH)) }
            val detail = measurables[2].map { it.measure(Constraints.fixed(colW, (bodyH - colW * 9 / 16).coerceAtLeast(0))) }
            val side = measurables[3].map { it.measure(Constraints.fixed(sideW, bodyH)) }
            val mini = measurables[4].map { it.measure(Constraints.fixed(videoW, videoH)) }
            val line = measurables[5].map { it.measure(Constraints.fixed(videoW, videoH)) }

            return@Layout layout(w, h) {
                sheetBackground.forEach { it.place(lerp(0, cardLeft, p), lerp(0, cardTop, p)) }
                video.forEach { it.place(videoX, videoY) }
                detail.forEach { it.place(videoX, videoY + videoH) }
                side.forEach { it.place(left + colW + (sideW * p).roundToInt(), videoY) } // slides out to the side
                mini.forEach { it.place(videoX, videoY) }
                line.forEach { it.place(videoX, videoY) }
            }
        }

        // Where the mini player docks: the screen showing marks the spot with a
        // MiniPlayerSlot (above the tabs' navigation bar); otherwise above the system bar.
        val dockTop = (dock.top ?: (h - navigationBars.getBottom(this) - miniH)).coerceIn(0, (h - miniH).coerceAtLeast(0))
        sheet.range = dockTop.coerceAtLeast(1).toFloat()

        val sheetTop = lerp(0, dockTop, p)
        val sheetH = lerp(h, miniH, p)
        val bodyTop = sheetTop + lerp(top, 0, p)
        val bodyH = (sheetH - lerp(top, 0, p) - lerp(bottom, 0, p)).coerceAtLeast(0)
        val videoW = lerp(colW, miniH * 16 / 9, p)
        val videoH = lerp(colW * 9 / 16, miniH, p)

        val sheetBackground = backgrounds.map { it.measure(Constraints.fixed(w, sheetH)) }
        val video = videos.map { it.measure(Constraints.fixed(videoW, videoH)) }
        val detail = measurables[2].map { it.measure(Constraints.fixed(colW, (bodyH - videoH).coerceAtLeast(0))) }
        val side = measurables[3].map { it.measure(Constraints.fixed(sideW, bodyH)) }
        val mini = measurables[4].map { it.measure(Constraints.fixed((innerW - videoW).coerceAtLeast(0), miniH)) }
        val line = measurables[5].map { it.measure(Constraints.fixed(innerW, lineH)) }

        layout(w, h) {
            sheetBackground.forEach { it.place(0, sheetTop) }
            video.forEach { it.place(left, bodyTop) }
            detail.forEach { it.place(left, bodyTop + videoH) }
            side.forEach { it.place(left + colW + (sideW * p).roundToInt(), bodyTop) } // slides out to the side
            mini.forEach { it.place(left + videoW, bodyTop) }
            line.forEach { it.place(left, sheetTop + sheetH - lineH) }
        }
    }
}

/**
 * The quality menu, top right on the video like YouTube's: Auto (the best up to the Settings
 * max, showing what's playing) or one of the video's qualities, for this video.
 */
@Composable
private fun QualityButton(
    qualities: List<Int>,
    chosen: Int?,
    playing: Int?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onPick: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Surface(
            onClick = { onMenuOpenChange(true) },
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
        ) {
            Text(
                when {
                    chosen != null -> "${chosen}p"
                    playing != null -> "Auto · ${playing}p"
                    else -> "Auto"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            val options = listOf<Int?>(null) + qualities
            options.forEach { q ->
                val selected = q == chosen
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                q != null -> "${q}p"
                                playing != null && chosen == null -> "Auto (${playing}p)"
                                else -> "Auto"
                            },
                        )
                    },
                    leadingIcon = {
                        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected")
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        onPick(q)
                        onMenuOpenChange(false)
                    },
                )
            }
        }
    }
}

/** An error, tagged for the smoke test, with a way to try again. */
@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TAG_ERROR))
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
    }
}

private fun lerp(start: Int, stop: Int, fraction: Float): Int = (start + (stop - start) * fraction).roundToInt()

@Composable
private fun VideoDetails(
    state: PlayerUiState.Ready,
    isSubscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    onSegmentClick: (Segment) -> Unit,
    onLink: (String) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    savedForLater: Boolean,
    onToggleWatchLater: () -> Unit,
) {
    Text(state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(TAG_TITLE))
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The avatar and name open the channel's page.
        val channel = state.channel
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .then(
                    if (channel == null) Modifier
                    else Modifier.clickable(onClickLabel = "Open the channel") { onOpenChannel(channel) },
                )
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = channel?.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape),
            )
            Column {
                Text(state.uploader, style = MaterialTheme.typography.bodyLarge)
                if (state.subscribers.isNotEmpty()) {
                    Text(
                        state.subscribers,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.channel != null) {
            if (isSubscribed) {
                OutlinedButton(onClick = onToggleSubscribe) { Text("Subscribed") }
            } else {
                Button(onClick = onToggleSubscribe) { Text("Subscribe") }
            }
        }
    }

    // Actions on the video, like YouTube's row under the channel.
    Row(Modifier.padding(top = 8.dp)) {
        FilterChip(
            selected = savedForLater,
            onClick = onToggleWatchLater,
            label = { Text(if (savedForLater) "Saved for later" else "Watch later") },
            leadingIcon = {
                Icon(
                    if (savedForLater) BookmarkIcon else BookmarkBorderIcon,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
    }

    DescriptionBox(state.stats, state.description, onLink)

    Text(
        "SponsorBlock",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    val segments = state.segments
    when {
        segments == null -> Text("Looking up segments…")
        segments.isEmpty() -> Text("No segments to skip in this video.")
        else -> {
            segments.forEach { seg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSegmentClick(seg) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "${SponsorCategory.labelFor(seg.category)}  " +
                            "${formatDuration(seg.startMs / 1000).ifEmpty { "0:00" }} → " +
                            formatDuration(seg.endMs / 1000),
                    )
                }
            }
            Text(
                "Tap a segment to watch it instead of skipping.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** YouTube-style description: a tinted box with views and date, a few lines, tap for the rest. */
@Composable
private fun DescriptionBox(stats: String, description: Description?, onLink: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(description, linkColor) { descriptionText(description, linkColor) }
    if (stats.isBlank() && text.isBlank()) return
    var expanded by rememberSaveable(text.text) { mutableStateOf(false) }
    // Links in the description go to the player (timestamps, other videos) instead of
    // straight to another app.
    val links = remember(onLink) {
        object : UriHandler {
            override fun openUri(uri: String) = onLink(uri)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        if (stats.isNotBlank()) Text(stats, style = MaterialTheme.typography.labelLarge)
        if (text.isNotBlank()) {
            CompositionLocalProvider(LocalUriHandler provides links) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(
            if (expanded) "Show less" else "…more",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun descriptionText(description: Description?, linkColor: Color): AnnotatedString {
    val content = description?.content.orEmpty().trim()
    if (content.isEmpty()) return AnnotatedString("")
    // YouTube sends HTML (links, line breaks); fromHtml keeps the links tappable, and the
    // color shows which parts are links.
    return if (description?.type == Description.Type.HTML) {
        AnnotatedString.fromHtml(content, linkStyles = TextLinkStyles(SpanStyle(color = linkColor)))
    } else {
        AnnotatedString(content)
    }
}
