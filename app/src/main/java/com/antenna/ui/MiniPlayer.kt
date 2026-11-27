package com.antenna.ui

import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import com.antenna.data.MiniPlayerPlace
import com.antenna.data.Prefs
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import com.antenna.sponsorblock.Segment
import com.antenna.player.SEGMENT_COLOR
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Height of the minimized player on phones (its video is this tall, at 16:9). */
internal val MiniPlayerHeight = 64.dp

/**
 * On big screens ([isWideWindow]) the minimized player is a floating video card instead of a
 * bar: in the bottom corner, about a third of the window wide within these bounds, until it's
 * moved or resized ([FloatingMini]).
 */
internal val FloatingMiniMinWidth = 240.dp
internal val FloatingMiniMaxWidth = 360.dp
internal val FloatingMiniMargin = 12.dp
internal val FloatingMiniCorner = 12.dp
internal val FloatingMiniElevation = 8.dp

/** The smallest the floating mini player can be made. */
internal val FloatingMiniSmallest = 160.dp

/** How close to one of the floating mini player's bottom corners a drag resizes it instead of moving it. */
internal val FloatingMiniGrip = 28.dp

/**
 * Where the floating mini player is, and how big. Drag it to move it anywhere above the tabs'
 * navigation bar; pinch it, or drag one of its bottom corners, to resize it. Its place is kept
 * as fractions of the room it can move in ([MiniPlayerPlace]), and [onSave] keeps it for next
 * time when a move ends.
 */
@Stable
internal class FloatingMini(start: MiniPlayerPlace, private val onSave: (MiniPlayerPlace) -> Unit) {
    private var x by mutableFloatStateOf(start.x.coerceIn(0f, 1f))
    private var y by mutableFloatStateOf(start.y.coerceIn(0f, 1f))

    /** 0 until it's resized. */
    private var widthDp by mutableFloatStateOf(start.widthDp)

    // As the last layout had them: the room it can move in, its usual width, the density.
    private var room = Rect.Zero
    private var usualWidth = 0f
    private var density = 1f

    /**
     * Where it is, in the window's pixels: in [room], and [usualWidth] wide until it's resized.
     * Read while laying out, so moving it lays it out again without recomposing anything.
     */
    fun place(room: Rect, usualWidth: Float, density: Float): Rect {
        this.room = room
        this.usualWidth = usualWidth
        this.density = density
        return card()
    }

    /** Moved by [pan], and scaled by [zoom] around [pivot] (a point on it, from its top left): a drag, or a pinch. */
    fun transform(pan: Offset, zoom: Float = 1f, pivot: Offset = Offset.Zero) {
        val card = card()
        val width = (card.width * zoom).coerceIn(widths())
        val scale = width / card.width
        // The point between the fingers stays under them while it grows or shrinks.
        moveTo(card.left + pivot.x * (1 - scale) + pan.x, card.top + pivot.y * (1 - scale) + pan.y, width, resized = zoom != 1f)
    }

    /**
     * Resized by dragging one of its bottom corners by [drag] ([fromLeft]: the left one), by as
     * much as the drag goes along its diagonal. The opposite corner stays put, unless that would
     * take the card out of its room.
     */
    fun resize(drag: Offset, fromLeft: Boolean) {
        val card = card()
        // How far the corner went outwards along the 16:9 diagonal, in width: (±16, 9)·drag × 16 / (16² + 9²)
        val wider = ((if (fromLeft) -drag.x else drag.x) * 16 + drag.y * 9) * 16 / 337
        val width = (card.width + wider).coerceIn(widths())
        moveTo(if (fromLeft) card.right - width else card.left, card.top, width, resized = true)
    }

    /** A move ended: kept for next time. */
    fun save() = onSave(MiniPlayerPlace(x, y, widthDp))

    /** From the smallest it can be made to as big as its room. */
    private fun widths(): ClosedFloatingPointRange<Float> {
        val most = minOf(room.width, room.height * 16 / 9).coerceAtLeast(1f)
        return (FloatingMiniSmallest.value * density).coerceAtMost(most)..most
    }

