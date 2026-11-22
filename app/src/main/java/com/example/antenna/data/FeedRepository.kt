package com.example.antenna.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicInteger

data class FeedResult(val items: List<VideoItem>, val failedChannels: List<String>)

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

    suspend fun fetch(
        channels: List<Channel>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FeedResult = coroutineScope {
        val gate = Semaphore(PARALLEL_REQUESTS)
        val done = AtomicInteger(0)

        val results = channels.map { channel ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val r = runCatching { fetchChannel(channel) }
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
        )
    }

    private fun fetchChannel(channel: Channel): List<VideoItem> =
        try {
            fromRss(channel)
        } catch (e: Exception) {
            Log.i(TAG, "RSS failed for ${channel.name}, falling back to channel page")
            fromVideosTab(channel)
        }

    private fun fromRss(channel: Channel): List<VideoItem> {
        val extractor = ServiceList.YouTube.getFeedExtractor(channel.url)
            ?: error("No feed extractor")
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
}
