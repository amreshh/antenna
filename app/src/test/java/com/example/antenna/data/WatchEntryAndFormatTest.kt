package com.example.antenna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a video counts as watched, and the durations shown on thumbnails. */
class WatchEntryAndFormatTest {

    private fun entry(positionMs: Long, durationMs: Long) = WatchEntry(
        video = TestData.video(1),
        positionMs = positionMs,
        durationMs = durationMs,
        lastWatchedAt = TestData.NOW,
    )

    @Test
    fun `watched at 90 percent`() {
        assertTrue(entry(540_000, 600_000).isWatched)
        assertFalse(entry(530_000, 600_000).isWatched)
    }

    @Test
    fun `watched in the last 30 seconds once past halfway (the end screen)`() {
        assertTrue(entry(175_000, 200_000).isWatched) // 87.5%, 25 s left
        assertFalse(entry(30_000, 200_000).isWatched) // 30 s left of the beginning? no: not past halfway
    }

    @Test
    fun `a short video isn't watched just by opening it`() {
        // Under 30 seconds long: the "last 30 seconds" rule alone would count it at once.
        assertFalse(entry(1_000, 20_000).isWatched)
        assertTrue(entry(19_000, 20_000).isWatched)
    }

    @Test
    fun `unknown duration is never watched, and the fraction stays in range`() {
        assertFalse(entry(10_000, 0).isWatched)
        assertEquals(0f, entry(10_000, 0).fraction)
        assertEquals(1f, entry(700_000, 600_000).fraction)
    }

    @Test
    fun `durations read like YouTube's`() {
        assertEquals("", formatDuration(0))
        assertEquals("0:07", formatDuration(7))
        assertEquals("4:05", formatDuration(245))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("2:03:04", formatDuration(7384))
    }
}