    private fun card(): Rect {
        val width = (if (widthDp > 0) widthDp * density else usualWidth).coerceIn(widths())
        val height = width * 9 / 16
        val left = room.left + (room.width - width).coerceAtLeast(0f) * x
        val top = room.top + (room.height - height).coerceAtLeast(0f) * y
        return Rect(left, top, left + width, top + height)
    }

    private fun moveTo(left: Float, top: Float, width: Float, resized: Boolean) {
        val freeX = room.width - width
        val freeY = room.height - width * 9 / 16
        if (freeX > 0) x = ((left - room.left) / freeX).coerceIn(0f, 1f)
        if (freeY > 0) y = ((top - room.top) / freeY).coerceIn(0f, 1f)
        if (resized) widthDp = width / density
    }
}

@Composable
internal fun rememberFloatingMini(): FloatingMini {
    val context = LocalContext.current
    return remember(context) {
        val prefs = Prefs(context)
        FloatingMini(prefs.miniPlayer) { prefs.miniPlayer = it }
    }
}

/**
 * Moving and resizing the floating mini player ([mini]) while it's all the way down ([sheet]):
 * a drag moves it, one from a bottom corner ([FloatingMiniGrip]) resizes it, and a pinch does
 * both. It sees touches before what's inside, and takes them only once they've moved: taps
 * still open the player, and press its buttons.
 */
internal fun Modifier.moveAndResize(mini: FloatingMini, sheet: SheetState): Modifier =
    pointerInput(mini, sheet) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (sheet.progress != 1f) return@awaitEachGesture
            val grip = FloatingMiniGrip.toPx()
            val fromCorner = down.position.y > size.height - grip && (down.position.x < grip || down.position.x > size.width - grip)
            val fromLeft = down.position.x < size.width / 2f
            val slop = viewConfiguration.touchSlop
            var moving = false
            // Until it's moving, what the fingers did adds up, so it then catches up with them.
            var pan = Offset.Zero
            var zoom = 1f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
                pan += event.calculatePan()
                zoom *= event.calculateZoom()
                if (!moving) {
                    moving = pan.getDistance() > slop || abs(1 - zoom) * event.calculateCentroidSize() > slop
                    if (!moving) continue
                }
                if (fromCorner && event.changes.count { it.pressed } == 1) mini.resize(pan, fromLeft)
                else mini.transform(pan, zoom, pivot = event.calculateCentroid())
                pan = Offset.Zero
                zoom = 1f
                event.changes.forEach { if (it.positionChanged()) it.consume() } // not a tap for what's inside
            }
            if (moving) mini.save()
        }
    }

/**
 * Where the minimized player docks. Each screen puts a [MiniPlayerSlot] where it should go
 * (the tabs: just above their navigation bar), which tells PlayerSheet where to draw it and,
 * on phones, keeps the screen's content clear of it.
 */
@Stable
internal class MiniPlayerDock(private val isOpen: () -> Boolean) {
    /** Whether a video is open, so screens should leave room for the mini player. */
    val visible: Boolean get() = isOpen()

    /** The slot's top edge, in the window's coordinates; null until a screen has shown one. */
    var top by mutableStateOf<Int?>(null)
}

internal val LocalMiniPlayerDock = staticCompositionLocalOf { MiniPlayerDock { false } }

/**
 * Room for the mini player, while a video is open. Nothing otherwise, and nothing on big
 * screens either: the floating mini player sits over the content, just above this spot.
 */
@Composable
internal fun MiniPlayerSlot(modifier: Modifier = Modifier) {
    val dock = LocalMiniPlayerDock.current
    if (!dock.visible) return
    Spacer(
        modifier
            .fillMaxWidth()
            .height(if (isWideWindow()) 0.dp else MiniPlayerHeight)
            .onGloballyPositioned { dock.top = it.positionInRoot().y.roundToInt() },
    )
}

