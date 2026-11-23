package com.example.antenna.data

import com.example.antenna.data.TestData.DAY_MS
import com.example.antenna.data.TestData.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.time.ZoneOffset

/** Exporting subscriptions as Takeout's CSV, and choosing what the cleanup removes. */
class SubscriptionToolsTest {

    private fun channel(id: String, name: String) = Channel("https://www.youtube.com/channel/$id", name)

    private val rick = channel("UCuAXFkgsw1L7xaCfnd5JJOw", "Rick Astley")
    private val kurz = channel("UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt, In a Nutshell")
    private val quoted = channel("UCXuqSBlHAE6Xw-yeJA0Tunw", "Say \"hi\"")

    // --- Export ------------------------------------------------------------------------

    @Test
    fun `export is written exactly as Takeout writes it`() {
        assertEquals(
            "Channel Id,Channel Url,Channel Title\n" +
                "UCuAXFkgsw1L7xaCfnd5JJOw,http://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw,Rick Astley\n" +
                "UCsXVk37bltHxD1rDPwtNM8Q,http://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q,\"Kurzgesagt, In a Nutshell\"\n" +
                "UCXuqSBlHAE6Xw-yeJA0Tunw,http://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw,\"Say \"\"hi\"\"\"\n",
            exportTakeoutCsv(listOf(rick, kurz, quoted)),
        )
    }

    @Test
    fun `an exported file imports back to the same channels`() {
        val channels = listOf(rick, kurz, quoted)
        assertEquals(channels, parseTakeoutCsv(exportTakeoutCsv(channels)))
    }

    @Test
    fun `line breaks in a name can't break the file`() {
        val csv = exportTakeoutCsv(listOf(channel("UCuAXFkgsw1L7xaCfnd5JJOw", "Two\nlines")))
        assertEquals(2, csv.lines().count { it.isNotEmpty() })
        assertEquals("Two lines", parseTakeoutCsv(csv).single().name)
    }

    // --- Cleanup -----------------------------------------------------------------------

    private val gone = channel("UCaaaaaaaaaaaaaaaaaaaaaa", "Gone")
    private val quiet = channel("UCbbbbbbbbbbbbbbbbbbbbbb", "Quiet")
    private val never = channel("UCcccccccccccccccccccccc", "Never")
    private val active = channel("UCdddddddddddddddddddddd", "Active")
    private val unchecked = channel("UCeeeeeeeeeeeeeeeeeeeeee", "Unchecked")
    private val all = listOf(gone, quiet, never, active, unchecked)
    private val checks = mapOf(
        gone.url to ChannelCheck.Gone("This channel does not exist."),
        quiet.url to ChannelCheck.Uploads(NOW - 400 * DAY_MS),
        never.url to ChannelCheck.Uploads(null),
        active.url to ChannelCheck.Uploads(NOW - 10 * DAY_MS),
        unchecked.url to ChannelCheck.Unknown("timeout"),
    )

    private fun pick(removeGone: Boolean, months: Int?) =
        SubscriptionCleanup.candidates(all, checks, removeGone, months, NOW, ZoneOffset.UTC).associate { it.channel.name to it.reason }

    @Test
    fun `gone channels, and ones with no upload in the period, with their reasons`() {
        assertEquals(
            mapOf(
                "Gone" to CleanupReason.Gone("This channel does not exist."),
                "Quiet" to CleanupReason.Inactive(NOW - 400 * DAY_MS),
                "Never" to CleanupReason.NoUploads,
            ),
            pick(removeGone = true, months = 12),
        )
    }

    @Test
    fun `a longer period keeps channels that uploaded within it`() {
        assertEquals(setOf("Gone", "Never"), pick(removeGone = true, months = 24).keys)
    }

    @Test
    fun `each reason can be left out`() {
        assertEquals(setOf("Quiet", "Never"), pick(removeGone = false, months = 12).keys)
        assertEquals(setOf("Gone"), pick(removeGone = true, months = null).keys)
    }

    @Test
    fun `channels that couldn't be checked are never removed`() {
        assertFalse("Unchecked" in pick(removeGone = true, months = 1).keys)
    }

    @Test
    fun `only a channel YouTube says doesn't exist, or was terminated, counts as gone`() {
        assertTrue(SubscriptionCleanup.isGone(ContentNotAvailableException("This channel does not exist.")))
        assertTrue(SubscriptionCleanup.isGone(AccountTerminatedException("This account has been terminated.")))
        // Also "not available" errors, but about the viewer, not the channel:
        assertFalse(SubscriptionCleanup.isGone(GeographicRestrictionException("Not available in your country")))
        assertFalse(SubscriptionCleanup.isGone(AgeRestrictedContentException("Age-restricted")))
        // And YouTube or the network not answering:
        assertFalse(SubscriptionCleanup.isGone(ReCaptchaException("captcha", "https://www.youtube.com")))
        assertFalse(SubscriptionCleanup.isGone(IOException("timeout")))
    }
}
