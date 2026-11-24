package com.antenna.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelTabLinkHandlerFactory
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicInteger

data class FeedResult(
    val items: List<VideoItem>,
    /** The names of the channels that couldn't be loaded, and their urls. */
    val failedChannels: List<String>,
    val failedUrls: Set<String> = emptySet(),
)

/**
 * Builds the subscription feed.
 *
 * Fast path: each channel's public RSS feed (youtube.com/feeds/videos.xml), one small
 * request per channel, giving the latest ~15 uploads. If a feed fails (YouTube's RSS is
 * occasionally flaky), we fall back to loading the channel's Videos tab.
 */
object FeedRepository {

    private const val PARALLEL_REQUESTS = 8
    private const val TAG = "Feed"

    /**
     * The newest uploads of [channels]. For those in [skipPageFor] (they failed lately, so
     * likely gone) a failed RSS feed isn't followed by the channel's page: that's about
     * 180 KB, against 8 KB for the feed.
     */
    suspend fun fetch(
        channels: List<Channel>,
        skipPageFor: Set<String> = emptySet(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FeedResult = coroutineScope {
        val gate = Semaphore(PARALLEL_REQUESTS)
        val done = AtomicInteger(0)

        val results = channels.map { channel ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val r = runCatching { fetchChannel(channel, skipPage = channel.url in skipPageFor) }
                        .onFailure { Log.w(TAG, "Feed failed for ${channel.name}", it) }
                    onProgress(done.incrementAndGet(), channels.size)
                    channel to r
                }
            }
        }.awaitAll()

        FeedResult(
            items = results
                .flatMap { (_, r) -> r.getOrDefault(emptyList()) }
                .distinctBy { it.url }
                .sortedByDescending { it.uploadedAtMs },
            failedChannels = results.filter { it.second.isFailure }.map { it.first.name },
            failedUrls = results.filter { it.second.isFailure }.mapTo(HashSet()) { it.first.url },
        )
    }

    private fun fetchChannel(channel: Channel, skipPage: Boolean): List<VideoItem> =
        try {
            fromRss(channel)
        } catch (e: Exception) {
            if (skipPage) throw e
            Log.i(TAG, "RSS failed for ${channel.name}, falling back to channel page")
            fromVideosTab(channel)
        }

    private fun fromRss(channel: Channel): List<VideoItem> {
        val extractor = ServiceList.YouTube.getFeedExtractor(channel.url)
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem(fallbackUploader = channel.name).copy(channelUrl = channel.url) }
    }

    private fun fromVideosTab(channel: Channel): List<VideoItem> {
        val service = ServiceList.YouTube
        val info = ChannelInfo.getInfo(service, channel.url)
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
            ?: return emptyList()
        return ChannelTabInfo.getInfo(service, videosTab).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem(fallbackUploader = channel.name).copy(channelUrl = channel.url) }
    }

    /**
     * How long the newest videos of the channel at [channelUrl] are, in seconds, by video id:
     * the RSS feed leaves that out. From the channel's Videos tab, one request of about 45 KB
     * for its last 30 or so (the feed is 3 to 8 KB). Shorts and live streams aren't on that
     * tab; YouTube shows no length for a Short anyway.
     */
    suspend fun lengths(channelUrl: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val id = channelUrl.substringAfter("/channel/", missingDelimiterValue = "")
        require(id.isNotEmpty()) { "Not a channel link: $channelUrl" }
        val tab = YoutubeChannelTabLinkHandlerFactory.getInstance().fromQuery("channel/$id", listOf(ChannelTabs.VIDEOS), "")
        ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item -> videoIdOf(item.url)?.let { video -> item.duration.takeIf { it > 0 }?.let { video to it } } }
            .toMap()
    }
}

/**
 * These videos with the lengths in [lengths] (seconds, by video id) for the ones that have
 * none: the RSS feed's videos, once their channel's lengths are known.
 */
fun List<VideoItem>.withLengths(lengths: Map<String, Long>): List<VideoItem> =
    if (lengths.isEmpty()) this
    else map { v ->
        if (v.durationSeconds > 0) v
        else videoIdOf(v.url)?.let(lengths::get)?.let { v.copy(durationSeconds = it) } ?: v
    }

/** The lengths these videos have, in seconds, by video id: to keep them when a fresh feed comes in without. */
fun List<VideoItem>.lengths(): Map<String, Long> =
    mapNotNull { v -> v.durationSeconds.takeIf { it > 0 }?.let { d -> videoIdOf(v.url)?.let { it to d } } }.toMap()
