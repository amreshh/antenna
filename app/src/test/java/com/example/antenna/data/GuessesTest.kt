package com.example.antenna.data

import com.example.antenna.data.GuessReason.ForYou
import com.example.antenna.data.GuessReason.NewUpload
import com.example.antenna.data.GuessReason.Unfinished
import com.example.antenna.data.GuessReason.WatchLater
import com.example.antenna.data.TestData.DAY_MS
import com.example.antenna.data.TestData.NOW
import com.example.antenna.data.TestData.channelUrl
import com.example.antenna.data.TestData.id
import com.example.antenna.data.TestData.video
import com.example.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Guessing which videos you'll open next, and keeping score (Guesses.kt, for Prefetcher). */
class GuessesTest {

    private val utc = ZoneOffset.UTC
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun guess(
        n: Int,
        at: Long = NOW - hour,
        reason: GuessReason = ForYou,
        channel: String = "c",
        rank: Int = 1,
        fetched: Boolean = true,
        used: Boolean = false,
    ) = Guess(id(n), at, reason, channel, rank, fetched, used)

    private fun ids(candidates: List<Candidate>) = candidates.map { videoIdOf(it.video.url) }

    // --- Candidates and ranking -------------------------------------------------------------

    @Test
    fun `candidates are unfinished videos, Watch later, For you and new uploads, not Shorts`() {
        val history = listOf(
            watched(video(1), fraction = 0.4, daysAgo = 1.0), // unfinished
            watched(video(2), fraction = 1.0, daysAgo = 1.0), // finished
            watched(video(3), fraction = 0.4, daysAgo = 20.0), // started too long ago
            watched(video(4), fraction = 0.01, daysAgo = 1.0), // barely opened
        )
        val short = video(31).copy(url = "https://www.youtube.com/shorts/${id(31)}")
        val feed = listOf(video(32, uploadedAtMs = 1), short.copy(uploadedAtMs = 3), video(30, uploadedAtMs = 2))
        val c = candidates(history, watchLater = listOf(video(10)), forYou = listOf(video(20), video(21)), feed = feed, now = NOW)
        assertEquals(listOf(Unfinished, WatchLater, ForYou, ForYou, NewUpload, NewUpload), c.map { it.reason })
        assertEquals(listOf(id(1), id(10), id(20), id(21), id(30), id(32)), ids(c)) // newest upload first
        assertEquals(listOf(0, 0, 0, 1, 0, 1), c.map { it.position })
    }

    /** A channel's upload, [daysOld] days before NOW. */
    private fun upload(n: Int, channel: String, daysOld: Int) = video(n, channel = channel, uploadedAtMs = NOW - daysOld * DAY_MS)

    @Test
    fun `next in line is the oldest upload after the newest you finished`() {
        val history = listOf(
            watched(upload(1, "series", daysOld = 10), fraction = 1.0), // finished
            watched(upload(2, "series", daysOld = 8), fraction = 1.0), // finished: the last one
            watched(upload(4, "series", daysOld = 4), fraction = 0.2), // started, not finished
        )
        val feed = listOf(
            upload(1, "series", 10), upload(2, "series", 8), upload(3, "series", 6),
            upload(4, "series", 4), upload(5, "series", 2),
        )
        assertEquals(listOf(id(3)), nextInLine(history, feed).map { videoIdOf(it.url) })
    }

    @Test
    fun `no next in line when caught up, never watched, or further behind than the feed goes`() {
        val caughtUp = listOf(watched(upload(1, "a", daysOld = 2), fraction = 1.0))
        assertEquals(emptyList<VideoItem>(), nextInLine(caughtUp, listOf(upload(1, "a", 2))))
        assertEquals(emptyList<VideoItem>(), nextInLine(emptyList(), listOf(upload(1, "a", 2))))
        // Finished one from 60 days ago, but the feed only goes back 20 days: there may be others in between.
        val farBehind = listOf(watched(upload(9, "a", daysOld = 60), fraction = 1.0))
        assertEquals(emptyList<VideoItem>(), nextInLine(farBehind, listOf(upload(1, "a", 20), upload(2, "a", 5))))
    }

