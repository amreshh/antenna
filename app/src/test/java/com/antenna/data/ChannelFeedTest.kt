package com.antenna.data

import com.antenna.data.TestData.DAY_MS
import com.antenna.data.TestData.NOW
import com.antenna.data.TestData.id
import com.antenna.data.TestData.video
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A channel picked in the Subscriptions tab: its videos in the feed (its RSS, the last 15
 * uploads, Shorts among them) and further back, from its Videos tab ([channelFeed]).
 */
class ChannelFeedTest {

    /** As the feed has it: an exact time, no length. */
    private fun fromFeed(n: Int, daysAgo: Double, short: Boolean = false) =
        video(n, channel = "a", uploadedAtMs = NOW - (daysAgo * DAY_MS).toLong()).let { v ->
            v.copy(durationSeconds = -1, url = if (short) "https://www.youtube.com/shorts/${id(n)}" else v.url)
        }

    /** As the Videos tab has it: a length, and a rough time ("2 weeks ago": midnight 14 days ago). */
    private fun fromTab(n: Int, roughDaysAgo: Double) =
        video(n, channel = "a", uploadedAtMs = NOW - (roughDaysAgo * DAY_MS).toLong()).copy(durationSeconds = 100L + n)

    private fun List<VideoItem>.ids() = map { videoIdOf(it.url) }

    @Test
    fun `the tab's older videos come after the feed's, which get the tab's lengths`() {
        // Like XDA's: the feed has two videos among its Shorts, the tab those and older ones.
        val feed = listOf(fromFeed(1, 5.0), fromFeed(2, 6.0, short = true), fromFeed(3, 17.0))
        val tab = listOf(fromTab(1, 5.0), fromTab(3, 14.0), fromTab(4, 21.0), fromTab(5, 30.0))
        val merged = channelFeed(feed, tab)
        assertEquals(listOf(1, 2, 3, 4, 5).map(::id), merged.ids())
        assertEquals(listOf(101L, -1L, 103L, 104L, 105L), merged.map { it.durationSeconds }) // a Short has none
        assertEquals(feed[2].uploadedAtMs, merged[2].uploadedAtMs) // the feed's exact time, not "2 weeks ago"
    }

    @Test
    fun `a rough time doesn't put an older video above a newer one`() {
        // 3 came out 15 days ago; 4, only on the tab, 16 days ago: "2 weeks ago", taken as 14.
        val feed = listOf(fromFeed(3, 15.0))
        val tab = listOf(fromTab(3, 14.0), fromTab(4, 14.0))
        assertEquals(listOf(id(3), id(4)), channelFeed(feed, tab).ids())
    }

    @Test
    fun `nor a newer one below an older one`() {
        // 5 came out since the feed loaded, 19 hours ago, "1 day ago"; 2 came out 20 hours ago.
        val feed = listOf(fromFeed(2, 20 / 24.0))
        val tab = listOf(fromTab(5, 1.0), fromTab(2, 0.0))
        assertEquals(listOf(id(5), id(2)), channelFeed(feed, tab).ids())
    }

    @Test
    fun `a video of unknown time keeps its place on the tab`() {
        val feed = listOf(fromFeed(1, 1.0), fromFeed(3, 3.0))
        val unknown = { n: Int -> fromTab(n, 0.0).copy(uploadedAtMs = 0) }
        val tab = listOf(fromTab(1, 1.0), unknown(2), fromTab(3, 3.0), unknown(4))
        assertEquals(listOf(1, 2, 3, 4).map(::id), channelFeed(feed, tab).ids())
    }

    @Test
    fun `with no tab loaded, it's the feed`() {
        val feed = listOf(fromFeed(1, 1.0), fromFeed(2, 2.0, short = true))
        assertEquals(feed, channelFeed(feed, emptyList()))
    }
}
