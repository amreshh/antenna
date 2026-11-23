package com.example.antenna.data

import com.example.antenna.data.TestData.DAY_MS
import com.example.antenna.data.TestData.NOW
import com.example.antenna.data.TestData.channelUrl
import com.example.antenna.data.TestData.history
import com.example.antenna.data.TestData.video
import com.example.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {

    private fun build(
        history: Map<String, WatchEntry>,
        uploads: List<VideoItem> = emptyList(),
        subscribed: Set<String> = emptySet(),
    ) = Recommender.build(history, subscribed, seed = 1L, channelUploads = uploads, now = NOW)

    private fun List<VideoItem>.urls() = map { it.url }

    // --- Blending "up next" lists ----------------------------------------------------

    @Test
    fun `suggestions from what you watched show up`() {
        val result = build(history(watched(video(1), related = listOf(video(10), video(11)))))
        assertEquals(setOf(video(10).url, video(11).url), result.urls().toSet())
    }

    @Test
    fun `videos you already watched are left out`() {
        val h = history(
            watched(video(1), related = listOf(video(2), video(3))),
            watched(video(2), fraction = 1.0, daysAgo = 1.0),
        )
        val result = build(h).urls()
        assertFalse(video(2).url in result)
        assertTrue(video(3).url in result)
    }

    @Test
    fun `a video suggested after several things you watched ranks first`() {
        val h = history(
            watched(video(1), related = listOf(video(10), video(20))),
            watched(video(2), daysAgo = 1.0, related = listOf(video(11), video(20))),
            watched(video(3), daysAgo = 2.0, related = listOf(video(12), video(20))),
        )
        assertEquals(video(20).url, build(h).first().url)
    }

    @Test
    fun `up next lists from far back still count`() {
        // 100 watched videos, each suggesting one video of its own: the oldest still counts.
        val entries = (1..100).map { n ->
            watched(video(n), daysAgo = n.toDouble(), related = listOf(video(1000 + n)))
        }
        val result = build(history(*entries.toTypedArray())).urls()
        assertTrue(video(1100).url in result)
    }

    @Test
    fun `no channel takes more than three spots at the top`() {
        val sameChannel = (10..19).map { video(it, channel = "busy") }
        val others = (20..25).map { video(it) }
        val h = history(watched(video(1), related = sameChannel + others))
        val topNine = build(h).take(9)
        assertEquals(3, topNine.count { it.uploader == "busy" })
    }

    // --- Channel preferences ---------------------------------------------------------

    @Test
    fun `channels watched more, and further into, score higher`() {
        val h = listOf(
            watched(video(1, channel = "loved"), fraction = 1.0),
            watched(video(2, channel = "loved"), fraction = 1.0),
            watched(video(3, channel = "skimmed"), fraction = 0.1),
            watched(video(4, channel = "skimmed"), fraction = 0.1),
        )
        val affinity = Recommender.channelAffinity(h, NOW)
        assertTrue(affinity.getValue(channelUrl("loved")) > affinity.getValue(channelUrl("skimmed")))
    }

    @Test
    fun `old watches fade, by half after 60 days`() {
        val recent = Recommender.channelAffinity(listOf(watched(video(1), daysAgo = 0.0)), NOW).values.single()
        val old = Recommender.channelAffinity(listOf(watched(video(1), daysAgo = 60.0)), NOW).values.single()
        assertEquals(recent / 2, old, 1e-9)
    }

    @Test
    fun `a liked channel's suggestions rank above an unknown channel's`() {
        // Both suggested once, at the same spot in two lists; only one channel is watched a lot.
        val h = history(
            watched(video(1, channel = "fav"), related = listOf(video(10, channel = "fav"))),
            watched(video(2, channel = "fav"), related = listOf(video(11, channel = "stranger"))),
            watched(video(3, channel = "fav")),
            watched(video(4, channel = "fav")),
        )
        val result = Recommender.build(h, emptySet(), seed = 1L, now = NOW).urls()
        assertTrue(result.indexOf(video(10).url) < result.indexOf(video(11).url))
    }

    @Test
    fun `favourites need about two videos watched, and leave out subscriptions`() {
        val h = listOf(
            watched(video(1, channel = "fav")), watched(video(2, channel = "fav")),
            watched(video(3, channel = "once")),
            watched(video(4, channel = "subbed")), watched(video(5, channel = "subbed")),
        )
        val favourites = Recommender.favouriteChannels(h, NOW, exclude = setOf(channelUrl("subbed")))
        assertEquals(listOf(channelUrl("fav")), favourites.map { it.url })
        assertEquals("fav", favourites.single().name)
    }

    @Test
    fun `at most eight favourites, the most watched first`() {
        val h = (1..12).flatMap { c ->
            // channel c watched c times
            (1..c).map { n -> watched(video(c * 100 + n, channel = "c$c")) }
        }
        val favourites = Recommender.favouriteChannels(h, NOW, exclude = emptySet())
        assertEquals(8, favourites.size)
        assertEquals(channelUrl("c12"), favourites.first().url)
    }

    // --- New uploads from favourite channels -----------------------------------------

    private val likesFav = arrayOf(
        watched(video(1, channel = "fav"), daysAgo = 1.0),
        watched(video(2, channel = "fav"), daysAgo = 2.0),
    )

    @Test
    fun `new uploads from a favourite channel are mixed in`() {
        val upload = video(50, channel = "fav", uploadedAtMs = NOW - DAY_MS)
        assertEquals(listOf(upload.url), build(history(*likesFav), uploads = listOf(upload)).urls())
    }

    @Test
    fun `uploads over a month old, and ones you watched, are left out`() {
        val old = video(50, channel = "fav", uploadedAtMs = NOW - 40 * DAY_MS)
        val seen = video(51, channel = "fav", uploadedAtMs = NOW - DAY_MS)
        val h = history(*likesFav, watched(seen, fraction = 1.0))
        assertTrue(build(h, uploads = listOf(old, seen)).isEmpty())
    }

    @Test
    fun `at most two new uploads per channel, the newest`() {
        val uploads = (1..5).map { d -> video(60 + d, channel = "fav", uploadedAtMs = NOW - d * DAY_MS) }
        val result = build(history(*likesFav), uploads = uploads).urls()
        assertEquals(setOf(video(61).url, video(62).url), result.toSet())
    }

    @Test
    fun `up next lists saved without channel links still match channels by name`() {
        val h = history(
            watched(video(1, channel = "fav"), related = listOf(video(10, channel = "fav", withChannelUrl = false))),
            watched(video(2, channel = "fav"), related = listOf(video(11, channel = "x", withChannelUrl = false))),
            watched(video(3, channel = "fav")),
        )
        val result = build(h).urls()
        assertTrue(result.indexOf(video(10).url) < result.indexOf(video(11).url))
    }
}
