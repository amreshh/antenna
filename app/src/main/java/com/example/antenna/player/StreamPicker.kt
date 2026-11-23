package com.example.antenna.player

import androidx.annotation.OptIn
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
import com.example.antenna.net.DESKTOP_USER_AGENT
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

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
    fun build(info: StreamInfo, maxHeight: Int, http: OkHttpClient, preferred: Int? = null): MediaSource {
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

        // Only codecs this device can decode (a stream it can't would play as audio only),
        // and hardware decoding first: 1440p in VP9 decoded in software stutters where
        // 1080p in H.264 plays smoothly. A quality picked in the menu comes before all that.
        val decoders = HashMap<String?, Decoding>()
        val video = info.videoOnlyStreams
            .filter { it.isPlayable() && it.shortSide in 1..maxOf(maxHeight, preferred ?: 0) }
            .map { it to decoders.getOrPut(it.codec) { decodingOf(it.codec) } }
            .filter { (_, decoding) -> decoding != Decoding.NONE }
            .sortedWith(
                compareBy<Pair<VideoStream, Decoding>> { (stream, _) -> if (stream.shortSide == preferred) 0 else 1 }
                    .thenBy { (_, decoding) -> decoding.ordinal }
                    .thenByDescending { (stream, _) -> stream.shortSide }
                    // at equal height prefer H.264 (hardware decoding on nearly every phone)
                    .thenBy { (stream, _) -> if (stream.codec?.startsWith("avc") == true) 0 else 1 }
                    .thenByDescending { (stream, _) -> stream.fps },
            )
            .firstOrNull()?.first

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
     * The qualities the quality menu offers, best first: those this device can decode. Empty
     * for live streams, whose quality adapts by itself.
     */
    fun qualities(info: StreamInfo): List<Int> {
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) return emptyList()
        val decoders = HashMap<String?, Decoding>()
        val separate = info.videoOnlyStreams
            .filter { it.isPlayable() && decoders.getOrPut(it.codec) { decodingOf(it.codec) } != Decoding.NONE }
        val streams = separate.ifEmpty { info.videoStreams.filter { it.isPlayable() } }
        return streams.map { it.shortSide }.filter { it > 0 }.distinct().sortedDescending()
    }

    /** The shorter side: 1080 for both 1920×1080 and a vertical 1080×1920. */
    val VideoStream.shortSide: Int
        get() = if (width > 0 && height > 0) minOf(width, height) else height

    private fun Stream.isPlayable() =
        isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && content.isNotEmpty()

    /** In order of preference. */
    private enum class Decoding { HARDWARE, SOFTWARE, NONE }

    /** How this device can decode a codec, e.g. "avc1.640028", "vp09.00.40.08", "av01.0.08M.08". */
    private fun decodingOf(codec: String?): Decoding {
        val mime = codec?.let(MimeTypes::getVideoMediaMimeType) ?: return Decoding.SOFTWARE // unknown: let ExoPlayer try
        val decoders = try {
            MediaCodecUtil.getDecoderInfos(mime, /* secure = */ false, /* tunneling = */ false)
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            return Decoding.SOFTWARE
        }
        return when {
            decoders.any { it.hardwareAccelerated } -> Decoding.HARDWARE
            decoders.isNotEmpty() -> Decoding.SOFTWARE
            else -> Decoding.NONE
        }
    }
}
