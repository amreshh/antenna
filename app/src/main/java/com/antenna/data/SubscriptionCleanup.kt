package com.antenna.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** What checking a subscribed channel on YouTube found. */
sealed interface ChannelCheck {
    /** It exists; when its newest upload came out (null: it has none). */
    data class Uploads(val newestMs: Long?) : ChannelCheck

    /** YouTube says it doesn't exist (deleted, or the account terminated): [message] is YouTube's. */
    data class Gone(val message: String) : ChannelCheck

    /** Couldn't tell (no connection, YouTube limiting requests, not available here…): it's kept. */
    data class Unknown(val message: String) : ChannelCheck
}

/** Why a channel would be removed by the cleanup. */
sealed interface CleanupReason {
    data class Gone(val message: String) : CleanupReason
    data object NoUploads : CleanupReason
    data class Inactive(val newestMs: Long) : CleanupReason
}

data class CleanupCandidate(val channel: Channel, val reason: CleanupReason)

/**
 * Finds subscriptions that are gone, or that haven't uploaded for a while (the Manage
 * subscriptions screen's Clean up). Checking makes requests to YouTube; deciding what to
 * remove ([candidates]) doesn't, so the period can change without checking again.
 */
object SubscriptionCleanup {

    private const val PARALLEL_REQUESTS = 8

    /** Checks every channel, a few at a time. [onProgress] gets (done, total). */
    suspend fun check(
        channels: List<Channel>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, ChannelCheck> = coroutineScope {
        val gate = Semaphore(PARALLEL_REQUESTS)
        val done = AtomicInteger(0)
        channels.map { channel ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val result = checkOne(channel)
                    onProgress(done.incrementAndGet(), channels.size)
                    channel.url to result
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * The channel's RSS feed first: one small request, with exact dates, Shorts and streams
     * included. If it fails (it's flaky, and gone channels have none) the channel page says
     * whether it exists, and its tabs when it last uploaded.
     */
    private fun checkOne(channel: Channel): ChannelCheck {
        val youtube = ServiceList.YouTube
        try {
            val feed = youtube.getFeedExtractor(channel.url)
            feed.fetchPage()
            return ChannelCheck.Uploads(newestOf(feed.initialPage.items))
        } catch (_: Exception) {
            // ask the channel itself
        }
        return try {
            val info = ChannelInfo.getInfo(youtube, channel.url)
            val tabs = info.tabs.filter { tab ->
                tab.contentFilters.any { it == ChannelTabs.VIDEOS || it == ChannelTabs.SHORTS || it == ChannelTabs.LIVESTREAMS }
            }
            ChannelCheck.Uploads(tabs.mapNotNull { newestOf(ChannelTabInfo.getInfo(youtube, it).relatedItems) }.maxOrNull())
        } catch (e: Exception) {
            if (isGone(e)) ChannelCheck.Gone(youtubeMessage(e)) else ChannelCheck.Unknown(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Only a channel YouTube says doesn't exist, or whose account was terminated, counts as
     * gone. The other "not available" errors (in this country, age-restricted, members-only)
     * are kinds of ContentNotAvailableException too, and those channels are fine.
     */
    internal fun isGone(e: Exception): Boolean =
        e is AccountTerminatedException || e.javaClass == ContentNotAvailableException::class.java

    /** YouTube's reason is quoted in the message: "This channel does not exist." */
    private fun youtubeMessage(e: Exception): String =
        e.message?.let { Regex("\"(.+)\"").find(it)?.groupValues?.get(1) ?: it }.orEmpty()

    private fun newestOf(items: List<InfoItem>): Long? =
        items.filterIsInstance<StreamInfoItem>()
            .mapNotNull { runCatching { it.uploadDate?.instant?.toEpochMilli() }.getOrNull() }
            .maxOrNull()

    /**
     * The channels to remove, and why: gone ones (with [removeGone]), and ones with no
     * upload in the last [inactiveMonths] months (null: don't remove for that), including
     * ones that never uploaded. Channels that couldn't be checked are never in it.
     */
    fun candidates(
        channels: List<Channel>,
        checks: Map<String, ChannelCheck>,
        removeGone: Boolean,
        inactiveMonths: Int?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CleanupCandidate> {
        val cutoff = inactiveMonths?.let { Instant.ofEpochMilli(now).atZone(zone).minusMonths(it.toLong()).toInstant().toEpochMilli() }
        return channels.mapNotNull { channel ->
            val reason = when (val check = checks[channel.url]) {
                is ChannelCheck.Gone -> if (removeGone) CleanupReason.Gone(check.message) else null
                is ChannelCheck.Uploads -> when {
                    cutoff == null -> null
                    check.newestMs == null -> CleanupReason.NoUploads
                    check.newestMs < cutoff -> CleanupReason.Inactive(check.newestMs)
                    else -> null
                }
                is ChannelCheck.Unknown, null -> null
            }
            reason?.let { CleanupCandidate(channel, it) }
        }
    }
}
