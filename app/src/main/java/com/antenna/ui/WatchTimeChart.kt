package com.antenna.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.antenna.data.formatWatchTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The Analytics tab's watch time chart: watch time over time, which pinching zooms in from
// weeks to days to the hours of a day, and dragging scrolls back through the history. Times
// are hour indexes (WatchStats.hourIndex: the epoch day × 24 + the hour), as doubles while
// zooming and scrolling.

/** How much time one bar is. */
internal enum class Step(val hours: Int) { Hour(1), Day(24), Week(7 * 24) }

/** The fewest hours the chart can show, zoomed all the way in. */
internal const val MIN_SPAN_HOURS = 12.0

/** The most it can show at once, zoomed all the way out (the rest is a drag away). */
internal const val MAX_SPAN_HOURS = 365 * 24.0

/** Bars are hours only when it shows this many hours or fewer (3 days): wider than that, days read better. */
internal const val HOURS_UP_TO = 72.0

/** Bars are at least this wide, gaps included: zoomed out further, they become days, then weeks. */
private val MinSlot = 5.dp

/**
 * The bars for [spanHours] across [widthPx] (at [minSlotPx]): hours when zoomed in to
 * [HOURS_UP_TO] or fewer and they're wide enough, then days while they're wide enough, then
 * weeks.
 */
internal fun stepFor(spanHours: Double, widthPx: Float, minSlotPx: Float): Step = when {
    spanHours <= HOURS_UP_TO && widthPx / spanHours >= minSlotPx -> Step.Hour
    widthPx * 24 / spanHours >= minSlotPx -> Step.Day
    else -> Step.Week
}

/** The epoch day of the Monday of [day]'s week. */
internal fun mondayOf(day: Long): Long = day - Math.floorMod(day - 4, 7L) // day 4 (5 Jan 1970) was a Monday

/** Where the bar that [hour] falls in starts, in [step]s. */
internal fun barStart(hour: Long, step: Step): Long = when (step) {
    Step.Hour -> hour
    Step.Day -> Math.floorDiv(hour, 24L) * 24
    Step.Week -> mondayOf(Math.floorDiv(hour, 24L)) * 24
}

/**
 * The top of the chart's scale: the smallest round amount at or above [maxMs], whose half is
 * round too (the line halfway up).
 */
internal fun scaleTop(maxMs: Long): Long {
    val minute = 60_000L
    val hour = 60 * minute
    val tops = listOf(2, 4, 10, 20, 30, 40).map { it * minute } +
        listOf(1, 2, 4, 6, 8, 10, 12, 16, 20, 24, 30, 40, 50, 60, 80, 100, 120, 160, 200).map { it * hour }
    return tops.firstOrNull { it >= maxMs } ?: run {
        var top = tops.last()
        while (top < maxMs) top *= 2
        top
    }
}

/**
 * Watch time by hour ([hourly], by hour index), and by day and by week (from Monday) added up
 * from it, for the bars at each [Step].
 */
@Stable
internal class TimeSeries(hourly: Map<Long, Long>) {
    private val byHour = hourly
    private val byDay = HashMap<Long, Long>()
    private val byWeek = HashMap<Long, Long>()

    init {
        hourly.forEach { (h, ms) ->
            val day = Math.floorDiv(h, 24L)
            byDay.merge(day, ms, Long::plus)
            byWeek.merge(mondayOf(day), ms, Long::plus)
        }
    }

    /** The first day with any watching, or null for none. */
    val firstDay: Long? = byDay.keys.minOrNull()

    /** Watch time in the bar starting at hour index [start]. */
    fun value(step: Step, start: Long): Long = when (step) {
        Step.Hour -> byHour[start]
        Step.Day -> byDay[Math.floorDiv(start, 24L)]
        Step.Week -> byWeek[Math.floorDiv(start, 24L)]
    } ?: 0L

    /** Watch time per day, by epoch day. */
    val days: Map<Long, Long> get() = byDay
}

/**
 * What the chart shows: from [start] for [span] hours, between [first] and [last] (the start of
 * the first day it can show, and the end of today). Pinching zooms it, dragging moves it.
 */
@Stable
internal class TimeWindow(start: Double, span: Double) {
    var start by mutableDoubleStateOf(start)
        private set
    var span by mutableDoubleStateOf(span)
        private set
    val end: Double get() = start + span

    var first = start
        private set
    var last = start + span
        private set

