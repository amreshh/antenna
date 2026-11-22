package com.example.antenna.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
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
 */
@OptIn(UnstableApi::class)
object StreamPicker {

    fun build(info: StreamInfo, maxHeight: Int, http: OkHttpClient): MediaSource {
        val metadata = MediaMetadata.Builder()
            .setTitle(info.name)
            .setArtist(info.uploaderName)
            .build()

        // Live streams: HLS manifest, normal HTTP works fine
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            require(info.hlsUrl.isNotEmpty()) { "No HLS stream found for this live video" }
            val hlsHttp = DefaultHttpDataSource.Factory().setUserAgent(DESKTOP_USER_AGENT)
            return HlsMediaSource.Factory(hlsHttp).createMediaSource(
                MediaItem.Builder().setUri(info.hlsUrl).setMediaMetadata(metadata).build(),
            )
        }

        val progressive = ProgressiveMediaSource.Factory(YoutubeDataSource.Factory(http))
        fun source(stream: Stream) = progressive.createMediaSource(
            MediaItem.Builder().setUri(stream.content).setMediaMetadata(metadata).build(),
        )

        val video = info.videoOnlyStreams
            .filter { it.isPlayable() && it.height in 1..maxHeight }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    // at equal height prefer H.264 (hardware decoding on nearly every phone)
                    .thenBy { if (it.codec?.startsWith("avc") == true) 0 else 1 }
                    .thenByDescending { it.fps },
            )
            .firstOrNull()

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
            .maxByOrNull { it.height }
            ?: error("No playable stream found")
        return source(muxed)
    }

    private fun Stream.isPlayable() =
        isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && content.isNotEmpty()
}