/**
 * How far the player is between full size ([progress] 0) and minimized (1), following a
 * finger while it's dragged and animating to one or the other when it's let go.
 */
@Stable
internal class SheetState(private val scope: CoroutineScope, private val flingVelocity: Float) {
    var progress by mutableFloatStateOf(1f)
        private set

    /** How many pixels the sheet moves between the two, so it keeps up with the finger. */
    var range = 1f

    /** Off in fullscreen. */
    var draggable = true

    /** Minimized, it drags up to full size: not the floating mini player, which a drag moves ([moveAndResize]). */
    var expandsByDrag = true

    /** Called when a drag ends, with where the sheet is going: true for full size. */
    var onSettle: (expanded: Boolean) -> Unit = {}

    /** Where the sheet is (or is animating to): 0 or 1. */
    private var target = progress
    private var animation: Job? = null

    /** Where the sheet was when the drag started: the end it's being dragged away from. */
    private var dragFrom = target

    /** Full size only drags down, minimized only up ([expandsByDrag]). */
    fun canDrag(dy: Float): Boolean = when (progress) {
        0f -> dy > 0
        1f -> dy < 0 && expandsByDrag
        else -> true
    }

    fun startDrag() {
        animation?.cancel()
        dragFrom = target
    }

    fun drag(dy: Float) {
        progress = (progress + dy / range).coerceIn(0f, 1f)
    }

    /**
     * A flick goes the way it was flicked. Otherwise it goes to the other end once it's been
     * dragged a third of the way there, and back to where it started if not.
     */
    fun release(velocityY: Float) {
        val other = 1f - dragFrom
        val to = when {
            velocityY > flingVelocity -> 1f
            velocityY < -flingVelocity -> 0f
            abs(progress - dragFrom) > 1f / 3 -> other
            else -> dragFrom
        }
        animateTo(to, velocityY / range)
        onSettle(to == 0f)
    }

    fun animateTo(to: Float, velocity: Float = 0f) {
        if (to == target && animation?.isActive == true) return
        target = to
        animation?.cancel()
        animation = scope.launch {
            animate(progress, to, velocity, spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
internal fun rememberSheetState(): SheetState {
    val scope = rememberCoroutineScope()
    val flingVelocity = with(LocalDensity.current) { 400.dp.toPx() } // per second
    return remember(scope) { SheetState(scope, flingVelocity) }
}

/**
 * Dragging the player up and down. It looks at touches before what's inside (the
 * PlayerView's own controls), and only takes over once the finger has clearly moved up or
 * down; taps and sideways drags (the seek bar) are left alone. [onTap] gets taps first and
 * returns whether it used them.
 */
internal fun Modifier.sheetDrag(sheet: SheetState, onTap: (() -> Boolean)? = null): Modifier =
    pointerInput(sheet) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!sheet.draggable) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var dragging = false
            var moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null) { // the touch was cancelled
                    if (dragging) sheet.release(0f)
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    if (dragging) {
                        velocity.addPosition(change.uptimeMillis, change.position)
                        sheet.drag(change.positionChangeIgnoreConsumed().y)
                        change.consume()
                        sheet.release(velocity.calculateVelocity().y)
                    } else if (!moved && onTap?.invoke() == true) {
                        change.consume()
                    }
                    return@awaitEachGesture
                }
                velocity.addPosition(change.uptimeMillis, change.position)
                if (dragging) {
                    sheet.drag(change.positionChangeIgnoreConsumed().y)
                    change.consume() // the PlayerView inside gets a cancel
                    continue
                }
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                if (abs(dy) > slop && abs(dy) > abs(dx) && sheet.canDrag(dy)) {
                    dragging = true
                    sheet.startDrag()
                    sheet.drag(dy - sign(dy) * slop)
                    change.consume()
                } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
                    return@awaitEachGesture // sideways: not ours
                }
            }
        }
    }

