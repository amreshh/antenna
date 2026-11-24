package com.antenna.data

import com.antenna.data.StatsFilter.Kind
import com.antenna.data.TestData.DAY_MS
import com.antenna.data.TestData.NOW
import com.antenna.data.TestData.channelUrl
import com.antenna.data.TestData.id
import com.antenna.data.TestData.video
import com.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/**
 * The Analytics cards for subscriptions, length, where videos were opened from, how new they
 * were, the last 7 days, and sittings. TestData's videos are 10 minutes long.
 */
class AnalyticsCardsTest {

    private val utc = ZoneOffset.UTC
    private val hour = 3_600_000L

    private fun analyticsOf(
        history: List<WatchEntry>,
        filters: List<StatsFilter> = emptyList(),
        subscriptions: List<Channel> = emptyList(),
        spans: List<WatchSpan> = emptyList(),
    ) = analytics(history, filters, NOW, utc, subscriptions, spans)

    private fun subscription(name: String, subscribedAt: Long = 0) = Channel(channelUrl(name), name, subscribedAt = subscribedAt)

    @Test
    fun `watch time from subscriptions and from the rest, and who you watch without subscribing`() {
        val history = listOf(
            watched(video(1, channel = "subscribed"), fraction = 1.0),
            watched(video(2, channel = "other"), fraction = 0.5),
            watched(video(3, channel = "favourite"), fraction = 1.0),
        )
        val subscriptions = listOf(subscription("subscribed"))
        val s = analyticsOf(history, subscriptions = subscriptions).shown
        assertEquals(Share(600_000, 1), s.subscribed)
        assertEquals(Share(900_000, 2), s.notSubscribed)
        assertEquals(listOf("favourite", "other"), s.unsubscribedChannels.map { it.name })
        val others = analyticsOf(history, listOf(StatsFilter(Kind.Subscribed, StatsFilter.NOT_SUBSCRIBED, "Other channels")), subscriptions)
        assertEquals(2, others.shown.videos)
    }

    @Test
    fun `subscriptions you haven't watched in 3 months`() {
        val history = listOf(
            watched(video(1, channel = "watched"), daysAgo = 10.0),
            watched(video(2, channel = "stale"), daysAgo = 100.0),
        )
        val subscriptions = listOf(
            subscription("watched"),
            subscription("stale"),
            subscription("never"),
            subscription("new", subscribedAt = NOW - 5 * DAY_MS), // too recent to say
        )
        assertEquals(listOf("never", "stale"), unwatchedSubscriptions(subscriptions, history, emptyList(), NOW)?.map { it.name })
        // Watched lately according to the watch log, though not the history.
        val lately = listOf(WatchSpan("x", channelUrl("stale"), NOW - DAY_MS, 0, 60_000, null))
        assertEquals(listOf("never"), unwatchedSubscriptions(subscriptions, history, lately, NOW)?.map { it.name })
        // A history that doesn't go back 3 months can't tell.
        assertNull(unwatchedSubscriptions(subscriptions, history.take(1), emptyList(), NOW))
    }

    @Test
    fun `by length, with how many of them are finished`() {
        val short = watched(video(1), fraction = 1.0).copy(durationMs = 3 * 60_000, positionMs = 3 * 60_000)
        val ten = watched(video(2), fraction = 1.0)
        val long = watched(video(3), fraction = 1.0).copy(durationMs = 90 * 60_000, positionMs = 45 * 60_000)
        val s = analyticsOf(listOf(short, ten, long)).shown
        assertEquals(listOf(1, 1, 0, 1), s.byLength.map { it.videos })
        assertEquals(listOf(1, 1, 0, 0), s.byLength.map { it.finished })
        val overAnHour = StatsFilter(Kind.Length, "3", "Over an hour")
        assertEquals(1, analyticsOf(listOf(short, ten, long), listOf(overAnHour)).shown.videos)
    }

    @Test
    fun `where videos were opened from, the first time it's recorded`() {
        val history = listOf(watched(video(1)), watched(video(2)), watched(video(3)))
        val spans = listOf(
            WatchSpan(id(1), "c", NOW - 3 * DAY_MS, 0, 60_000, OpenSource.Search),
            WatchSpan(id(1), "c", NOW - DAY_MS, 60_000, 120_000, OpenSource.History), // came back to it
            WatchSpan(id(2), "c", NOW - DAY_MS, 0, 60_000, null), // not known
            WatchSpan(id(2), "c", NOW - DAY_MS + 60_000, 60_000, 120_000, OpenSource.ForYou),
        )
        val s = analyticsOf(history, spans = spans).shown
        assertEquals(setOf(OpenSource.Search, OpenSource.ForYou), s.bySource.map { it.source }.toSet())
        assertEquals(2, s.withSource) // the third wasn't opened since it's recorded
        val fromSearch = StatsFilter(Kind.Source, OpenSource.Search.name, "Search")
        assertEquals(1, analyticsOf(history, listOf(fromSearch), spans = spans).shown.videos) // first opened from search
    }

    @Test
    fun `how new videos were when you first watched them`() {
        val history = listOf(
            watched(video(1, uploadedAtMs = NOW - 12 * hour)), // the day it came out
            watched(video(2, uploadedAtMs = NOW - 3 * DAY_MS)), // within a week
            watched(video(3, uploadedAtMs = NOW - 400 * DAY_MS)), // older
            watched(video(4)), // no upload date
        )
        val s = analyticsOf(history).shown
        assertEquals(listOf(1, 1, 0, 0, 1), s.byAge.map { it.videos })
        assertEquals(3, s.withAge)
        assertEquals(3 * DAY_MS, s.medianAgeMs)
        // The watch log knows it was first watched an hour after it came out (and last watched now).
        val first = listOf(WatchSpan(id(2), "c", NOW - 3 * DAY_MS + hour, 0, 60_000, null))
        assertEquals(listOf(2, 0, 0, 0, 1), analyticsOf(history, spans = first).shown.byAge.map { it.videos })
    }

    @Test
    fun `the last 7 days, and the 7 before`() {
        val history = listOf(
            watched(video(1), fraction = 1.0, daysAgo = 0.0),
            watched(video(2), fraction = 0.5, daysAgo = 6.0),
            watched(video(3), fraction = 1.0, daysAgo = 7.0),
            watched(video(4), fraction = 1.0, daysAgo = 13.0),
            watched(video(5), fraction = 1.0, daysAgo = 20.0), // neither
        )
        val s = analyticsOf(history).shown
        assertEquals(Share(900_000, 2), s.thisWeek)
        assertEquals(Share(1_200_000, 2), s.lastWeek)
    }

    @Test
    fun `sittings follow the picks`() {
        val history = listOf(watched(video(1, channel = "a")), watched(video(2, channel = "b")))
        val spans = listOf(
            WatchSpan(id(1), channelUrl("a"), NOW - 3 * hour, 0, 600_000, null),
            WatchSpan(id(2), channelUrl("b"), NOW - hour, 0, 300_000, null),
        )
        assertEquals(2, analyticsOf(history, spans = spans).sittings!!.sittings)
        val onlyA = analyticsOf(history, listOf(StatsFilter(Kind.Channel, channelUrl("a"), "a")), spans = spans).sittings!!
        assertEquals(1, onlyA.sittings)
        assertEquals(600_000L, onlyA.playedMs)
    }
}
