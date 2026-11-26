package com.antenna.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.antenna.net.DESKTOP_USER_AGENT
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a StreamInfo into something ExoPlayer can play.
 *
 * This is where "no ads" comes from: we play the raw googlevideo streams directly,
 * so YouTube's ad-serving player is never involved.
 *
 * YouTube serves its best qualities as separate video-only and audio-only files,
 * so we pick one of each and play them together with a MergingMediaSource.
 *
 * Quality means the shorter side, like YouTube's labels: a vertical 1080×1920 video is 1080p.
 */
@OptIn(UnstableApi::class)
object StreamPicker {

    /**
     * The video in the best quality up to [maxHeight] (e.g. 1080), or in exactly [preferred]
     * when it has that quality and the device can decode it (the player's quality menu).
     */
    fun build(context: Context, info: StreamInfo, maxHeight: Int, http: OkHttpClient, preferred: Int? = null): MediaSource {
        val metadata = MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(info.uploaderName)
            .build()

        // Live streams: HLS manifest, normal HTTP works fine
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            require(info.hlsUrl.isNotEmpty()) { "No HLS stream found for this live video" }
            val hlsHttp = DefaultHttpDataSource.Factory().setUserAgent(DESKTOP_USER_AGENT)
            return HlsMediaSource.Factory(hlsHttp).setLoadErrorHandlingPolicy(PatientRetryPolicy()).createMediaSource(
                MediaItem.Builder().setUri(info.hlsUrl).setMediaMetadata(metadata).build(),
            )
        }

        val progressive = ProgressiveMediaSource.Factory(YoutubeDataSource.Factory(http))
            .setLoadErrorHandlingPolicy(PatientRetryPolicy())
        fun source(stream: Stream) = progressive.createMediaSource(
            MediaItem.Builder().setUri(stream.content).setMediaMetadata(metadata).build(),
        )

        // Only streams in a codec this device has a decoder for (one it hasn't would play as
        // audio only), the best of them by [order].
        val candidates = info.videoOnlyStreams
            .filter { it.isPlayable() && it.shortSide in 1..maxOf(maxHeight, preferred ?: 0) }
            .map { it to it.option(decodingOf(context, it)) }
            .filter { (_, option) -> option.decoding != Decoding.NONE }
        val best = candidates.minWithOrNull(compareBy(order(preferred)) { it.second })
        best?.let { logChoice(it.second, candidates.map { (_, option) -> option }) }
        val video = best?.first

        val audio = info.audioStreams
            .filter { it.isPlayable() }
            .sortedWith(
                // original language track first (skip auto-dubbed tracks)
                compareBy<AudioStream> {
                    if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 0 else 1
                }
                    .thenBy { if (it.format == MediaFormat.M4A) 0 else 1 }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull()

        if (video != null && audio != null) {
            return MergingMediaSource(source(video), source(audio))
        }

        // Fallback: a muxed (video+audio in one file) stream, usually 360p
        val muxed = info.videoStreams
            .filter { it.isPlayable() }
            .maxByOrNull { it.shortSide }
            ?: error("No playable stream found")
        return source(muxed)
    }