    private val maxSpan get() = min(MAX_SPAN_HOURS, last - first).coerceAtLeast(MIN_SPAN_HOURS)

    /** It can show from [first] to [last]; it's moved inside if it's outside. */
    fun setBounds(first: Double, last: Double) {
        this.first = first
        this.last = max(last, first + MIN_SPAN_HOURS)
        show(start, span)
    }

    /** Shows [span] hours from [start], as far as the bounds allow. */
    fun show(start: Double, span: Double) {
        val s = span.coerceIn(MIN_SPAN_HOURS, maxSpan)
        this.span = s
        this.start = start.coerceIn(first, last - s)
    }

    /** Zooms in by [factor] (below 1: out), keeping the time at [focus] (0…1 across) where it is. */
    fun zoom(factor: Float, focus: Float) {
        val at = start + span * focus
        val s = (span / factor).coerceIn(MIN_SPAN_HOURS, maxSpan)
        show(at - s * focus, s)
    }

    /** Moves it by [hours] (later for more); false if it was already as far as it goes. */
    fun pan(hours: Double): Boolean {
        val before = start
        show(start + hours, span)
        return start != before
    }

    /** Whether it shows the last [span] hours, up to [last]: as it does at first. */
    fun showsLast(span: Double): Boolean = abs(this.span - span) < 0.5 && abs(end - last) < 0.5

    companion object {
        /** Kept across rotation, and while the tab is in the background: where it was, and how far zoomed. */
        val Saver = Saver<TimeWindow, List<Double>>(
            save = { listOf(it.start, it.span) },
            restore = { TimeWindow(it[0], it[1]) },
        )
    }
}

/** Animates [window] to show [span] hours from [start] (the zoom buttons for TalkBack, a tapped week). */
internal suspend fun TimeWindow.animateTo(start: Double, span: Double) {
    val fromStart = this.start
    val fromSpan = this.span
    animate(0f, 1f) { t, _ -> show(fromStart + (start - fromStart) * t, fromSpan + (span - fromSpan) * t) }
}

/** A label along the bottom: [text] centred under the hour index [at] (a bar's middle). */
internal data class Tick(val at: Double, val text: String)

private val dayFormat = DateTimeFormatter.ofPattern("MMM d")
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE d")
private val monthFormat = DateTimeFormatter.ofPattern("MMM")
private val yearFormat = DateTimeFormatter.ofPattern("yyyy")

/**
 * The labels under bars of [step] from [start] to [end] (hour indexes), at least [minGap] hours
 * apart: hours of the day ("6:00", with the date at midnight), days ("Mon 24" when every day
 * has one, else "Nov 24"), or the first of the months (with the year for January). Only those
 * whose spot is in view.
 */
internal fun ticks(step: Step, start: Double, end: Double, minGap: Double): List<Tick> =
    allTicks(step, start, end, minGap).filter { it.at in start..end }

private fun allTicks(step: Step, start: Double, end: Double, minGap: Double): List<Tick> {
    val from = floor(start).toLong()
    val to = end.toLong() + 1
    fun date(day: Long) = LocalDate.ofEpochDay(day)
    return when {
        step == Step.Hour -> {
            val every = listOf(1, 2, 3, 6, 12, 24).firstOrNull { it >= minGap } ?: 24
            (from..to).filter { Math.floorMod(it, every.toLong()) == 0L }.map { h ->
                val hourOfDay = Math.floorMod(h, 24L).toInt()
                val text = if (hourOfDay == 0) date(Math.floorDiv(h, 24L)).format(dayFormat) else "$hourOfDay:00"
                Tick(h + 0.5, text)
            }
        }
        step == Step.Day && minGap <= 14 * 24 -> {
            val every = listOf(1, 2, 7, 14).first { it * 24 >= minGap }
            (Math.floorDiv(from, 24L)..Math.floorDiv(to, 24L))
                .filter { d ->
                    when (every) {
                        7 -> mondayOf(d) == d
                        14 -> mondayOf(d) == d && Math.floorMod(Math.floorDiv(d - 4, 7L), 2L) == 0L
                        else -> Math.floorMod(d, every.toLong()) == 0L
                    }
                }
                .map { d -> Tick(d * 24 + 12.0, date(d).format(if (every == 1) weekdayFormat else dayFormat)) }
        }
        else -> {
            // The first of every month, or of every few, at least minGap apart.
            val every = listOf(1, 2, 3, 6, 12).firstOrNull { it * 30 * 24 >= minGap } ?: 12
            var month = date(Math.floorDiv(from, 24L)).withDayOfMonth(1)
            val last = date(Math.floorDiv(to, 24L))
            val out = ArrayList<Tick>()
            while (!month.isAfter(last)) {
                if ((month.monthValue - 1) % every == 0) {
                    val text = if (month.monthValue == 1) month.format(yearFormat) else month.format(monthFormat)
                    out += Tick(month.toEpochDay() * 24.0 + 12, text)
                }
                month = month.plusMonths(1)
            }
            out
        }
    }
}

