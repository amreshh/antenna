package com.antenna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.UnknownHostException

/** Counts ("1.2M views") and the error messages people see. */
class TextTest {

    @Test
    fun `counts are shortened like YouTube's`() {
        assertEquals("", formatCount(-1, "view"))
        assertEquals("0 views", formatCount(0, "view"))
        assertEquals("1 view", formatCount(1, "view"))
        assertEquals("999 views", formatCount(999, "view"))
        assertEquals("1.2K views", formatCount(1_234, "view"))
        assertEquals("12K views", formatCount(12_345, "view"))
        assertEquals("4.6M subscribers", formatCount(4_600_000, "subscriber"))
        assertEquals("25M subscribers", formatCount(25_400_000, "subscriber"))
        assertEquals("1.8B views", formatCount(1_800_000_000, "view"))
    }

    @Test
    fun `whole numbers drop the decimal`() {
        assertEquals("1K views", formatCount(1_000, "view"))
        assertEquals("2M views", formatCount(2_000_000, "view"))
    }

    @Test
    fun `playback speeds read like YouTube's`() {
        assertEquals("Normal", speedLabel(1f))
        assertEquals("0.25×", speedLabel(0.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2×", speedLabel(2f))
    }

    @Test
    fun `errors read as what went wrong`() {
        assertEquals(
            "YouTube is limiting requests from this network. Wait a while, or switch networks.",
            ReCaptchaException("captcha", "https://www.youtube.com").readable(),
        )
        assertEquals(
            "This video is age-restricted, which needs a signed-in account.",
            AgeRestrictedContentException("age").readable(),
        )
        assertEquals(
            "Can't reach YouTube. Check your internet connection.",
            UnknownHostException("www.youtube.com").readable(),
        )
    }

    @Test
    fun `YouTube's own reason is quoted for unavailable videos`() {
        assertEquals(
            "This video has been removed by the uploader",
            ContentNotAvailableException("Got error: \"This video has been removed by the uploader\"").readable(),
        )
        assertEquals("This video isn't available.", ContentNotAvailableException("no reason").readable())
    }
}
