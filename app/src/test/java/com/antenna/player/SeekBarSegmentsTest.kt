package com.antenna.player

import com.antenna.sponsorblock.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ad markers that draw SponsorBlock's segments on the seek bar. Here a 1000-second video
 * on a 1000-pixel bar, with markers 4 pixels wide: a pixel is a second, a marker 4 seconds.
 */
class SeekBarSegmentsTest {

    private fun markers(vararg segments: Segment, durationMs: Long = 1_000_000) =
        segmentMarkers(segments.toList(), durationMs, barWidthPx = 1000, markerWidthPx = 4f).toList()

    private fun segment(startS: Int, endS: Int) = Segment("uuid$startS", "sponsor", startS * 1000L, endS * 1000L)

    @Test
    fun `a segment is filled with overlapping markers, kept inside its ends`() {
        val m = markers(segment(100, 120))
        // Centred 2 s (half a marker) in from each end, 2 s apart: the band is 100-120 s exactly.
        assertEquals(102_000L, m.first())
        assertEquals(118_000L, m.last())
        assertTrue(m.zipWithNext().all { (a, b) -> b - a in 1..2_000 })
    }

    @Test
    fun `a segment narrower than a marker gets one, in its middle`() {
        assertEquals(listOf(101_500L), markers(segment(100, 103)))
    }

    @Test
    fun `each segment gets its own markers`() {
        val m = markers(segment(10, 20), segment(500, 510))
        assertTrue(m.all { it in 10_000..20_000 || it in 500_000..510_000 })
        assertTrue(m.any { it < 20_000 } && m.any { it > 500_000 })
    }

    @Test
    fun `segments past the end are cut there, and none show without a length`() {
        assertTrue(markers(segment(990, 1100)).all { it in 990_000..1_000_000 })
        assertEquals(emptyList<Long>(), markers(segment(1100, 1200)))
        assertEquals(emptyList<Long>(), markers(segment(10, 20), durationMs = 0)) // live, or not known yet
        assertEquals(0, segmentMarkers(listOf(segment(10, 20)), 1_000_000, barWidthPx = 0, markerWidthPx = 4f).size) // not laid out yet
    }
}