    /**
     * The qualities the quality menu offers, best first: those this device's decoders say they
     * can play. Empty for live streams, whose quality adapts by itself.
     */
    fun qualities(context: Context, info: StreamInfo): List<Int> {
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) return emptyList()
        return menuQualities(
            separate = info.videoOnlyStreams.filter { it.isPlayable() }.map { it.shortSide to decodingOf(context, it) },
            muxed = info.videoStreams.filter { it.isPlayable() }.map { it.shortSide },
        )
    }

    /**
     * The quality menu's qualities, best first, from those of the separate video streams (with
     * how each can be decoded) and of the muxed ones. Like [build], it goes by the streams a
     * decoder says it can play; if there are none, by those with a decoder for their codec at
     * all; and if there are none of those either, by the muxed ones.
     */
    internal fun menuQualities(separate: List<Pair<Int, Decoding>>, muxed: List<Int>): List<Int> {
        val decodable = separate.filter { (_, decoding) -> decoding != Decoding.NONE }
        val supported = decodable.filter { (_, decoding) -> decoding != Decoding.UNSUPPORTED }
        return supported.ifEmpty { decodable }.map { (side, _) -> side }.ifEmpty { muxed }
            .filter { it > 0 }.distinct().sortedDescending()
    }

    /** A video stream as [order] weighs it. */
    internal data class Option(
        val shortSide: Int,
        val fps: Int,
        val decoding: Decoding,
        /** How much data it takes, from what YouTube says; null if it doesn't. */
        val bitsPerSecond: Long?,
        val codec: String?,
    )

    private fun VideoStream.option(decoding: Decoding) = Option(
        shortSide = shortSide,
        fps = fps,
        decoding = decoding,
        bitsPerSecond = itagItem?.let { bitsPerSecond(it.contentLength, it.approxDurationMs, it.averageBitrate) },
        codec = codec,
    )

    /**
     * Video streams, best first: the quality picked in the menu ([preferred]), if any; then
     * how they can be decoded ([Decoding]: 1440p in VP9 decoded in software stutters where
     * 1080p in H.264 decoded in hardware plays smoothly); then the best quality, and the most
     * frames per second; and at equal quality the smallest file. That's usually AV1, but
     * YouTube's VP9 is sometimes bigger than its H.264, so it goes by the sizes YouTube gives
     * rather than by codec.
     */
    internal fun order(preferred: Int?): Comparator<Option> =
        compareBy<Option> { if (it.shortSide == preferred) 0 else 1 }
            .thenBy { it.decoding.ordinal }
            .thenByDescending { it.shortSide }
            .thenByDescending { it.fps }
            .thenBy { it.bitsPerSecond ?: Long.MAX_VALUE }
            .thenBy { codecRank(it.codec) }

    /**
     * A stream's bits per second: its size over its length, or else YouTube's average bitrate
     * for it; null when neither is known.
     */
    internal fun bitsPerSecond(contentLength: Long, durationMs: Long, averageBitrate: Int): Long? = when {
        contentLength > 0 && durationMs > 0 -> contentLength * 8_000 / durationMs
        averageBitrate > 0 -> averageBitrate.toLong()
        else -> null
    }

    /**
     * Logs the stream picked, and what its quality would take in each codec this device can
     * decode: "Playing 1080p60 avc1.64002A at 1.5 Mb/s; at this quality: avc1.64002A (hardware)
     * 1.5 Mb/s, vp9 (hardware) 1.8 Mb/s, av01.0.09M.08 (software) 0.6 Mb/s".
     */
    private fun logChoice(picked: Option, options: List<Option>) {
        val same = options
            .filter { it.shortSide == picked.shortSide && it.fps == picked.fps }
            .joinToString { "${it.codec} (${it.decoding.name.lowercase()}) ${mbps(it.bitsPerSecond)}" }
        Log.d(TAG, "Playing ${picked.shortSide}p${picked.fps} ${picked.codec} at ${mbps(picked.bitsPerSecond)}; at this quality: $same")
    }

    private fun mbps(bitsPerSecond: Long?) =
        if (bitsPerSecond == null) "? Mb/s" else String.format(Locale.US, "%.1f Mb/s", bitsPerSecond / 1e6)

    private const val TAG = "StreamPicker"

    /** Codecs by how much data they usually take for the same picture, least first: for streams of unknown size. */
    internal fun codecRank(codec: String?): Int = when {
        codec == null -> 3
        codec.startsWith("av01") -> 0
        codec.startsWith("vp09") || codec.startsWith("vp9") -> 1
        codec.startsWith("avc") -> 2
        else -> 3
    }

    /** The shorter side: 1080 for both 1920×1080 and a vertical 1080×1920. */
    val VideoStream.shortSide: Int
        get() = if (width > 0 && height > 0) minOf(width, height) else height

    private fun Stream.isPlayable() =
        isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && content.isNotEmpty()

    /** How a stream can be decoded on this device, in order of preference. */
    internal enum class Decoding {
        /** A hardware decoder says it can play the stream. */
        HARDWARE,

        /** Only a software decoder says it can, which may stutter at high qualities. */
        SOFTWARE,

        /**
         * The device has decoders for the stream's codec, but none says it can play the stream:
         * it's bigger or has more frames per second than they take (a hardware decoder that
         * tops out at 1080p, for 2160p60), or a profile they don't have (10-bit). It's tried
         * only when nothing else can play, as decoders sometimes undersell themselves.
         */
        UNSUPPORTED,

        /** No decoder for the stream's codec: it would play as audio only. */
        NONE,
    }

    /** One of the device's decoders for a stream's codec: whether it's [hardware], and whether it says it [fits] the stream. */
    internal data class DecoderFit(val hardware: Boolean, val fits: Boolean)

    /** How a stream can be decoded, from what each of the device's decoders for its codec says. */
    internal fun decodingOf(decoders: List<DecoderFit>): Decoding = when {
        decoders.any { it.hardware && it.fits } -> Decoding.HARDWARE
        decoders.any { it.fits } -> Decoding.SOFTWARE
        decoders.isNotEmpty() -> Decoding.UNSUPPORTED
        else -> Decoding.NONE
    }

    /** A stream as the decoders see it. */
    private data class StreamShape(val codec: String?, val width: Int, val height: Int, val fps: Int)

    /**
     * What the decoders said about each [StreamShape] so far. It doesn't change while the app
     * runs, and asking again for every video would fill the log with Media3's notes on what
     * each decoder can't play.
     */
    private val decodings = ConcurrentHashMap<StreamShape, Decoding>()

    private fun decodingOf(context: Context, stream: VideoStream): Decoding =
        decodings.getOrPut(StreamShape(stream.codec, stream.width, stream.height, stream.fps)) {
            askDecoders(context, stream)
        }

    /**
     * How this device can decode [stream]: whether its decoders for the stream's codec (e.g.
     * "avc1.640028", "vp09.00.40.08", "av01.0.08M.08") say they can play its size, frame rate,
     * profile and level, and whether one that can is a hardware decoder.
     */
    private fun askDecoders(context: Context, stream: VideoStream): Decoding {
        val mime = stream.codec?.let(MimeTypes::getVideoMediaMimeType) ?: return Decoding.SOFTWARE // unknown: let ExoPlayer try
        val decoders = try {
            MediaCodecUtil.getDecoderInfos(mime, /* secure = */ false, /* tunneling = */ false)
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            return Decoding.SOFTWARE
        }
        // A size or frame rate YouTube didn't give (0 or less) isn't checked.
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setCodecs(stream.codec)
            .setWidth(stream.width)
            .setHeight(stream.height)
            .setFrameRate(if (stream.fps > 0) stream.fps.toFloat() else Format.NO_VALUE.toFloat())
            .build()
        return decodingOf(decoders.map { DecoderFit(hardware = it.hardwareAccelerated, fits = it.isFormatSupported(context, format)) })
    }
}
