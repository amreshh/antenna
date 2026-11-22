package com.example.antenna.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import android.net.Uri
import com.example.antenna.net.DESKTOP_USER_AGENT
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream

/**
 * Media3 DataSource for YouTube "googlevideo" stream URLs.
 *
 * Plain GET requests with a Range header get throttled or rejected by YouTube, so
 * this mirrors what NewPipe's player does:
 *  - POST with a tiny fixed body
 *  - `&rn=<n>` request counter and `&range=start-end` as query parameters
 *  - browser-like Origin/Referer headers for WEB-client URLs
 *  - fetch in bounded chunks (stitched together transparently)
 *
 * If YouTube changes its delivery and playback starts failing with HTTP 403,
 * compare this against NewPipe's YoutubeHttpDataSource:
 * https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/org/schabi/newpipe/player/datasource/YoutubeHttpDataSource.java
 */
@OptIn(UnstableApi::class)
class YoutubeDataSource(private val client: OkHttpClient) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeDataSource(client)
    }

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var opened = false
    private var requestNumber = 0

    private var isPlaybackUrl = false
    private var chunked = false
    private var position = 0L          // absolute offset of the next byte we'll return
    private var endInclusive = -1L     // last absolute byte wanted, -1 = unknown
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        isPlaybackUrl = uri.path?.startsWith("/videoplayback") == true
        // googlevideo URLs carry the total file size as `clen`
        val totalLength = uri.getQueryParameter("clen")?.toLongOrNull()

        position = dataSpec.position
        endInclusive = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length - 1
            totalLength != null -> totalLength - 1
            else -> -1L
        }
        if (endInclusive >= 0 && position > endInclusive) {
            // Opening right at the end of the file is how ExoPlayer can probe for it:
            // answer "nothing left", like Media3's own HTTP source does.
            if (position == endInclusive + 1 && dataSpec.length == C.LENGTH_UNSET.toLong()) {
                bytesRemaining = 0
                opened = true
                transferStarted(dataSpec)
                return 0
            }
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        chunked = isPlaybackUrl && endInclusive >= 0
        if (chunked) {
            openNextChunk()
        } else {
            openUnbounded()
        }

        bytesRemaining = when {
            endInclusive >= 0 -> endInclusive - position + 1
            else -> response?.body?.contentLength()?.takeIf { it >= 0 } ?: C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()

        try {
            var n = input!!.read(buffer, offset, toRead)
            if (n == -1 && chunked && position <= endInclusive) {
                // current chunk exhausted, fetch the next one
                openNextChunk()
                n = input!!.read(buffer, offset, toRead)
                if (n == -1) throw IOException("Empty chunk at byte $position")
            }
            if (n == -1) return C.RESULT_END_OF_INPUT

            position += n
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
            bytesTransferred(n)
            return n
        } catch (e: HttpDataSourceException) {
            throw e
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(
                e, dataSpec!!, HttpDataSourceException.TYPE_READ,
            )
        }
    }

    override fun getUri(): Uri? =
        response?.request?.url?.toString()?.let(Uri::parse) ?: dataSpec?.uri

    override fun close() {
        closeResponse()
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }

    // ---------------------------------------------------------------------------------

    private fun openNextChunk() {
        val chunkEnd = minOf(position + CHUNK_SIZE - 1, endInclusive)
        execute(playbackUrl() + "&range=$position-$chunkEnd", post = true)
    }

    private fun openUnbounded() {
        val spec = dataSpec!!
        if (isPlaybackUrl) {
            val range = if (position > 0) "&range=$position-" else ""
            execute(playbackUrl() + range, post = true)
        } else {
            execute(spec.uri.toString(), post = false, rangeHeader = position.takeIf { it > 0 })
        }
    }

    private fun playbackUrl(): String {
        var url = dataSpec!!.uri.toString()
        if (!url.contains("&rn=")) url += "&rn=${requestNumber++}"
        return url
    }

    private fun execute(url: String, post: Boolean, rangeHeader: Long? = null) {
        closeResponse()
        val spec = dataSpec!!

        val builder = Request.Builder().url(url)
        spec.httpRequestHeaders.forEach { (k, v) -> builder.header(k, v) }
        rangeHeader?.let { builder.header("Range", "bytes=$it-") }

        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", YOUTUBE)
                .header("Referer", YOUTUBE)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            DESKTOP_USER_AGENT
        }
        builder.header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity") // keep Content-Length meaningful

        if (post) builder.post(POST_BODY.toRequestBody()) else builder.get()

        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(
                e, spec, HttpDataSourceException.TYPE_OPEN,
            )
        }

        if (!resp.isSuccessful) {
            val code = resp.code
            val message = resp.message
            val headers = resp.headers.toMultimap()
            resp.close()
            throw InvalidResponseCodeException(code, message, null, headers, spec, ByteArray(0))
        }

        response = resp
        input = resp.body?.byteStream()

        // Server ignored our Range header and sent the whole file: skip ahead manually.
        if (rangeHeader != null && resp.code == 200) {
            var toSkip = rangeHeader
            while (toSkip > 0) {
                val skipped = input!!.skip(toSkip)
                if (skipped <= 0) throw DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
                toSkip -= skipped
            }
        }
    }

    private fun closeResponse() {
        try {
            input?.close()
        } catch (_: IOException) {
        }
        response?.close()
        input = null
        response = null
    }

    private companion object {
        const val YOUTUBE = "https://www.youtube.com"
        const val CHUNK_SIZE = 10L * 1024 * 1024 // 10 MB per request
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
