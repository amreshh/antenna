package com.antenna.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
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
     * A page of [channel]'s Videos tab: the first, newest videos (with [page] null), or [page].
     * One request of about 45 KB for 30 videos (the feed is 3 to 8 KB). They have their
     * lengths, which the feed leaves out, and the pages go back further than the feed's last
     * 15 uploads, Shorts among them. Shorts and live streams aren't on that tab; YouTube shows
     * no length for a Short anyway.
     */
    suspend fun videos(channel: Channel, page: Page? = null): VideosPage = withContext(Dispatchers.IO) {
        val id = channel.url.substringAfter("/channel/", missingDelimiterValue = "")
        require(id.isNotEmpty()) { "Not a channel link: ${channel.url}" }
        val tab = YoutubeChannelTabLinkHandlerFactory.getInstance().fromQuery("channel/$id", listOf(ChannelTabs.VIDEOS), "")
        val (items, next) = if (page == null) {
            ChannelTabInfo.getInfo(ServiceList.YouTube, tab).let { it.relatedItems to it.nextPage.takeIf { _ -> it.hasNextPage() } }
        } else {
            ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, page).let { it.items to it.nextPage.takeIf { _ -> it.hasNextPage() } }
        }
        val videos = items.filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem(fallbackUploader = channel.name).copy(channelUrl = channel.url) }
            .distinctBy { it.url }
        VideosPage(videos, next.takeIf { videos.isNotEmpty() })
    }
}

/** A page of a channel's Videos tab, newest first, and the page after it: null at the end. */
class VideosPage(val videos: List<VideoItem>, val next: Page?)

/**
 * One channel's videos, newest first: [feed], its videos in the Subscriptions feed (the RSS's
 * last 15 uploads, with exact times), and [tab], its Videos tab as far as it's loaded (further
 * back, with lengths, but its times only as YouTube words them: "2 weeks ago" is taken as
 * midnight 14 days ago). Each video once, the feed's with the tab's length.
 *
 * The tab's order is the true one. A rough time can be off by most of its unit, so a video
 * only the tab has would sort above a newer one, or below an older one: its time is moved
 * between those of the feed's videos above and below it in the tab, keeping it in place.
 */
fun channelFeed(feed: List<VideoItem>, tab: List<VideoItem>): List<VideoItem> {
    if (tab.isEmpty()) return feed
    val inFeed = feed.associateBy { videoIdOf(it.url) }
    val ids = tab.map { videoIdOf(it.url) }
    // The exact time of the feed's video nearest above each of the tab's, and nearest below.
    val exact = ids.map { id -> inFeed[id]?.uploadedAtMs?.takeIf { it > 0 } }
    val above = LongArray(tab.size)
    var time = Long.MAX_VALUE
    for (i in tab.indices) {
        above[i] = time
        exact[i]?.let { time = minOf(time, it) }
    }
    val below = LongArray(tab.size)
    time = 0L
    for (i in tab.indices.reversed()) {
        below[i] = time
        exact[i]?.let { time = maxOf(time, it) }
    }
    val inTab = tab.mapIndexed { i, v ->
        inFeed[ids[i]] ?: v.copy(uploadedAtMs = if (below[i] <= above[i]) v.uploadedAtMs.coerceIn(below[i], above[i]) else v.uploadedAtMs)
    }
    val tabIds = ids.toHashSet()
    val feedOnly = feed.filter { videoIdOf(it.url) !in tabIds } // Shorts, live streams, uploads since the tab loaded
    // A stable sort: a video given the time of the one above or below it stays on its side.
    return (inTab + feedOnly).withLengths(tab.lengths()).sortedByDescending { it.uploadedAtMs }
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
