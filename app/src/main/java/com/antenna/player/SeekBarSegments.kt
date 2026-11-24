package com.antenna.player

import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.antenna.sponsorblock.Segment

/** SponsorBlock's colour for sponsors: the segments on the seek bar and the progress lines. */
internal const val SEGMENT_COLOR = 0xFF00D400.toInt()

/**
 * Draws a video's SponsorBlock segments on the player's seek bar, as green bands.
 *
 * Media3's seek bar (DefaultTimeBar, inside [view]'s controls) has no way to draw a range:
 * only ad markers, short marks of one width at given times. So each segment is filled with
 * markers close enough to merge into a band ([segmentMarkers]). How many that takes depends
 * on the bar's width, so they're placed again when that changes (turning the phone,
 * fullscreen). Markers are drawn under the playhead, and the bar looks after them from
 * there: they show and hide with the controls, at the right spot whatever its layout.
 */
@OptIn(UnstableApi::class)
internal class SeekBarSegments(private val view: PlayerView) {

    private val timeBar: DefaultTimeBar? = findTimeBar(view)?.apply {
        setAdMarkerColor(SEGMENT_COLOR)
        setPlayedAdMarkerColor(SEGMENT_COLOR)
        addOnLayoutChangeListener { bar, left, _, right, _, oldLeft, _, oldRight, _ ->
            // After the layout pass, not in the middle of it.
            if (right - left != oldRight - oldLeft) bar.post { place() }
        }
    }
    private var segments: List<Segment> = emptyList()
    private var durationMs = 0L

    /** Shows [segments] of a video [durationMs] long (0: unknown, or live: none shown). */
    fun show(segments: List<Segment>, durationMs: Long) {
        if (segments == this.segments && durationMs == this.durationMs) return
        this.segments = segments
        this.durationMs = durationMs
        place()
    }

    private fun place() {
        val bar = timeBar ?: return
        val markers = segmentMarkers(
            segments,
            durationMs,
            barWidthPx = bar.width - bar.paddingLeft - bar.paddingRight,
            markerWidthPx = DefaultTimeBar.DEFAULT_AD_MARKER_WIDTH_DP * bar.resources.displayMetrics.density,
        )
        view.setExtraAdGroupMarkers(markers, BooleanArray(markers.size))
    }

    private companion object {
        fun findTimeBar(v: View): DefaultTimeBar? = when (v) {
            is DefaultTimeBar -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findTimeBar(v.getChildAt(it)) }
            else -> null
        }
    }
}

/**
 * The times of the ad markers that draw [segments] as bands on a seek bar [barWidthPx] wide,
 * for a video [durationMs] long. Each marker is [markerWidthPx] wide, centred on its time.
 * They're placed half a marker apart, so they overlap, and the first and last half a marker
 * in from the segment's ends, so the band covers the segment and no more. A segment
 * narrower than a marker gets one, in its middle.
 */
internal fun segmentMarkers(segments: List<Segment>, durationMs: Long, barWidthPx: Int, markerWidthPx: Float): LongArray {
    if (durationMs <= 0 || barWidthPx <= 0 || markerWidthPx <= 0f) return LongArray(0)
    val markerMs = durationMs * markerWidthPx / barWidthPx // a marker's width, in the video's time
    val half = (markerMs / 2).toLong()
    val step = half.coerceAtLeast(1)
    val markers = ArrayList<Long>()
    segments.forEach { s ->
        val start = s.startMs.coerceIn(0, durationMs)
        val end = s.endMs.coerceIn(0, durationMs)
        if (end <= start) return@forEach
        if (end - start <= markerMs) {
            markers += (start + end) / 2
            return@forEach
        }
        var t = start + half
        while (t < end - half) {
            markers += t
            t += step
        }
        markers += end - half
    }
    return markers.toLongArray()
}
