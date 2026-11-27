package com.antenna.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.materialIcon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import com.antenna.data.videoIdOf
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
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import android.view.View
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.antenna.data.Channel
import com.antenna.data.OpenSource
import com.antenna.data.VideoItem
import com.antenna.data.formatDuration
import com.antenna.player.SeekBarSegments
import com.antenna.sponsorblock.Segment
import com.antenna.sponsorblock.SponsorCategory
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

/** On big screens the full-size video is this far from the window's edge (left) and top. */
private val FullVideoInset = 16.dp

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
        /** SponsorBlock couldn't be reached, even after trying again: nothing is skipped ([segments] is empty). */
        val segmentsFailed: Boolean = false,
        /** How long the video is, as YouTube says; 0 for live streams. */
        val durationMs: Long = 0,
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
 * keeps playing while you browse. Tap it to bring it back. On phones the mini player is a bar
 * across the screen, which also swipes up; on big screens ([isWideWindow]) a floating video
 * card over the tabs, which you can drag anywhere and resize ([FloatingMini]).
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
    // Whether the player's controls show: the thin progress line runs along the bottom of the
    // video while they don't.
    var controlsVisible by remember { mutableStateOf(false) }
    /** Where ⚙ in the controls is (window coordinates) while its menu is open; null when it's closed. */
    var settingsMenu by remember { mutableStateOf<IntRect?>(null) }
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
    // Minimized on a big screen, it's a card you move and resize, and tap to bring back.
    val mini = rememberFloatingMini()
    sheet.expandsByDrag = !floating
    val ready = state as? PlayerUiState.Ready
    val related = ready?.related.orEmpty()
    // The sponsor segments, drawn on the seek bar and the progress lines.
    val barSegments = ready?.segments.orEmpty()
    val barDuration = ready?.durationMs ?: 0L
    // Live streams play at their own pace: no speed for them.
    val canChangeSpeed = barDuration > 0
    val hasSettings = vm.qualities.isNotEmpty() || canChangeSpeed
    // ⚙ moves, or goes, with the video: its menu closes.
    LaunchedEffect(atTop, fullscreen, vm.currentUrl) { settingsMenu = null }
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
                        onRetrySegments = vm::retrySegments,
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
            related.forEachIndexed { i, item ->
                VideoRow(item, onClick = { vm.openLink(item.url, OpenSource.UpNext, position = i) }, trailing = { VideoMenu(item) })
            }
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
    // On big screens the video has the card's rounded corners full size too (not in fullscreen).
    val videoCard: GraphicsLayerScope.() -> Unit = {
        if (floating && !fullscreen) {
            shape = RoundedCornerShape(FloatingMiniCorner.toPx())
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
                Box(Modifier.graphicsLayer(videoCard).sheetDrag(sheet, onTap = { (!atTop).also { if (it) vm.expand() } })) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setControllerVisibilityListener(
                                    PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE },
                                )
                                tag = SeekBarSegments(this)
                                // ⚙ opens the app's menu (quality and speed), not Media3's (speed),
                                // above the pill it's in and lined up with its end.
                                settingsButton()?.setOnClickListener { button ->
                                    settingsMenu = ((button.parent as? View) ?: button).boundsInWindow()
                                }
                            }
                        },
                        update = { view ->
                            (view.tag as? SeekBarSegments)?.show(barSegments, barDuration)
                            // Only the full-size player has controls; minimized, a tap opens it.
                            view.useController = atTop
                            if (!atTop) view.hideController()
                            view.setFullscreenButtonState(fullscreen)
                            view.setFullscreenButtonClickListener(
                                if (showFullscreenButton) PlayerView.FullscreenButtonClickListener { vm.toggleFullscreen() }
                                else null,
                            )
                            // ⚙ only when there's something in its menu.
                            view.settingsButton()?.visibility = if (hasSettings) View.VISIBLE else View.GONE
                        },
                        // if the view is ever rebuilt, let go of the player so the new one gets the picture
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                    settingsMenu?.let { anchor ->
                        SettingsMenu(
                            anchor = anchor,
                            qualities = vm.qualities,
                            chosenQuality = vm.chosenQuality,
                            playingQuality = vm.playingQuality,
                            onPickQuality = vm::setQuality,
                            speed = vm.playbackSpeed.takeIf { canChangeSpeed },
                            onPickSpeed = vm::setSpeed,
                            onDismiss = { settingsMenu = null },
                        )
                    }
                    // How far into the video you are, while the controls (and their seek bar)
                    // are hidden: a thin line along its bottom, like YouTube's. Not in
                    // fullscreen, where nothing covers the picture.
                    if (atTop && !fullscreen && !controlsVisible && settingsMenu == null && ready != null) {
                        ProgressLine(
                            player,
                            segments = barSegments,
                            showBuffered = true,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(ProgressLineHeight),
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
                        modifier = Modifier.graphicsLayer { alpha = fadeIn() }.sheetDrag(sheet).moveAndResize(mini, sheet),
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
                    Box(Modifier.graphicsLayer(videoCard)) {
                        ProgressLine(
                            player,
                            segments = barSegments,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).graphicsLayer { alpha = fadeIn() },
                        )
                    }
                } else if (showMini && !fullscreen) {
                    ProgressLine(player, segments = barSegments, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fadeIn() })
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
            // The video card where it was left (FloatingMini): anywhere in the safe area above
            // the tabs' navigation bar (the screen's MiniPlayerSlot marks its top) or the
            // system bar. Until it's moved, in the bottom corner, about a third as wide as that.
            val margin = FloatingMiniMargin.toPx()
            val navTop = dock.top ?: (h - navigationBars.getBottom(this))
            val room = Rect(left + margin, top + margin, w - right - margin, navTop - margin)
            val usualWidth = (innerW * 0.3f).coerceIn(FloatingMiniMinWidth.toPx(), FloatingMiniMaxWidth.toPx())
            val card = mini.place(room, usualWidth, density)
            val cardW = card.width.roundToInt().coerceAtLeast(1)
            val cardH = card.height.roundToInt().coerceAtLeast(1)
            val cardLeft = card.left.roundToInt()
            val cardTop = card.top.roundToInt()
            // How far dragging the full-size player down goes to minimize it: at least a third
            // of the window, even with the card near the top.
            sheet.range = cardTop.coerceAtLeast(h / 3).coerceAtLeast(1).toFloat()

            // Full size, the video has room around it, and rounded corners (videoCard), like
            // YouTube's on big screens: its left edge lines up with the text under it.
            val inset = FullVideoInset.roundToPx()
            val fullW = (colW - inset).coerceAtLeast(1)
            val fullH = fullW * 9 / 16

            // Everything moves from its full-size spot to the card's.
            val videoX = lerp(left + inset, cardLeft, p)
            val videoY = lerp(top + inset, cardTop, p)
            val videoW = lerp(fullW, cardW, p)
            val videoH = lerp(fullH, cardH, p)
            val bodyH = (h - top - bottom).coerceAtLeast(0)

            val sheetBackground = backgrounds.map { it.measure(Constraints.fixed(lerp(w, cardW, p), lerp(h, cardH, p))) }
            val video = videos.map { it.measure(Constraints.fixed(videoW, videoH)) }
            val detail = measurables[2].map { it.measure(Constraints.fixed(colW, (bodyH - inset - fullH).coerceAtLeast(0))) }
            val side = measurables[3].map { it.measure(Constraints.fixed(sideW, bodyH)) }
            val mini = measurables[4].map { it.measure(Constraints.fixed(videoW, videoH)) }
            val line = measurables[5].map { it.measure(Constraints.fixed(videoW, videoH)) }

            return@Layout layout(w, h) {
                sheetBackground.forEach { it.place(lerp(0, cardLeft, p), lerp(0, cardTop, p)) }
                video.forEach { it.place(videoX, videoY) }
                detail.forEach { it.place(lerp(left, cardLeft, p), videoY + videoH) }
                side.forEach { it.place(left + colW + (sideW * p).roundToInt(), lerp(top, cardTop, p)) } // slides out to the side
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
 * ⚙ in the controls: Media3's own button, in the pill at the bottom right
 * (layout/exo_player_control_view.xml). It opens the app's menu ([SettingsMenu]) instead of
 * Media3's, and Media3 never shows or hides it (only enables or dims it), so the app can.
 */
@SuppressLint("PrivateResource") // the id is part of the controls' layout, which apps may replace
@OptIn(UnstableApi::class)
private fun PlayerView.settingsButton(): View? = findViewById(androidx.media3.ui.R.id.exo_settings)

/** Where a view is in its window, for a popup next to it. */
private fun View.boundsInWindow(): IntRect {
    val at = IntArray(2).also(::getLocationInWindow)
    return IntRect(at[0], at[1], at[0] + width, at[1] + height)
}

/** The player's menu: over the video, so always dark, like YouTube's. */
private val PlayerMenuColor = Color.Black.copy(alpha = 0.88f)
private val PlayerMenuWidth = 240.dp
private val PlayerMenuText = Color.White
private val PlayerMenuDetail = Color.White.copy(alpha = 0.7f)

/** Its rows: smaller than Material's menu items (which are at least 48 dp tall), so it covers less of the video. */
private val PlayerMenuRowHeight = 40.dp
private val PlayerMenuIconSize = 20.dp

/** What the ⚙ menu shows: the two settings, or the choices for one of them. */
private enum class SettingsPage { Main, Quality, Speed }

/** The speeds to choose from, like YouTube's. */
private val Speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** "Normal", or "1.5×". */
internal fun speedLabel(speed: Float): String = when {
    speed == 1f -> "Normal"
    speed % 1f == 0f -> "${speed.toInt()}×"
    else -> "$speed×"
}

/**
 * The ⚙ menu, like YouTube's: above ⚙ ([anchor], where it is in the window), ending where it
 * ends. It has the quality (Auto, the best up to the Settings max, showing what's playing; or
 * one of the video's qualities, for this video) and, unless [speed] is null (a live stream),
 * the playback speed (for this video and the next). Each opens its choices; picking one closes
 * the menu, as does tapping outside it, or Back.
 */
@Composable
private fun SettingsMenu(
    anchor: IntRect,
    qualities: List<Int>,
    chosenQuality: Int?,
    playingQuality: Int?,
    onPickQuality: (Int?) -> Unit,
    speed: Float?,
    onPickSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val qualityLabel = when {
        chosenQuality != null -> "${chosenQuality}p"
        playingQuality != null -> "Auto (${playingQuality}p)"
        else -> "Auto"
    }
    Popup(
        popupPositionProvider = remember(anchor, gap) { AboveAnchor(anchor, gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PlayerMenuColor,
            contentColor = PlayerMenuText,
            modifier = Modifier.width(PlayerMenuWidth),
        ) {
            // Scrolls when it's taller than the window (the speeds, on a phone held sideways).
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                when (page) {
                    SettingsPage.Main -> {
                        if (qualities.isNotEmpty()) {
                            SettingRow(TuneIcon, "Quality", qualityLabel) { page = SettingsPage.Quality }
                        }
                        if (speed != null) {
                            SettingRow(SpeedIcon, "Playback speed", speedLabel(speed)) { page = SettingsPage.Speed }
                        }
                    }
                    SettingsPage.Quality -> {
                        ChoicesHeader("Quality") { page = SettingsPage.Main }
                        (listOf<Int?>(null) + qualities).forEach { q ->
                            Choice(
                                label = when {
                                    q != null -> "${q}p"
                                    chosenQuality == null && playingQuality != null -> "Auto (${playingQuality}p)"
                                    else -> "Auto"
                                },
                                selected = q == chosenQuality,
                            ) {
                                onPickQuality(q)
                                onDismiss()
                            }
                        }
                    }
                    SettingsPage.Speed -> {
                        ChoicesHeader("Playback speed") { page = SettingsPage.Main }
                        Speeds.forEach { s ->
                            Choice(speedLabel(s), selected = s == speed) {
                                onPickSpeed(s)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Places a popup above [anchor] (in window coordinates) with its right edge at the anchor's,
 * [gap] away from it and from the window's edges; below the anchor when there's no room above.
 */
private class AboveAnchor(private val anchor: IntRect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchor.right - popupContentSize.width)
            .coerceAtMost(windowSize.width - popupContentSize.width - gap)
            .coerceAtLeast(gap)
        val above = anchor.top - gap - popupContentSize.height
        val y = if (above >= gap) above
        else (anchor.bottom + gap).coerceAtMost(windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)
        return IntOffset(x, y)
    }
}

/**
 * A row of the ⚙ menu: an icon (or the room for one), [label], then [trailing]. [modifier]
 * says what tapping it does, with its semantics (a button, or a choice that's selected or not).
 */
@Composable
private fun MenuRow(
    icon: ImageVector?,
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    iconDescription: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = PlayerMenuRowHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(PlayerMenuIconSize)) {
            if (icon != null) Icon(icon, iconDescription, tint = PlayerMenuText, modifier = Modifier.matchParentSize())
        }
        Text(
            label,
            style = labelStyle,
            color = PlayerMenuText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        trailing()
    }
}

/** A setting in the ⚙ menu: its icon and name, then what it's set to and ›, which opens its choices. */
@Composable
private fun SettingRow(icon: ImageVector, name: String, value: String, onClick: () -> Unit) {
    MenuRow(icon, name, Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = PlayerMenuDetail,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PlayerMenuDetail,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Above a setting's choices: its name, with ‹ to go back to the settings. */
@Composable
private fun ChoicesHeader(name: String, onBack: () -> Unit) {
    MenuRow(
        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        name,
        Modifier.clickable(onClickLabel = "Back to the settings", role = Role.Button, onClick = onBack),
        labelStyle = MaterialTheme.typography.labelLarge,
    )
    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
}

/** One of a setting's choices, ✓ when it's the one in use. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    MenuRow(
        if (selected) Icons.Default.Check else null,
        label,
        Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
    )
}

/** Material's "tune" icon (sliders), for the quality (the core icon set doesn't have it). */
private val TuneIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Tune") {
        addPath(
            addPathNodes(
                "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
            ),
            fill = SolidColor(Color.Black),
        )
    }
}

/** Material's "speed" icon (a gauge), for the playback speed (the core icon set doesn't have it). */
private val SpeedIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Speed") {
        addPath(
            addPathNodes(
                "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19" +
                    "a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0" +
                    "l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
            ),
            fill = SolidColor(Color.Black),
        )
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
    onRetrySegments: () -> Unit,
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
        state.segmentsFailed -> Column {
            Text("Couldn't reach SponsorBlock, so nothing is skipped in this video.")
            TextButton(onClick = onRetrySegments, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
        }
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
