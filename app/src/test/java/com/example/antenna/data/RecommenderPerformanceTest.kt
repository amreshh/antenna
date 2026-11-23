package com.example.antenna.data

import com.example.antenna.data.TestData.NOW
import com.example.antenna.data.TestData.video
import com.example.antenna.data.TestData.watched
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The For you tab is rebuilt whenever the history changes, which is every few seconds while
 * a video plays. With the most history the app keeps (1000 videos, 150 of them with a
 * 20-video up next list) building it has to stay quick. The budget is generous so a slow CI
 * runner doesn't fail it: it's here to catch something going quadratic, not milliseconds.
 */
class RecommenderPerformanceTest {

    @Test
    fun `For you builds quickly from a full history`() {
        val entries = (1..1000).map { n ->
            val related = if (n <= 150) (1..20).map { j -> video(10_000 + n * 20 + j, channel = "c${j % 40}") } else emptyList()
            watched(video(n, channel = "c${n % 60}"), fraction = (n % 10) / 10.0, daysAgo = n / 10.0, related = related)
        }
        val history = entries.associateBy { videoIdOf(it.video.url)!! }
        val uploads = (1..64).map { video(50_000 + it, channel = "c${it % 8}", uploadedAtMs = NOW - it * 3_600_000L) }

        repeat(3) { Recommender.build(history, emptySet(), it.toLong(), uploads, NOW) } // warm up the JIT
        val runs = 10
        val start = System.nanoTime()
        repeat(runs) { Recommender.build(history, emptySet(), it.toLong(), uploads, NOW) }
        val averageMs = (System.nanoTime() - start) / 1e6 / runs

        println("Recommender.build with a full history: %.1f ms on average".format(averageMs))
        assertTrue("Building For you took %.0f ms (budget %d ms)".format(averageMs, BUDGET_MS), averageMs < BUDGET_MS)
    }

    private companion object {
        /** About 10-30 ms on a laptop; CI runners are slower. */
        const val BUDGET_MS = 500
    }
}
