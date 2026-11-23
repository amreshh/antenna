package com.example.antenna.data

import com.example.antenna.data.StatsFilter.Kind
import com.example.antenna.data.TestData.NOW
import com.example.antenna.data.TestData.channelUrl
import com.example.antenna.data.TestData.video
import com.example.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * What SponsorBlock skipped, and narrowing the Analytics tab down by tapping its cards.
 * TestData's videos are 10 minutes long.
 */
class AnalyticsFiltersTest {

    private val utc = ZoneOffset.UTC
    private val tenMin = 600_000L
    private fun analyticsOf(vararg entries: WatchEntry, filters: List<StatsFilter> = emptyList()) =
        analytics(entries.toList(), filters, NOW, utc)

    private fun channel(name: String) = StatsFilter(Kind.Channel, channelUrl(name), name)
    private fun WatchEntry.skipping(vararg skips: SkippedSegment) = copy(skipped = skips.toList())

    // --- What was skipped -----------------------------------------------------------

    @Test
    fun `skips add up by kind, the most time first`() {
        val s = analyticsOf(
            watched(video(1)).skipping(
                SkippedSegment("sponsor", 0, 60_000),
                SkippedSegment("sponsor", 300_000, 30_000),
                SkippedSegment("selfpromo", 500_000, 10_000),
            ),
            watched(video(2)).skipping(SkippedSegment("sponsor", 100_000, 45_000)),
            watched(video(3)),
        ).shown
        assertEquals(
            listOf(SkipTime("sponsor", 135_000, segments = 3, videos = 2), SkipTime("selfpromo", 10_000, segments = 1, videos = 1)),
            s.skips,
        )
        assertEquals(145_000L, s.skippedMs)
        assertEquals(4, s.skippedSegments)
        assertEquals(2, s.videosWithSkips)
    }

    @Test
    fun `a segment skipped again counts once, at its longest`() {
        val e = watched(video(1))
            .withSkip(SkippedSegment("sponsor", 10_000, 20_000)) // from part of the way in
            .withSkip(SkippedSegment("sponsor", 10_000, 30_000)) // again, from its start
            .withSkip(SkippedSegment("sponsor", 10_000, 5_000)) // and once more, near its end
            .withSkip(SkippedSegment("selfpromo", 10_000, 8_000)) // another kind at the same spot
        assertEquals(listOf(SkippedSegment("sponsor", 10_000, 30_000), SkippedSegment("selfpromo", 10_000, 8_000)), e.skipped)
    }

    // --- Picking --------------------------------------------------------------------

    @Test
    fun `nothing picked, every card shows everything`() {
        val a = analyticsOf(watched(video(1)), watched(video(2)))
        Kind.entries.forEach { assertEquals(2, a.forCard(it).videos) }
        assertEquals(a.all, a.shown)
    }

    @Test
    fun `a pick narrows every number to its videos`() {
        val a = analyticsOf(
            watched(video(1, channel = "a"), fraction = 1.0),
            watched(video(2, channel = "a"), fraction = 0.5),
            watched(video(3, channel = "b"), fraction = 1.0),
            filters = listOf(channel("a")),
        )
        assertEquals(2, a.shown.videos)
        assertEquals(tenMin + tenMin / 2, a.shown.watchedMs)
        assertEquals(listOf(1, 1, 0, 0), a.forCard(Kind.Completion).completion)
        assertEquals(3, a.all.videos) // for the share of all your watch time
    }

    @Test
    fun `picks in different cards combine`() {
        val a = analyticsOf(
            watched(video(1, channel = "a"), fraction = 1.0),
            watched(video(2, channel = "a"), fraction = 0.05),
            watched(video(3, channel = "b"), fraction = 1.0),
            filters = listOf(channel("a"), StatsFilter(Kind.Completion, "0", "Finished")),
        )
        assertEquals(1, a.shown.videos)
        assertEquals(tenMin, a.shown.watchedMs)
    }

    @Test
    fun `each card leaves out its own pick, so it still lists what else could be picked`() {
        val a = analyticsOf(
            watched(video(1, channel = "a"), fraction = 1.0),
            watched(video(2, channel = "a"), fraction = 0.05),
            watched(video(3, channel = "b"), fraction = 0.05),
            filters = listOf(channel("a"), StatsFilter(Kind.Completion, "3", "Barely started")),
        )
        // Top channels: the channels of every barely started video.
        assertEquals(listOf("a", "b"), a.forCard(Kind.Channel).topChannels.map { it.name }.sorted())
        // How much you finish: all of a's videos.
        assertEquals(listOf(1, 0, 0, 1), a.forCard(Kind.Completion).completion)
        // A card with nothing picked: the videos that match everything.
        assertEquals(1, a.forCard(Kind.Hour).videos)
        assertEquals(channel("a"), a.picked(Kind.Channel))
        assertNull(a.picked(Kind.Topic))
    }

