package com.antenna.data

import com.antenna.data.TestData.NOW
import com.antenna.data.TestData.channelUrl
import com.antenna.data.TestData.video
import com.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The Analytics tab's numbers. TestData's videos are 10 minutes long. */
class WatchStatsTest {

    private val utc = ZoneOffset.UTC
    private fun stats(vararg entries: WatchEntry) = watchStats(entries.toList(), NOW, utc)
    private val tenMin = 600_000L

    @Test
    fun `totals add up`() {
        val s = stats(
            watched(video(1, channel = "a"), fraction = 1.0),
            watched(video(2, channel = "a"), fraction = 0.5),
            watched(video(3, channel = "b"), fraction = 0.05),
        )
        assertEquals(3, s.videos)
        assertEquals(tenMin + tenMin / 2 + tenMin / 20, s.watchedMs)
        assertEquals(2, s.channels)
        assertEquals(1f / 3, s.finishedShare, 1e-6f)
    }

    @Test
    fun `watch time lands on the day it was watched, today last`() {
        val s = stats(
            watched(video(1), fraction = 1.0, daysAgo = 0.0),
            watched(video(2), fraction = 0.5, daysAgo = 2.0),
            watched(video(3), fraction = 1.0, daysAgo = 45.0), // before the 30 days
        )
        assertEquals(30, s.perDay.size)
        assertEquals(tenMin, s.perDay.last())
        assertEquals(tenMin / 2, s.perDay[27])
        assertEquals(tenMin + tenMin / 2, s.perDay.sum())
        assertEquals(Instant.ofEpochMilli(NOW).atZone(utc).toLocalDate().minusDays(29), s.firstDay)
    }

    @Test
    fun `watch time lands on the hour it was watched`() {
        val hour = Instant.ofEpochMilli(NOW).atZone(utc).hour
        val s = stats(watched(video(1), fraction = 1.0))
        assertEquals(tenMin, s.byHour[hour])
        assertEquals(tenMin, s.byHour.sum())
    }

    @Test
    fun `the zoomable chart has every hour watched in, from the whole history`() {
        val now = Instant.ofEpochMilli(NOW).atZone(utc)
        val today = now.toLocalDate().toEpochDay()
        val s = stats(
            watched(video(1), fraction = 1.0, daysAgo = 0.0),
            watched(video(2), fraction = 0.5, daysAgo = 0.0), // the same hour
            watched(video(3), fraction = 1.0, daysAgo = 45.0), // before the 30 days, still there
            watched(video(4), fraction = 0.0, daysAgo = 3.0), // nothing watched: no hour
        )
        assertEquals(
            mapOf(hourIndex(today, now.hour) to tenMin + tenMin / 2, hourIndex(today - 45, now.hour) to tenMin),
            s.hourly,
        )
    }

    @Test
    fun `top channels are by time spent, with their video counts`() {
        val s = stats(
            watched(video(1, channel = "many"), fraction = 0.2),
            watched(video(2, channel = "many"), fraction = 0.2),
            watched(video(3, channel = "many"), fraction = 0.2),
            watched(video(4, channel = "long"), fraction = 1.0),
        )
        assertEquals(listOf("long", "many"), s.topChannels.map { it.name })
        assertEquals(3, s.topChannels[1].videos)
        assertEquals(channelUrl("long"), s.topChannels[0].url)
    }

    @Test
    fun `videos without a channel link are grouped by name`() {
        val s = stats(
            watched(video(1, channel = "old", withChannelUrl = false)),
            watched(video(2, channel = "old", withChannelUrl = false)),
        )
        assertEquals(1, s.channels)
        assertNull(s.topChannels.single().url)
        assertEquals(2, s.topChannels.single().videos)
    }

