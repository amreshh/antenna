package com.antenna.data

import com.antenna.data.TestData.DAY_MS
import com.antenna.data.TestData.NOW
import com.antenna.data.TestData.channelUrl
import com.antenna.data.TestData.id
import com.antenna.data.TestData.video
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Subscriptions tab's blue dots (channels with new uploads), and the lengths the feed's
 * videos get from their channels.
 */
class NewUploadsTest {

    private fun upload(n: Int, channel: String, daysAgo: Double) =
        video(n, channel = channel, uploadedAtMs = NOW - (daysAgo * DAY_MS).toLong())

    @Test
    fun `a channel with an upload from the last week is new until it's opened`() {
        val feed = listOf(upload(1, "a", daysAgo = 1.0), upload(2, "b", daysAgo = 3.0), upload(3, "c", daysAgo = 30.0))
        assertEquals(setOf(channelUrl("a"), channelUrl("b")), channelsWithNewUploads(feed, emptyMap(), NOW))
        // Opened when it had video 1 in the feed: seen.
        val seen = mapOf(channelUrl("a") to setOf(id(1)))
        assertEquals(setOf(channelUrl("b")), channelsWithNewUploads(feed, seen, NOW))
    }

    @Test
    fun `an upload that came after the channel was opened makes it new again`() {
        val seen = mapOf(channelUrl("a") to setOf(id(1)))
        val feed = listOf(upload(4, "a", daysAgo = 0.1), upload(1, "a", daysAgo = 1.0))
        assertEquals(setOf(channelUrl("a")), channelsWithNewUploads(feed, seen, NOW))
    }

    @Test
    fun `in the New activity order, channels with new uploads come first, then the newest`() {
        val channels = listOf("seen", "new", "older", "quiet").map { Channel(channelUrl(it), it) }
        val feed = listOf(upload(1, "seen", daysAgo = 0.1), upload(2, "new", daysAgo = 2.0), upload(3, "older", daysAgo = 3.0))
        val order = newActivityOrder(channels, feed, withNew = setOf(channelUrl("new"), channelUrl("older")))
        assertEquals(listOf("new", "older", "seen", "quiet"), order.map { it.name })
    }

    @Test
    fun `an upload of unknown date, or older than a week, isn't new`() {
        val feed = listOf(video(1, channel = "a", uploadedAtMs = 0), upload(2, "b", daysAgo = NEW_UPLOAD_DAYS + 0.5))
        assertEquals(emptySet<String>(), channelsWithNewUploads(feed, emptyMap(), NOW))
    }

    @Test
    fun `the feed's videos get their channel's lengths, and keep them`() {
        val noLength = { n: Int -> video(n).copy(durationSeconds = -1) }
        val feed = listOf(noLength(1), noLength(2), video(3))
        val filled = feed.withLengths(mapOf(id(1) to 90L, id(3) to 1L))
        assertEquals(listOf(90L, -1L, 600L), filled.map { it.durationSeconds }) // 3 already had its own
        assertEquals(mapOf(id(1) to 90L, id(3) to 600L), filled.lengths())
        // A fresh feed comes in without lengths: the ones known are kept.
        assertEquals(listOf(90L, -1L), listOf(noLength(1), noLength(2)).withLengths(filled.lengths()).map { it.durationSeconds })
    }
}