    @Test
    fun `watching in order, next in line wins over new uploads`() {
        val next = Candidate(upload(3, "series", 6), GuessReason.NextInLine, 0)
        val newest = Candidate(upload(5, "series", 2), NewUpload, 0)
        val log = GuessLog(
            guesses = (1..10).map { guess(100 + it, reason = NewUpload, channel = channelUrl("other")) } + // all missed
                (11..20).map { guess(100 + it, reason = GuessReason.NextInLine, channel = channelUrl("other"), used = true) },
        )
        assertEquals(id(3), ids(rankCandidates(listOf(newest, next), emptyList(), log, NOW, utc)).first())
    }

    @Test
    fun `the channel you watch at this hour comes first`() {
        // NOW is 8:00 UTC. "morning" is watched at 8:00, "evening" (more often) at 20:00.
        val history = (1..3).map { watched(video(it, channel = "morning"), daysAgo = it.toDouble()) } +
            (4..9).map { watched(video(it, channel = "evening"), daysAgo = it + 0.5) }
        val candidates = listOf(
            Candidate(video(50, channel = "evening"), NewUpload, 0),
            Candidate(video(51, channel = "morning"), NewUpload, 0),
        )
        assertEquals(listOf(id(51), id(50)), ids(rankCandidates(candidates, history, GuessLog(), NOW, utc)))
    }

    @Test
    fun `a kind of guess that keeps missing goes down`() {
        val candidates = listOf(Candidate(video(50), ForYou, 0), Candidate(video(51), NewUpload, 0))
        assertEquals(id(50), ids(rankCandidates(candidates, emptyList(), GuessLog(), NOW, utc)).first()) // listed first
        val missed = GuessLog(guesses = (1..10).map { guess(100 + it, reason = ForYou, channel = "other") })
        assertEquals(id(51), ids(rankCandidates(candidates, emptyList(), missed, NOW, utc)).first())
    }

    @Test
    fun `so does a channel whose guesses keep missing`() {
        val a = video(50, channel = "a")
        val b = video(51, channel = "b")
        val candidates = listOf(Candidate(a, ForYou, 0), Candidate(b, ForYou, 1))
        val missedA = GuessLog(guesses = (1..10).map { guess(100 + it, channel = channelUrl("a")) })
        assertEquals(id(51), ids(rankCandidates(candidates, emptyList(), missedA, NOW, utc)).first())
    }

    @Test
    fun `finished videos aren't guessed, and each video only once`() {
        val history = listOf(watched(video(1), fraction = 1.0))
        val candidates = listOf(Candidate(video(1), ForYou, 0), Candidate(video(2), ForYou, 1), Candidate(video(2), NewUpload, 0))
        assertEquals(listOf(id(2)), ids(rankCandidates(candidates, history, GuessLog(), NOW, utc)))
    }

    // --- How many are fetched -----------------------------------------------------------

    private fun round(n: Int, rank: Int, used: Boolean, at: Long = NOW - hour) = guess(n, at = at, rank = rank, used = used)

    @Test
    fun `it fetches one to begin with, and more as guesses are opened`() {
        assertEquals(1, fetchCount(GuessLog(), NOW))
        val log = GuessLog(
            guesses = (1..10).map { round(it, rank = 1, used = it <= 5) } + // 50% opened
                (11..20).map { round(it, rank = 2, used = it <= 13) } + // 30%
                (21..30).map { round(it, rank = 3, used = it <= 21) }, // 10%
        )
        assertEquals(2, fetchCount(log, NOW))
    }

    @Test
    fun `when its guesses keep missing it stops fetching, and starts again once they'd be opened`() {
        val missing = (1..20).map { round(it, rank = 1, used = it == 1, at = NOW - 3 * hour + it * minute) } // 1 in 20
        assertEquals(0, fetchCount(GuessLog(guesses = missing), NOW))
        // It still guesses, without fetching, and those guesses are checked too.
        val newer = (21..30).map { guess(it, at = NOW - 2 * hour + it * minute, fetched = false, used = it % 2 == 0) }
        assertEquals(1, fetchCount(GuessLog(guesses = missing + newer), NOW)) // 5 of the last 20 opened
    }

