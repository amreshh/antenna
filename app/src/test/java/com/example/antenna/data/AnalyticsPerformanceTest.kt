package com.example.antenna.data

import com.example.antenna.data.StatsFilter.Kind
import com.example.antenna.data.TestData.NOW
import com.example.antenna.data.TestData.channelUrl
import com.example.antenna.data.TestData.video
import com.example.antenna.data.TestData.watched
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Analytics tab is worked out again whenever the history changes, which is every few
 * seconds while a video plays, and once more for each card something is picked in. With the
 * most history the app keeps (1000 videos, each with its title, tags and category), a year of
 * the watch log, 100 subscriptions and two picks, it has to stay quick. Like the For you
 * timing test, the budget is generous: it's here to catch something going quadratic, not
 * milliseconds.
 */
class AnalyticsPerformanceTest {

    @Test
    fun `the Analytics tab works out quickly from a full history, with picks`() {
        val entries = (1..1000).map { n ->
            watched(video(n, channel = "c${n % 60}").copy(title = "Rust and Linux gaming, part $n of topic${n % 50}"), fraction = (n % 10) / 10.0, daysAgo = n / 20.0)
                .copy(
                    category = "Category ${n % 12}",
                    tags = (1..WatchHistoryStore.MAX_TAGS).map { "tag${(n + it) % 40}" },
                    skipped = if (n % 3 == 0) listOf(SkippedSegment("sponsor", 60_000, 45_000)) else emptyList(),
                )
        }
        val filters = listOf(StatsFilter(Kind.Channel, channelUrl("c1"), "c1"), StatsFilter(Kind.Topic, "rust", "Rust"))
        // A year of the watch log for a heavy viewer: about 15 stretches a day.
        val spans = (1..5_500).map { n ->
            WatchSpan(TestData.id(n % 1000 + 1), channelUrl("c${n % 60}"), NOW - n * 1_500_000L, 0, 300_000, OpenSource.entries[n % OpenSource.entries.size])
        }
        val subscriptions = (1..100).map { Channel(channelUrl("c$it"), "c$it") }
        fun run() = analytics(entries, filters, NOW, subscriptions = subscriptions, spans = spans)

        repeat(3) { run() } // warm up the JIT
        val runs = 10
        val start = System.nanoTime()
        repeat(runs) { run() }
        val averageMs = (System.nanoTime() - start) / 1e6 / runs

        println("Analytics with a full history and two picks: %.1f ms on average".format(averageMs))
        assertTrue("Working out Analytics took %.0f ms (budget %d ms)".format(averageMs, BUDGET_MS), averageMs < BUDGET_MS)
    }

    private companion object {
        const val BUDGET_MS = 500
    }
}