    @Test
    fun `completion buckets`() {
        val s = stats(
            watched(video(1), fraction = 0.95), // finished
            watched(video(2), fraction = 0.7), // over half
            watched(video(3), fraction = 0.3), // under half
            watched(video(4), fraction = 0.02), // barely
            watched(video(5), fraction = 0.02),
        )
        assertEquals(listOf(1, 1, 1, 2), s.completion)
    }

    @Test
    fun `no history, no numbers`() {
        val s = stats()
        assertEquals(0, s.videos)
        assertEquals(0f, s.finishedShare)
        assertEquals(0L, s.perDay.sum())
    }

    // --- Topics and categories ----------------------------------------------------

    private fun titled(n: Int, title: String, channel: String = "chan$n", tags: List<String> = emptyList(),
                       category: String? = null, fraction: Double = 1.0) =
        watched(video(n, channel = channel).copy(title = title), fraction = fraction).copy(tags = tags, category = category)

    private fun topicKeys(e: WatchEntry) = topicsOf(e).map { it.first }.toSet()

    @Test
    fun `topics are a title's meaningful words`() {
        val keys = topicKeys(titled(1, "Why Linux Gaming Is Finally Good (Official Video) 4K"))
        assertEquals(setOf("linux", "gaming", "finally"), keys)
    }

    @Test
    fun `capitals count even when short, numbers and the channel's name don't`() {
        val keys = topicKeys(titled(1, "AI chips explained by Gamers Nexus in 2026", channel = "Gamers Nexus"))
        assertEquals(setOf("ai", "chips"), keys)
    }

    @Test
    fun `short tags are topics, long ones and the channel's own aren't`() {
        val e = titled(
            1, "Kernel", channel = "Kyle Kulinski",
            tags = listOf("Tech News", "kyle kulinski", "a very long tag that reads like a sentence", "Linux"),
        )
        assertEquals(setOf("kernel", "tech news", "linux"), topicKeys(e))
    }

    @Test
    fun `top topics are by watch time and need two videos`() {
        val s = stats(
            titled(1, "Linux kernel news", fraction = 0.2),
            titled(2, "Linux desktop", fraction = 0.2),
            titled(3, "Rust in Linux", fraction = 1.0),
            titled(4, "Rust compiler", fraction = 1.0),
            titled(5, "Gardening once", fraction = 1.0), // one video: not a topic yet
        )
        assertEquals(listOf("Rust", "Linux"), s.topics.map { it.name }.take(2))
        assertEquals(3, s.topics.first { it.name == "Linux" }.videos)
        assertTrue(s.topics.none { it.name.startsWith("Gardening") })
    }

    @Test
    fun `a topic shows the way it's usually written`() {
        val s = stats(titled(1, "NVIDIA earnings"), titled(2, "NVIDIA drivers"), titled(3, "nvidia shield"))
        assertEquals("NVIDIA", s.topics.first { it.name.equals("nvidia", ignoreCase = true) }.name)
    }

    @Test
    fun `categories by watch time, and how many videos have one`() {
        val s = stats(
            titled(1, "a", category = "Music", fraction = 0.5),
            titled(2, "b", category = "Gaming", fraction = 1.0),
            titled(3, "c", category = "Music", fraction = 1.0),
            titled(4, "d"), // watched before categories were recorded
        )
        assertEquals(listOf("Music", "Gaming"), s.categories.map { it.name })
        assertEquals(2, s.categories.first().videos)
        assertEquals(3, s.withCategory)
    }

    @Test
    fun `watch time reads in hours and minutes`() {
        assertEquals("0 min", formatWatchTime(0))
        assertEquals("< 1 min", formatWatchTime(13_000))
        assertEquals("45 min", formatWatchTime(45 * 60_000L))
        assertEquals("1 h", formatWatchTime(60 * 60_000L))
        assertEquals("3 h 5 min", formatWatchTime((3 * 60 + 5) * 60_000L))
        assertEquals("120 h", formatWatchTime(120 * 3_600_000L + 7 * 60_000L))
    }
}
