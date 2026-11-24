package com.antenna.data

import com.antenna.data.ChannelTopics.OTHER
import com.antenna.data.ChannelTopics.topicOf
import com.antenna.data.TestData.video
import com.antenna.data.TestData.watched
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guessing a channel's topic for the Subscriptions tab's "Topic" order. */
class ChannelTopicsTest {

    @Test
    fun `recent titles point at a topic`() {
        assertEquals(
            "Science & Technology",
            topicOf(listOf("How to Start 3D Printing", "GTA 6 on PS5 vs Xbox", "AMD's new GPU is here", "Linux on a Pixel"), emptyList()),
        )
        assertEquals(
            "News & Politics",
            topicOf(listOf("JD Vance PANICS As ALL PLANS COLLAPSE", "Senate votes on tariffs", "Trump's latest scandal"), emptyList()),
        )
    }

    @Test
    fun `one stray title isn't enough`() {
        assertEquals(OTHER, topicOf(listOf("My morning routine", "Minecraft is back", "Talking to my neighbour"), emptyList()))
    }

    @Test
    fun `with only a video or two, one match will do`() {
        assertEquals("Gaming", topicOf(listOf("Elden Ring boss fight"), emptyList()))
    }

    @Test
    fun `YouTube's category of watched videos counts most`() {
        // Two titles say Music, but the one video watched from it is Education.
        assertEquals("Education", topicOf(listOf("Greatest hits", "Concert tonight"), listOf("Education")))
    }

    @Test
    fun `the channel's name counts more than one video`() {
        // A tech channel's recent run of game console comparisons.
        val titles = listOf("GTA 6 on PS5 vs Xbox Series X", "GTA 6 on PS5 Pro vs Xbox Series S", "GTA 6 on Xbox X vs S", "How to Start 3D Printing", "Tech Russian Roulette")
        assertEquals("Gaming", topicOf(titles, emptyList()))
        assertEquals("Science & Technology", topicOf(titles, emptyList(), name = "Linus Tech Tips"))
    }

    @Test
    fun `possessives match their word`() {
        assertEquals("Science & Technology", topicOf(listOf("AMD's comeback"), emptyList()))
    }

    @Test
    fun `whole words only`() {
        // "ai" in "said", "pc" in "epc", "car" in "card": none of them count.
        assertEquals(OTHER, topicOf(listOf("He said what?", "The EPC card trick", "Scared of the dark"), emptyList()))
    }

    @Test
    fun `every channel gets a topic, from the feed and the history`() {
        val tech = Channel(TestData.channelUrl("tech"), "Tech")
        val music = Channel(TestData.channelUrl("music"), "Music")
        val quiet = Channel(TestData.channelUrl("quiet"), "Quiet")
        val feed = listOf(
            video(1, channel = "tech").copy(title = "Nvidia RTX review"),
            video(2, channel = "tech").copy(title = "Building a PC"),
        )
        val history = listOf(watched(video(3, channel = "music")).copy(category = "Music"))
        assertEquals(
            mapOf(tech.url to "Science & Technology", music.url to "Music", quiet.url to OTHER),
            ChannelTopics.of(listOf(tech, music, quiet), feed, history),
        )
    }
}