    @Test
    fun `guesses still within their half hour don't count yet`() {
        val pending = (1..20).map { round(it, rank = 1, used = false, at = NOW - 10 * minute) }
        assertEquals(1, fetchCount(GuessLog(guesses = pending), NOW)) // too early to tell
    }

    @Test
    fun `nothing is fetched while paused`() {
        assertEquals(0, fetchCount(GuessLog(pausedUntil = NOW + hour), NOW))
        assertEquals(1, fetchCount(GuessLog(pausedUntil = NOW - 1), NOW))
    }

    // --- Keeping score ------------------------------------------------------------------

    @Test
    fun `a guess opened within its half hour was right, and ready if it was fetched`() {
        val log = GuessLog().guessed(listOf(guess(1, at = NOW), guess(2, at = NOW, rank = 2, fetched = false)), NOW)
        val first = log.opened(id(1), NOW + 10 * minute)
        assertTrue(first.guesses[0].used)
        assertTrue(first.opens.single().ready)
        val second = first.opened(id(2), NOW + 20 * minute)
        assertTrue(second.guesses[1].used) // right, though only noted
        assertFalse(second.opens.last().ready)
    }

    @Test
    fun `opened later than that, it was a miss`() {
        val log = GuessLog().guessed(listOf(guess(1, at = NOW)), NOW).opened(id(1), NOW + 31 * minute)
        assertFalse(log.guesses.single().used)
        assertFalse(log.opens.single().ready)
    }

    @Test
    fun `a guess that couldn't be fetched isn't ready`() {
        val g = guess(1, at = NOW)
        val log = GuessLog().guessed(listOf(g), NOW).notFetched(g).opened(id(1), NOW + minute)
        assertTrue(log.guesses.single().used)
        assertFalse(log.opens.single().ready)
    }

    @Test
    fun `how long the details took is noted on the video opened`() {
        val log = GuessLog().opened(id(1), NOW).opened(id(2), NOW + minute).started(id(1), 1_234, NOW + 2 * minute)
        assertEquals(listOf(1_234L, -1L), log.opens.map { it.detailsMs })
    }

    @Test
    fun `the numbers for the Analytics tab`() {
        val opens = listOf(
            Opened(id(1), NOW - hour, ready = true, detailsMs = 100),
            Opened(id(2), NOW - hour, ready = true, detailsMs = 200),
            Opened(id(3), NOW - hour, ready = false, detailsMs = 1_200),
            Opened(id(4), NOW - hour, ready = false, detailsMs = 1_400),
            Opened(id(5), NOW - hour, ready = false, detailsMs = 1_000),
            Opened(id(6), NOW - 40 * DAY_MS, ready = false, detailsMs = 9_000), // too long ago
        )
        val guesses = listOf(
            guess(1, reason = ForYou, used = true), guess(7, reason = ForYou), guess(8, reason = ForYou),
            guess(2, reason = Unfinished, used = true), guess(9, reason = Unfinished, used = true),
            guess(10, reason = WatchLater, fetched = false), // only noted
            guess(11, reason = NewUpload, at = NOW - minute), // not settled yet
        )
        val s = guessStats(GuessLog(guesses, opens), NOW)
        assertEquals(5, s.opened)
        assertEquals(2, s.ready)
        assertEquals((1_200L - 100) + (1_200L - 200), s.savedMs) // against the others' median
        assertEquals(5, s.fetched)
        assertEquals(3, s.used)
        assertEquals(listOf(ReasonScore(ForYou, 3, 1), ReasonScore(Unfinished, 2, 2)), s.byReason)
    }

    @Test
    fun `the log reads back as it was written, and a bad record is left out`() {
        val log = GuessLog(
            guesses = listOf(guess(1, channel = channelUrl("a"), used = true), guess(2, reason = Unfinished, rank = 2, fetched = false)),
            opens = listOf(Opened(id(1), NOW, ready = true, detailsMs = 120), Opened(id(2), NOW, ready = false)),
            pausedUntil = NOW + hour,
        )
        assertEquals(log, guessLogFromJson(log.toJson()))
        val damaged = log.toJson().replace("\"Unfinished\"", "\"NoSuchReason\"")
        assertEquals(1, guessLogFromJson(damaged).guesses.size)
    }
}