    @Test
    fun `days, hours, topics, categories and kinds of skip pick their videos`() {
        val now = Instant.ofEpochMilli(NOW).atZone(utc)
        val first = watched(video(1).copy(title = "Rust compiler"), daysAgo = 0.0)
            .copy(category = "Science & Technology")
            .skipping(SkippedSegment("sponsor", 0, 1_000))
        val dayBefore = watched(video(2).copy(title = "Rust borrow checker"), daysAgo = 1.0).copy(category = "Gaming")
        val hoursBefore = watched(video(3).copy(title = "Gardening"), daysAgo = 1.0 / 24)
        fun count(filter: StatsFilter) = analyticsOf(first, dayBefore, hoursBefore, filters = listOf(filter)).shown.videos

        val today = if (now.hour >= 1) 2 else 1 // the video an hour earlier is today too, unless it's past midnight
        assertEquals(today, count(StatsFilter(Kind.Day, now.toLocalDate().toEpochDay().toString(), "today")))
        assertEquals(2, count(StatsFilter(Kind.Hour, now.hour.toString(), "this hour"))) // and the day before, at the same time
        assertEquals(2, count(StatsFilter(Kind.Topic, "rust", "Rust")))
        assertEquals(1, count(StatsFilter(Kind.Category, "Gaming", "Gaming")))
        assertEquals(1, count(StatsFilter(Kind.Skipped, "sponsor", "Sponsors")))
        assertEquals(0, count(StatsFilter(Kind.Skipped, "intro", "Intros")))
    }

    @Test
    fun `a picked channel is listed even when it isn't among the top ones`() {
        val many = (1..10).map { watched(video(it, channel = "c$it"), fraction = 1.0) }
        val small = watched(video(11, channel = "small"), fraction = 0.1)
        val picked = analytics(many + small, listOf(channel("small")), NOW, utc).forCard(Kind.Channel).topChannels
        assertEquals(WatchStats.TOP_CHANNELS + 1, picked.size)
        assertEquals("small", picked.last().name)
        // Not picked, it's left out.
        assertTrue(analytics(many + small, emptyList(), NOW, utc).shown.topChannels.none { it.name == "small" })
    }

    @Test
    fun `a picked topic is listed even when it comes up in one video`() {
        val a = analyticsOf(
            watched(video(1).copy(title = "Linux kernel")),
            watched(video(2).copy(title = "Linux desktop")),
            filters = listOf(StatsFilter(Kind.Topic, "kernel", "kernel")),
        )
        assertEquals(setOf("linux", "kernel"), a.forCard(Kind.Topic).topics.map { it.key }.toSet())
        assertEquals(listOf("linux"), a.all.topics.map { it.key }) // two videos needed otherwise
    }

    @Test
    fun `nothing matches every pick`() {
        val a = analyticsOf(
            watched(video(1, channel = "a"), fraction = 1.0),
            watched(video(2, channel = "b"), fraction = 0.05),
            filters = listOf(channel("a"), StatsFilter(Kind.Completion, "3", "Barely started")),
        )
        assertEquals(0, a.shown.videos)
        assertEquals(0L, a.shown.watchedMs)
    }

    @Test
    fun `picks are kept as text and read back`() {
        val filters = listOf(channel("a"), StatsFilter(Kind.Hour, "22", "22:00–23:00"), StatsFilter(Kind.Topic, "c++", "C++"))
        assertEquals(filters, StatsFilter.restore(StatsFilter.save(filters)))
        // Something unknown, or cut short, is left out.
        assertEquals(emptyList<StatsFilter>(), StatsFilter.restore(listOf("NoSuchKind", "x", "y", "Hour")))
    }

    @Test
    fun `a channel's link comes back out of its key, unless it's known only by name`() {
        assertEquals(channelUrl("a"), channelUrlOfKey(watched(video(1, channel = "a")).channelKey()))
        assertNull(channelUrlOfKey(watched(video(2, channel = "old", withChannelUrl = false)).channelKey()))
    }
}
