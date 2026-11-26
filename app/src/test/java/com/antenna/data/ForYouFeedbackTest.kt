package com.antenna.data

import com.antenna.data.TestData.channelUrl
import com.antenna.data.TestData.id
import com.antenna.data.TestData.video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What For you learns besides what you watch: Not interested, hidden channels, and what it showed you. */
class ForYouFeedbackTest {

    // --- Hidden channels -----------------------------------------------------------------

    @Test
    fun `a hidden channel is matched by its link`() {
        val said = ForYouFeedback(hiddenChannels = listOf(HiddenChannel(channelUrl("loud"), "Loud", 1)))
        assertTrue(said.hides(id(1), video(1, channel = "loud")))
        assertFalse(said.hides(id(2), video(2, channel = "quiet")))
    }

    @Test
    fun `another channel with the same name isn't hidden, but a video saved without a link goes by name`() {
        val said = ForYouFeedback(hiddenChannels = listOf(HiddenChannel(channelUrl("loud"), "News", 1)))
        assertFalse(said.hides(id(1), video(1, channel = "other").copy(uploader = "News")))
        assertTrue(said.hides(id(2), video(2, withChannelUrl = false).copy(uploader = "News")))
    }

    @Test
    fun `a channel hidden without a link hides its videos by name`() {
        val said = ForYouFeedback(hiddenChannels = listOf(HiddenChannel(null, "loud", 1)))
        assertTrue(said.hides(id(1), video(1, channel = "loud")))
        assertFalse(said.hides(id(2), video(2, channel = "quiet")))
    }

    @Test
    fun `a video without a channel name isn't hidden by a channel without one`() {
        val said = ForYouFeedback(hiddenChannels = listOf(HiddenChannel(channelUrl("x"), "", 1)))
        assertFalse(said.hides(id(1), video(1, withChannelUrl = false).copy(uploader = "")))
    }

    @Test
    fun `not interested and hidden channels read back as they were written`() {
        val videos = listOf(NotInterested("v1", channelUrl("a"), "a", 5, "A title"), NotInterested("v2", null, "old", 6))
        assertEquals(videos, notInterestedFromJson(notInterestedToJson(videos)))
        val channels = listOf(HiddenChannel(channelUrl("a"), "a", 7), HiddenChannel(null, "old", 8))
        assertEquals(channels, hiddenChannelsFromJson(hiddenChannelsToJson(channels)))
    }

    @Test
    fun `a damaged list reads as empty, and a damaged entry is left out`() {
        assertEquals(emptyList<NotInterested>(), notInterestedFromJson("not json"))
        assertEquals(listOf(HiddenChannel(null, "a", 1)), hiddenChannelsFromJson("""[{"name":"a","at":1},{"url":null}]"""))
    }

    // --- Shown -------------------------------------------------------------------------------

    @Test
    fun `a video counts once a day, over the last 30 days`() {
        var days: Map<String, List<Int>> = emptyMap()
        days = days.withShown("a", 100)!!
        assertNull(days.withShown("a", 100)) // again the same day: no change
        days = days.withShown("a", 101)!!
        days = days.withShown("b", 101)!!
        assertEquals(mapOf("a" to 2, "b" to 1), days.shownCounts(101))
        assertEquals(mapOf("a" to 1, "b" to 1), days.shownCounts(130)) // day 100 is 30 days back
        assertEquals(emptyMap<String, Int>(), days.shownCounts(131))
    }

    @Test
    fun `days over a month back are dropped`() {
        val days = mapOf("a" to listOf(10, 50), "b" to listOf(10))
        assertEquals(mapOf("a" to listOf(50)), days.recent(60))
        assertEquals(listOf(50, 75), days.withShown("a", 75)!!.getValue("a"))
    }

    @Test
    fun `what was shown reads back as it was written`() {
        val days = mapOf("a" to listOf(1, 2), "b" to listOf(3))
        assertEquals(days, shownFromJson(shownToJson(days)))
    }
}
