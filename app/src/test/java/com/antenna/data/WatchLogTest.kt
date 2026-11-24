package com.antenna.data

import com.antenna.data.TestData.DAY_MS
import com.antenna.data.TestData.NOW
import com.antenna.data.TestData.id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/** The watch log's stretches of watching, and the sittings they make (WatchLog.kt). */
class WatchLogTest {

    private val utc = ZoneOffset.UTC
    private val minute = 60_000L

    private fun span(n: Int, at: Long, from: Long = 0, minutes: Long = 10, source: OpenSource? = OpenSource.ForYou) =
        WatchSpan(id(n), "channel", at, from, from + minutes * minute, source)

    @Test
    fun `a stretch reads back as it was written, and a line cut short is left out`() {
        val s = span(1, NOW, from = 5_000, source = OpenSource.UpNext)
        assertEquals(s, spanFromJson(s.toJson()))
        val unknown = span(2, NOW, source = null)
        assertEquals(unknown, spanFromJson(unknown.toJson()))
        assertNull(spanFromJson(s.toJson().dropLast(10)))
        assertNull(spanFromJson("not json"))
    }

    @Test
    fun `stretches less than half an hour apart are one sitting`() {
        val start = NOW - 3 * 60 * minute
        val spans = listOf(
            span(1, start, minutes = 10),
            span(2, start + 20 * minute, minutes = 10), // 10 minutes after the first ended
            span(3, start + 100 * minute, minutes = 5), // over an hour later
        )
        val s = sittingStats(spans, NOW, utc)!!
        assertEquals(2, s.sittings)
        assertEquals(25 * minute, s.playedMs)
        assertEquals(3, s.videos) // two in the first, one in the second
        assertEquals(start, s.since)
    }

    @Test
    fun `days in a row, the most and up to today`() {
        // Watched 10, 9 and 8 days ago, 5 days ago, and yesterday and today.
        val spans = listOf(10, 9, 8, 5, 1, 0).map { span(it, NOW - it * DAY_MS) }
        val s = sittingStats(spans, NOW, utc)!!
        assertEquals(3, s.longestStreak)
        assertEquals(2, s.currentStreak)
        // Nothing today or yesterday: no run going on.
        assertEquals(0, sittingStats(listOf(span(1, NOW - 3 * DAY_MS)), NOW, utc)!!.currentStreak)
    }

    @Test
    fun `playing part of a video again makes it rewatched, going a few seconds back doesn't`() {
        val again = listOf(
            span(1, NOW - 60 * minute, from = 0, minutes = 10),
            span(1, NOW - 40 * minute, from = 5 * minute, minutes = 5), // its second half, again
        )
        val nudge = listOf(
            WatchSpan(id(2), "channel", NOW - 20 * minute, 0, 300_000, null),
            WatchSpan(id(2), "channel", NOW - 14 * minute, 290_000, 600_000, null), // 10 s back
        )
        assertEquals(1, sittingStats(again + nudge, NOW, utc)!!.rewatched)
    }

    @Test
    fun `older than 30 days counts for days in a row, not for sittings`() {
        val s = sittingStats(listOf(span(1, NOW - 40 * DAY_MS), span(2, NOW - 39 * DAY_MS)), NOW, utc)!!
        assertEquals(0, s.sittings)
        assertEquals(0L, s.playedMs)
        assertEquals(2, s.longestStreak)
    }

    @Test
    fun `nothing recorded, no numbers`() {
        assertNull(sittingStats(emptyList(), NOW, utc))
    }
}