/** The minimized player's title and buttons; tapping anywhere else brings the player back. */
@Composable
internal fun MiniControls(
    title: String,
    subtitle: String,
    showPlayButton: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxSize()
            .testTag(TAG_MINI)
            .clickable(onClickLabel = "Open the player", onClick = onExpand)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                if (showPlayButton) Icons.Default.PlayArrow else PauseIcon,
                contentDescription = if (showPlayButton) "Play" else "Pause",
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close the player")
        }
    }
}

/**
 * The floating mini player's buttons (big screens), over its video: play/pause in the top
 * left corner, close in the top right. Tapping anywhere else brings the player back. A mouse
 * over a bottom corner gets a resize arrow: dragging from there resizes it ([moveAndResize]).
 */
@Composable
internal fun MiniOverlay(
    showPlayButton: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .testTag(TAG_MINI)
            .clickable(onClickLabel = "Open the player", onClick = onExpand)
            // darkens the top of the video a little, so the white buttons show on any picture
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.5f to Color.Transparent)),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            IconButton(onClick = onPlayPause, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(
                    if (showPlayButton) Icons.Default.PlayArrow else PauseIcon,
                    contentDescription = if (showPlayButton) "Play" else "Pause",
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Close, contentDescription = "Close the player")
            }
        }
        Box(Modifier.align(Alignment.BottomStart).size(FloatingMiniGrip).pointerHoverIcon(ResizeFromBottomLeft))
        Box(Modifier.align(Alignment.BottomEnd).size(FloatingMiniGrip).pointerHoverIcon(ResizeFromBottomRight))
    }
}

private val ResizeFromBottomLeft = PointerIcon(android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW)
private val ResizeFromBottomRight = PointerIcon(android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)

/** How thick the progress line along the bottom of the full-size video is. */
internal val ProgressLineHeight = 3.dp

private val LineTrack = Color.Gray.copy(alpha = 0.4f)
private val LineBuffered = Color.White.copy(alpha = 0.45f)
private val LinePlayed = Color.Red
private val LineSegment = Color(SEGMENT_COLOR)

/**
 * A thin line showing how far into the video you are, in red, with the SponsorBlock
 * [segments] in green (as on the seek bar) and, with [showBuffered], how far ahead it has
 * loaded. Along the bottom of the mini player, and of the full-size video while its controls
 * are hidden. While the video plays it moves about a pixel at a time, so it moves smoothly.
 */
@Composable
internal fun ProgressLine(player: Player, segments: List<Segment>, modifier: Modifier = Modifier, showBuffered: Boolean = false) {
    var position by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var widthPx by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val d = player.duration
                duration = if (d == C.TIME_UNSET || d < 0) 0L else d
                position = player.currentPosition
                buffered = player.bufferedPosition
                val perPixel = if (player.isPlaying && duration > 0 && widthPx > 0) {
                    (duration / widthPx / player.playbackParameters.speed).toLong()
                } else {
                    500L // paused: only a seek moves it
                }
                delay(perPixel.coerceIn(16L, 500L))
            }
        }
    }
    // The values are read only while drawing, so following the video doesn't recompose anything.
    Spacer(
        modifier
            .onSizeChanged { widthPx = it.width }
            .drawBehind {
                drawRect(LineTrack)
                if (duration <= 0) return@drawBehind
                fun x(ms: Long) = size.width * (ms.toFloat() / duration).coerceIn(0f, 1f)
                if (showBuffered) drawRect(LineBuffered, size = Size(x(buffered), size.height))
                drawRect(LinePlayed, size = Size(x(position), size.height))
                segments.forEach { s ->
                    val from = x(s.startMs)
                    drawRect(LineSegment, topLeft = Offset(from, 0f), size = Size(x(s.endMs) - from, size.height))
                }
            },
    )
}

/** Material's "pause" icon (the core icon set doesn't have it). */
private val PauseIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Pause") {
        materialPath {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            verticalLineToRelative(14f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineToRelative(-4f)
            close()
        }
    }
}
