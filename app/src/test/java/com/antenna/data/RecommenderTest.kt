package com.antenna.data

import com.antenna.data.TestData.DAY_MS
import com.antenna.data.TestData.NOW
import com.antenna.data.TestData.channelUrl
import com.antenna.data.TestData.history
import com.antenna.data.TestData.id
import com.antenna.data.TestData.video
import com.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {

    private fun build(
        history: Map<String, WatchEntry>,
        uploads: List<VideoItem> = emptyList(),
        subscribed: Set<String> = emptySet(),
        spans: List<WatchSpan> = emptyList(),
        feedback: ForYouFeedback = ForYouFeedback(),
        shown: Map<String, Int> = emptyMap(),
    ) = Recommender.build(
        history, subscribed, seed = 1L, channelUploads = uploads, now = NOW,
        outcomes = Recommender.outcomes(history, spans, NOW), feedback = feedback, shown = shown,
    ).videos

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
        val h = history(
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
        val recent = Recommender.channelAffinity(history(watched(video(1), daysAgo = 0.0)), NOW).values.single()
        val old = Recommender.channelAffinity(history(watched(video(1), daysAgo = 60.0)), NOW).values.single()
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
        val result = Recommender.build(h, emptySet(), seed = 1L, now = NOW).videos.urls()
        assertTrue(result.indexOf(video(10).url) < result.indexOf(video(11).url))
    }

    @Test
    fun `favourites need about two videos watched, and leave out subscriptions`() {
        val h = history(
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
        val h = history(
            *(1..12).flatMap { c ->
                // channel c watched c times
                (1..c).map { n -> watched(video(c * 100 + n, channel = "c$c")) }
            }.toTypedArray(),
        )
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

    // --- How a video went ------------------------------------------------------------

    /** [ms] of video [n] played in one stretch, from [from] in. */
    private fun played(n: Int, ms: Long, from: Long = 0) =
        WatchSpan(id(n), channelUrl("chan$n"), NOW - DAY_MS, from, from + ms, OpenSource.ForYou)

    private fun Map<String, WatchEntry>.outcome(n: Int, spans: List<WatchSpan> = emptyList(), now: Long = NOW) =
        Recommender.outcomes(this, spans, now).getValue(id(n))

    @Test
    fun `a video left in its first 30 seconds was left early, unless it's the one open now`() {
        val h = history(
            watched(video(1), fraction = 0.01, daysAgo = 1.0), // 6 seconds in
            watched(video(2), fraction = 0.3, daysAgo = 1.0),
            watched(video(3), fraction = 0.01), // opened last: may still be playing
        )
        assertEquals(Watched.LeftEarly, h.outcome(1).watched)
        assertEquals(Watched.Partly, h.outcome(2).watched)
        assertEquals(Watched.Partly, h.outcome(3).watched)
    }

    @Test
    fun `the video opened last is judged an hour later`() {
        val h = history(watched(video(1), fraction = 0.01, daysAgo = 1.0 / 48)) // half an hour ago
        assertEquals(Watched.Partly, h.outcome(1).watched)
        assertEquals(Watched.LeftEarly, h.outcome(1, now = NOW + 60 * 60 * 1000L).watched)
    }

    @Test
    fun `the watch log says what was played, not where the video was left`() {
        val h = history(
            watched(video(1), fraction = 0.5, daysAgo = 1.0),
            watched(video(2), fraction = 0.01, daysAgo = 1.0),
            watched(video(9)),
        )
        // Skipped to the middle and left 5 seconds later: left early, though it stopped halfway.
        // Played 2 minutes, then started again: not left early, and 2 minutes of 10 in.
        val spans = listOf(played(1, 5_000, from = 295_000), played(2, 120_000), played(2, 6_000))
        assertEquals(Watched.LeftEarly, h.outcome(1, spans).watched)
        assertEquals(Watched.Partly, h.outcome(2, spans).watched)
        assertEquals(0.2f, h.outcome(2, spans).fraction, 0.001f)
    }

    @Test
    fun `a video finished once stays finished when you start it again`() {
        val h = history(watched(video(1), fraction = 0.02, daysAgo = 1.0), watched(video(9)))
        assertEquals(Watched.Finished, h.outcome(1, listOf(played(1, 590_000), played(1, 12_000))).watched)
        assertFalse(video(1).url in build(h, spans = listOf(played(1, 590_000))).urls())
    }

    @Test
    fun `a short video is left early in its first half`() {
        val h = history(watched(video(1), daysAgo = 1.0).copy(positionMs = 0, durationMs = 40_000), watched(video(9)))
        assertEquals(Watched.LeftEarly, h.outcome(1, listOf(played(1, 15_000))).watched)
        assertEquals(Watched.Finished, h.outcome(1, listOf(played(1, 22_000))).watched) // past half, in its last 30 s
    }

    @Test
    fun `a video left early isn't suggested, and its up next list doesn't count`() {
        val h = history(
            watched(video(1), fraction = 0.01, daysAgo = 1.0, related = listOf(video(10), video(11))),
            watched(video(2), related = listOf(video(1), video(12))),
        )
        assertEquals(listOf(video(12).url), build(h).urls())
    }

    @Test
    fun `leaving a channel's videos early counts against it`() {
        val h = history(
            watched(video(1, channel = "bailed"), fraction = 0.01, daysAgo = 1.0),
            watched(video(2, channel = "bailed"), fraction = 0.01, daysAgo = 1.0),
            watched(video(3, channel = "liked")),
        )
        val affinity = Recommender.channelAffinity(h, NOW)
        assertTrue(affinity.getValue(channelUrl("bailed")) < 0)
        assertTrue(affinity.getValue(channelUrl("liked")) > 0)
    }

    @Test
    fun `a channel you keep leaving early doesn't become a favourite`() {
        // Opening ten of its videos used to make it one, however quickly you left.
        val h = history(*(1..10).map { watched(video(it, channel = "clickbait"), fraction = 0.01, daysAgo = it / 10.0) }.toTypedArray())
        assertTrue(Recommender.favouriteChannels(h, NOW, exclude = emptySet()).isEmpty())
    }

    // --- Not interested, and Don't recommend channel --------------------------------------

    private fun notInterested(vararg videos: VideoItem) =
        ForYouFeedback(notInterested = videos.associate { v -> idOf(v) to NotInterested(idOf(v), v.channelUrl, v.uploader, NOW) })

    private fun idOf(v: VideoItem) = videoIdOf(v.url)!!

    @Test
    fun `a video you're not interested in isn't suggested`() {
        val h = history(watched(video(1), related = listOf(video(10), video(11))))
        assertEquals(listOf(video(11).url), build(h, feedback = notInterested(video(10))).urls())
    }

    @Test
    fun `not interested counts against the video's channel`() {
        val h = history(watched(video(1, channel = "meh")))
        val said = notInterested(*(10..14).map { video(it, channel = "meh") }.toTypedArray())
        val before = Recommender.channelAffinity(h, NOW).getValue(channelUrl("meh"))
        assertTrue(Recommender.channelAffinity(h, NOW, feedback = said).getValue(channelUrl("meh")) < before)
    }

    @Test
    fun `a hidden channel's videos aren't suggested, with a link or without`() {
        val h = history(
            watched(video(1), related = listOf(video(10, channel = "loud"), video(11, channel = "loud", withChannelUrl = false), video(12))),
        )
        val said = ForYouFeedback(hiddenChannels = listOf(HiddenChannel(channelUrl("loud"), "loud", NOW)))
        assertEquals(listOf(video(12).url), build(h, feedback = said).urls())
    }

    @Test
    fun `a hidden channel isn't a favourite`() {
        val h = history(watched(video(1, channel = "fav")), watched(video(2, channel = "fav")))
        val said = ForYouFeedback(hiddenChannels = listOf(HiddenChannel(channelUrl("fav"), "fav", NOW)))
        assertTrue(Recommender.favouriteChannels(h, NOW, exclude = emptySet(), feedback = said).isEmpty())
    }

    // --- Passed over ---------------------------------------------------------------------

    /** Video 10 suggested after two videos, 11 after one: 10 comes first, by far. */
    private val twoLists = arrayOf(
        watched(video(1), related = listOf(video(10), video(11))),
        watched(video(2), daysAgo = 1.0, related = listOf(video(10))),
    )

    @Test
    fun `a video passed over on earlier days ranks lower`() {
        assertEquals(video(10).url, build(history(*twoLists)).first().url)
        assertEquals(video(11).url, build(history(*twoLists), shown = mapOf(id(10) to 4)).first().url)
    }

    @Test
    fun `a video you opened wasn't passed over`() {
        val h = history(*twoLists, watched(video(10), fraction = 0.3, daysAgo = 2.0))
        assertEquals(video(10).url, build(h, shown = mapOf(id(10) to 4)).first().url)
    }

    // --- Further pages ---------------------------------------------------------------

    /** For you with [more] pages after the first: the up next lists fetched for each. */
    private fun withPages(
        history: Map<String, WatchEntry>,
        more: List<List<List<VideoItem>>>,
        feedback: ForYouFeedback = ForYouFeedback(),
    ) = Recommender.build(
        history, emptySet(), seed = 1L, now = NOW,
        outcomes = Recommender.outcomes(history, emptyList(), NOW), feedback = feedback, more = more,
    )

    /** Ten watched videos with 20 suggestions each: 200 in all. */
    private val tenLists = history(
        *(1..10).map { n -> watched(video(n), daysAgo = n.toDouble(), related = (1..20).map { j -> video(1000 + n * 20 + j) }) }
            .toTypedArray(),
    )

    @Test
    fun `the first page has 150, and the next ones what's left, 40 at a time`() {
        val first = withPages(tenLists, more = emptyList())
        assertEquals(150, first.videos.size)
        assertEquals(50, first.left)
        val second = withPages(tenLists, more = listOf(emptyList()))
        assertEquals(first.videos, second.videos.take(150)) // the first page stays as it was
        assertEquals(190, second.videos.distinctBy { it.url }.size)
        assertEquals(10, second.left)
        assertEquals(200, withPages(tenLists, more = listOf(emptyList(), emptyList())).videos.size)
    }

    @Test
    fun `a page's fetched up next lists add new suggestions after what's there`() {
        val h = history(watched(video(1), related = listOf(video(10), video(11))))
        val first = withPages(h, more = emptyList())
        assertEquals(0, first.left)
        val second = withPages(h, more = listOf(listOf(listOf(video(11), video(20), video(21)))))
        assertEquals(first.videos.urls(), second.videos.take(2).urls())
        assertEquals(setOf(video(20).url, video(21).url), second.videos.drop(2).urls().toSet())
        assertEquals(1, second.pages)
    }

    @Test
    fun `a suggestion in several fetched lists comes first on its page`() {
        val h = history(watched(video(1), related = listOf(video(10))))
        val lists = listOf(listOf(video(20), video(30)), listOf(video(21), video(30)), listOf(video(22), video(30)))
        assertEquals(video(30).url, withPages(h, more = listOf(lists)).videos[1].url)
    }

    @Test
    fun `further pages leave out what For you leaves out`() {
        val h = history(
            watched(video(1), related = listOf(video(10))),
            watched(video(20), fraction = 1.0, daysAgo = 1.0),
        )
        val feedback = ForYouFeedback(notInterested = mapOf(id(21) to NotInterested(id(21), channelUrl("chan21"), "chan21", NOW)))
        val result = withPages(h, more = listOf(listOf(listOf(video(20), video(21), video(22)))), feedback = feedback)
        assertEquals(listOf(video(10).url, video(22).url), result.videos.urls())
    }
}