/** What the chart has picked: a day (epoch day) and an hour of the day, either, both or neither. */
internal data class TimePick(val day: Long?, val hour: Int?) {
    val any get() = day != null || hour != null

    /** Whether the bar of [step] starting at hour index [start] is one of what's picked. */
    fun includes(step: Step, start: Long): Boolean {
        val d = Math.floorDiv(start, 24L)
        return when (step) {
            Step.Hour -> (day == null || d == day) && (hour == null || Math.floorMod(start, 24L).toInt() == hour)
            Step.Day -> day == null || d == day
            Step.Week -> day == null || day in d until d + 7
        }
    }
}

/**
 * The chart: bars of watch time in [series] for what [window] shows, a day or an hour each, or a
 * week when zoomed far out, with what's picked ([picked]) standing out. The scale fits the
 * tallest bar in view, with its top labelled. Pinch to zoom, drag sideways to scroll (with a
 * fling), and tap a bar: [onTap] gets its [Step] and where it starts. Dragging up or down
 * scrolls the page, as usual. TalkBack can zoom and scroll with the chart's actions.
 */
@Composable
internal fun WatchTimeChart(
    series: TimeSeries,
    window: TimeWindow,
    picked: TimePick,
    onTap: (Step, Long) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val bar = colors.primary
    val dim = bar.copy(alpha = 0.35f)
    val grid = colors.outlineVariant
    val ink = colors.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val tap by rememberUpdatedState(onTap)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction("Zoom in") { scope.launch { window.animateTo(window.start + window.span / 4, window.span / 2) }; true },
                    CustomAccessibilityAction("Zoom out") { scope.launch { window.animateTo(window.start - window.span / 2, window.span * 2) }; true },
                    CustomAccessibilityAction("Earlier") { scope.launch { window.animateTo(window.start - window.span / 2, window.span) }; true },
                    CustomAccessibilityAction("Later") { scope.launch { window.animateTo(window.start + window.span / 2, window.span) }; true },
                )
            }
            .pointerInput(window) {
                var fling: Job? = null // a drag's fling, stopped by the next touch
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()
                    val gutter = AxisWidth.toPx()
                    val width = (size.width - gutter).coerceAtLeast(1f)
                    val slop = viewConfiguration.touchSlop
                    val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var panning = false
                    var zooming = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) {
                            zooming = true
                            val focus = ((event.calculateCentroid(useCurrent = true).x - gutter) / width).coerceIn(0f, 1f)
                            window.zoom(event.calculateZoom(), focus)
                            window.pan(-event.calculatePan().x / width * window.span)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = pressed.first()
                        if (zooming) { // a finger still down after pinching: nothing more
                            change.consume()
                            continue
                        }
                        if (!panning) {
                            val moved = change.position - down.position
                            if (abs(moved.x) > slop && abs(moved.x) > abs(moved.y)) {
                                panning = true
                            } else if (abs(moved.y) > slop) {
                                return@awaitEachGesture // up or down: the page scrolls
                            }
                        }
                        if (panning) {
                            window.pan(-(change.position.x - change.previousPosition.x) / width * window.span)
                            velocity.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                    }
                    when {
                        panning -> {
                            val perPx = window.span / width
                            fling = scope.launch {
                                var last = 0f
                                AnimationState(0f, velocity.calculateVelocity().x).animateDecay(decay) {
                                    val moved = window.pan(-(value - last) * perPx)
                                    last = value
                                    if (!moved) cancelAnimation()
                                }
                            }
                        }
                        !zooming -> {
                            val step = stepFor(window.span, width, MinSlot.toPx())
                            val at = window.start + ((down.position.x - gutter) / width).coerceIn(0f, 1f) * window.span
                            tap(step, barStart(floor(at).toLong(), step))
                        }
                    }
                }
            },
    ) {
        val labelBand = LabelBand.toPx()
        val top = TopBand.toPx()
        val plotLeft = AxisWidth.toPx()
        val plotWidth = size.width - plotLeft
        val plotBottom = size.height - labelBand
        val plotHeight = plotBottom - top
        val step = stepFor(window.span, plotWidth, MinSlot.toPx())
        val pxPerHour = plotWidth / window.span.toFloat()
        val slot = pxPerHour * step.hours
        val gap = min(2.dp.toPx(), slot / 3)
        // Narrow slots: the whole slot but a gap. Wide ones (a few days): about two thirds, so few bars don't look like blocks.
        val barWidth = min(slot - gap, max(24.dp.toPx(), slot * 0.62f)).coerceIn(1f, 56.dp.toPx())
        val radius = min(4.dp.toPx(), barWidth / 2)
        fun x(hour: Double) = plotLeft + ((hour - window.start) * pxPerHour).toFloat()

        // The bars in view (and the ones cut off at the edges).
        val bars = ArrayList<Long>()
        var b = barStart(floor(window.start).toLong(), step)
        while (b < window.end) {
            bars += b
            b = barStart(b + step.hours, step) // weeks: always the next Monday
        }
        val most = bars.maxOfOrNull { series.value(step, it) } ?: 0L
        val scale = scaleTop(most.coerceAtLeast(1))

        // The scale: hairlines at the top and halfway up, labelled in the gutter on the left, and 0 at the bottom.
        listOf(top to scale, top + plotHeight / 2 to scale / 2, plotBottom to 0L).forEach { (y, ms) ->
            if (ms > 0) drawLine(grid.copy(alpha = 0.5f), Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val label = measurer.measure(formatScale(ms), labelStyle)
            drawText(label, ink, Offset(plotLeft - label.size.width - 6.dp.toPx(), y - label.size.height / 2))
        }

        // Rounded at the top only: the round bottom is drawn below the baseline, and cut off.
        clipRect(plotLeft, 0f, size.width, plotBottom) {
            bars.forEach { start ->
                val value = series.value(step, start)
                if (value <= 0) return@forEach
                val h = (plotHeight * value / scale).coerceAtLeast(2.dp.toPx())
                val left = x(start.toDouble()) + (slot - barWidth) / 2
                drawRoundRect(
                    if (!picked.any || picked.includes(step, start)) bar else dim,
                    topLeft = Offset(left, plotBottom - h),
                    size = Size(barWidth, h + radius),
                    cornerRadius = CornerRadius(radius),
                )
                // Its value above it, when there's room (a few days, or hours zoomed in).
                if (slot < MinLabelSlot.toPx()) return@forEach
                val text = measurer.measure(formatWatchTime(value), labelStyle)
                if (text.size.width <= slot - 4.dp.toPx()) {
                    val labelLeft = x(start.toDouble()) + (slot - text.size.width) / 2
                    if (labelLeft >= plotLeft && labelLeft + text.size.width <= size.width) {
                        drawText(text, ink, Offset(labelLeft, (plotBottom - h - text.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                    }
                }
            }
        }
        drawLine(grid, Offset(plotLeft, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1.dp.toPx())

        // The labels along the bottom, as many as fit: never cut off at the edges.
        val minGap = LabelGap.toPx() / pxPerHour
        ticks(step, window.start, window.end, minGap.toDouble()).forEach { tick ->
            val text = measurer.measure(tick.text, labelStyle)
            val left = x(tick.at) - text.size.width / 2
            if (left >= plotLeft && left + text.size.width <= size.width) {
                drawText(text, ink, Offset(left, plotBottom + 4.dp.toPx()))
            }
        }
    }
}

/** "2 h", "30 min", "0": a mark on the scale (the scale's round amounts, so its half is round too). */
private fun formatScale(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0 -> "0"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60}"
    }
}

/** The plot, the room above it for the tallest bar's value, below it for the dates, and on its left for the scale. */
private val ChartHeight = 200.dp
private val TopBand = 20.dp
private val LabelBand = 20.dp
private val AxisWidth = 44.dp

/** Bars narrower than this have no room for their value above them (the shortest, "5 min", is about this wide). */
private val MinLabelSlot = 28.dp

/** Labels along the bottom are at least this far apart (centre to centre). */
private val LabelGap = 56.dp
