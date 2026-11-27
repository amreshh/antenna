package com.antenna.ui

import com.antenna.data.TestData.video
import com.antenna.data.VideosPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Page

/** A channel's Videos tab, loaded a page at a time for the channel picked in Subscriptions. */
class ChannelVideosTest {

    private fun page(vararg n: Int, next: String? = "next") = VideosPage(n.map { video(it) }, next?.let(::Page))

    private fun ChannelVideos.numbers() = videos.map { it.title.removePrefix("Video ").toInt() }

    @Test
    fun `before its first page, there's more to load`() {
        assertTrue(ChannelVideos().hasMore)
    }

    @Test
    fun `pages add up, newest first, until the last`() {
        val first = ChannelVideos().plus(page(1, 2, 3), first = true)
        assertTrue(first.loaded && first.hasMore)
        val all = first.plus(page(3, 4, 5, next = null), first = false)
        assertEquals(listOf(1, 2, 3, 4, 5), all.numbers())
        assertFalse(all.hasMore)
    }

    @Test
    fun `the first page loaded again goes in front, still going as far back`() {
        val two = ChannelVideos().plus(page(2, 3), first = true).plus(page(4, 5), first = false)
        val again = two.plus(page(1, 2, next = "another"), first = true)
        assertEquals(listOf(1, 2, 3, 4, 5), again.numbers())
        assertEquals(two.next, again.next)
    }

    @Test
    fun `a page that adds nothing is the end`() {
        val one = ChannelVideos().plus(page(1, 2), first = true)
        assertFalse(one.plus(page(1, 2), first = false).hasMore)
    }

    @Test
    fun `a channel with no videos on its tab has none to load`() {
        assertFalse(ChannelVideos().plus(page(next = null), first = true).hasMore)
    }
}
