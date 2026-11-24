package com.antenna.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

/**
 * How the player retries a failed request for a video's data.
 *
 * Media3's default gives a stream 3 quick tries (about 3 seconds) and treats some HTTP errors
 * as final, 503 among them. YouTube's video servers now and then answer 5xx (503 "Service
 * Unavailable": busy) or drop the connection for a few seconds, so those are tried again for
 * about 20 seconds, waiting longer each time. A refused or expired link (403, 404, 410…) is
 * not worth retrying: it fails at once, and PlayerViewModel fetches fresh links instead.
 */
@OptIn(UnstableApi::class)
internal class PatientRetryPolicy : DefaultLoadErrorHandlingPolicy(MAX_RETRIES) {

    override fun getRetryDelayMsFor(info: LoadErrorInfo): Long = retryDelayMs(
        responseCode = (info.exception as? InvalidResponseCodeException)?.responseCode,
        errorCount = info.errorCount,
        retryable = { super.getRetryDelayMsFor(info) != C.TIME_UNSET },
    )

    companion object {
        /** Failed tries of one request before the player gives up on it. */
        const val MAX_RETRIES = 5

        /**
         * How long to wait before trying again ([C.TIME_UNSET]: don't). [responseCode] is the
         * HTTP status of an error response, null for other errors (a dropped connection, say),
         * which are retried when Media3 would ([retryable]).
         */
        internal fun retryDelayMs(responseCode: Int?, errorCount: Int, retryable: () -> Boolean): Long = when {
            responseCode == null -> if (retryable()) backoff(errorCount) else C.TIME_UNSET
            responseCode in 500..599 -> backoff(errorCount)
            else -> C.TIME_UNSET // the link is no good: get a new one
        }

        /** 1, 2, 4, 8, 8 seconds: about 20 seconds over [MAX_RETRIES] tries. */
        private fun backoff(errorCount: Int): Long = minOf(1_000L shl (errorCount - 1).coerceIn(0, 3), 8_000L)
    }
}
