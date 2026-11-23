package com.example.antenna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Video and channel links from anywhere, and the Google Takeout subscriptions import. */
class LinksAndImportTest {

    @Test
    fun `video ids come out of every kind of YouTube link`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&t=42s",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube.com/embed/$id",
        ).forEach { assertEquals(it, id, videoIdOf(it)) }
    }

    @Test
    fun `links that aren't videos have no id`() {
        listOf("https://example.com/", "not a link", "https://www.youtube.com/@somebody").forEach {
            assertNull(it, videoIdOf(it))
        }
    }

    @Test
    fun `watch links are rebuilt from the id`() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", watchUrl("dQw4w9WgXcQ"))
    }

    @Test
    fun `channel links become one canonical form`() {
        val canonical = "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"
        listOf(
            canonical,
            "https://m.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw/videos",
            "http://youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw?view=0",
        ).forEach { assertEquals(it, canonical, normalizeChannelUrl(it)) }
    }

    @Test
    fun `channel links without a UC id can't be used`() {
        // The RSS feed needs the id, which a handle doesn't give without a request.
        assertNull(normalizeChannelUrl("https://www.youtube.com/@somebody"))
        assertNull(normalizeChannelUrl("https://example.com/channel/whatever"))
    }

    @Test
    fun `Takeout subscriptions are read, header and junk skipped`() {
        val csv = "﻿Channel Id,Channel Url,Channel Title\n" +
            "UCuAXFkgsw1L7xaCfnd5JJOw,http://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw,Rick Astley\n" +
            "UCsXVk37bltHxD1rDPwtNM8Q,http://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q,\"Kurzgesagt, In a Nutshell\"\n" +
            "\n" +
            "not-an-id,http://example.com,Nobody\n" +
            "UCXuqSBlHAE6Xw-yeJA0Tunw,http://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw,\"Say \"\"hi\"\"\"\n"
        val channels = parseTakeoutCsv(csv)
        assertEquals(
            listOf("Rick Astley", "Kurzgesagt, In a Nutshell", "Say \"hi\""),
            channels.map { it.name },
        )
        assertEquals("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw", channels.first().url)
    }

    @Test
    fun `a Takeout channel with no title is named after its id`() {
        val channels = parseTakeoutCsv("UCuAXFkgsw1L7xaCfnd5JJOw,http://x,\n")
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", channels.single().name)
    }
}
