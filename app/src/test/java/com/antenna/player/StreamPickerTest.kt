package com.antenna.player

import com.antenna.player.StreamPicker.DecoderFit
import com.antenna.player.StreamPicker.Decoding
import com.antenna.player.StreamPicker.Option
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of a video's streams the player picks (StreamPicker.order). */
class StreamPickerTest {

    private fun option(side: Int, codec: String, mbps: Double?, decoding: Decoding = Decoding.HARDWARE, fps: Int = 30) =
        Option(side, fps, decoding, mbps?.let { (it * 1e6).toLong() }, codec)

    private fun pick(vararg options: Option, preferred: Int? = null) = options.minWith(StreamPicker.order(preferred))

    @Test
    fun `at equal quality the smallest file, whatever its codec`() {
        // Two real 1080p60 videos' files (size over length): YouTube's VP9 bigger than its
        // H.264 in one, smaller in the other.
        val avc = option(1080, "avc1.64002A", 1.5, fps = 60)
        val vp9 = option(1080, "vp9", 1.8, fps = 60)
        assertEquals(avc, pick(avc, vp9))
        val avc2 = option(1080, "avc1.64002A", 3.1, fps = 60)
        val vp92 = option(1080, "vp9", 3.0, fps = 60)
        assertEquals(vp92, pick(avc2, vp92))
        // AV1 when it's decoded in hardware; decoded in software, it would stutter.
        val av1 = option(1080, "av01.0.09M.08", 0.6, fps = 60)
        assertEquals(av1, pick(avc, vp9, av1))
        assertEquals(avc, pick(avc, vp9, av1.copy(decoding = Decoding.SOFTWARE)))
    }

    @Test
    fun `hardware decoding comes before a better quality`() {
        val inSoftware = option(1440, "vp9", 9.0, Decoding.SOFTWARE)
        val inHardware = option(1080, "avc1.640028", 4.0)
        assertEquals(inHardware, pick(inSoftware, inHardware))
    }

    @Test
    fun `the best quality, and the most frames per second, before a smaller file`() {
        val p720 = option(720, "avc1.4d401f", 1.5)
        val p1080 = option(1080, "avc1.640028", 3.0)
        assertEquals(p1080, pick(p720, p1080))
        val fps30 = option(1080, "av01.0.08M.08", 1.5, fps = 30)
        val fps60 = option(1080, "avc1.64002A", 4.0, fps = 60)
        assertEquals(fps60, pick(fps30, fps60))
    }

    @Test
    fun `a decoder counts only if it says it can play the stream's size, frame rate and profile`() {
        val hardware = DecoderFit(hardware = true, fits = true)
        val software = DecoderFit(hardware = false, fits = true)
        assertEquals(Decoding.HARDWARE, StreamPicker.decodingOf(listOf(hardware, software)))
        // A hardware decoder that tops out at 1080p, for 2160p60.
        assertEquals(Decoding.SOFTWARE, StreamPicker.decodingOf(listOf(hardware.copy(fits = false), software)))
        assertEquals(Decoding.UNSUPPORTED, StreamPicker.decodingOf(listOf(hardware.copy(fits = false), software.copy(fits = false))))
        assertEquals(Decoding.NONE, StreamPicker.decodingOf(emptyList()))
    }

    @Test
    fun `a stream no decoder says it can play comes last`() {
        val av1 = option(2160, "av01.0.12M.08", 8.0, Decoding.UNSUPPORTED, fps = 60)
        val inSoftware = option(1440, "vp9", 9.0, Decoding.SOFTWARE)
        val inHardware = option(1080, "avc1.640028", 4.0)
        assertEquals(inHardware, pick(av1, inSoftware, inHardware))
        assertEquals(inSoftware, pick(av1, inSoftware))
        // Also at a quality picked in the menu.
        val vp9 = option(2160, "vp9", 18.0, Decoding.SOFTWARE, fps = 60)
        assertEquals(vp9, pick(av1, vp9, inHardware, preferred = 2160))
    }

    @Test
    fun `the quality menu offers the qualities a decoder says it can play`() {
        val separate = listOf(
            2160 to Decoding.UNSUPPORTED, 1440 to Decoding.SOFTWARE, 1080 to Decoding.UNSUPPORTED,
            1080 to Decoding.HARDWARE, 720 to Decoding.HARDWARE, 4320 to Decoding.NONE,
        )
        assertEquals(listOf(1440, 1080, 720), StreamPicker.menuQualities(separate, muxed = listOf(360)))
        // If no decoder says so, those with a decoder at all (what the player then picks from),
        // and if none has one, the muxed streams'.
        val unsure = listOf(2160 to Decoding.UNSUPPORTED, 1080 to Decoding.UNSUPPORTED, 720 to Decoding.NONE)
        assertEquals(listOf(2160, 1080), StreamPicker.menuQualities(unsure, muxed = listOf(360)))
        assertEquals(listOf(360), StreamPicker.menuQualities(listOf(1080 to Decoding.NONE), muxed = listOf(360, 0)))
    }

    @Test
    fun `a quality picked in the menu comes first`() {
        val p1080 = option(1080, "avc1.640028", 3.0)
        val p480 = option(480, "vp9", 0.8, Decoding.SOFTWARE)
        assertEquals(p480, pick(p1080, p480, preferred = 480))
    }

    @Test
    fun `streams of unknown size go by codec, after those of known size`() {
        val avc = option(1080, "avc1.640028", null)
        val vp9 = option(1080, "vp09.00.40.08", null)
        val av1 = option(1080, "av01.0.08M.08", null)
        assertEquals(av1, pick(avc, vp9, av1))
        assertEquals(vp9, pick(avc, vp9))
        val known = option(1080, "avc1.640028", 3.0)
        assertEquals(known, pick(av1, known))
    }

    @Test
    fun `bits per second come from the file's size and length, or else YouTube's average`() {
        assertEquals(4_000_000L, StreamPicker.bitsPerSecond(contentLength = 300_000_000, durationMs = 600_000, averageBitrate = 1))
        assertEquals(2_500_000L, StreamPicker.bitsPerSecond(contentLength = 0, durationMs = 600_000, averageBitrate = 2_500_000))
        assertNull(StreamPicker.bitsPerSecond(contentLength = 0, durationMs = 0, averageBitrate = 0))
    }
}
