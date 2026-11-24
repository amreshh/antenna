package com.antenna.data

import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import android.text.format.DateUtils
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    /** Upload time in epoch millis, 0 if unknown. */
    val uploadedAtMs: Long = 0,
    /** YouTube's own text ("3 days ago"), used when no exact date is available. */
    val uploadedText: String? = null,
    /**
     * The uploader's channel (canonical /channel/UC… form) when known: search results, the
     * feed (which subscription it came from), channel pages and the playing video.
     */
    val channelUrl: String? = null,
    /** The uploader's avatar, when the list it came from has one (search results do). */
    val channelAvatarUrl: String? = null,
) {
    /**
     * A YouTube Short. The Subscriptions feed (channels' RSS) links them as
     * youtube.com/shorts/…, and other videos as /watch?v=…; other lists don't tell them apart.
     */
    val isShort: Boolean get() = "/shorts/" in url
}

data class SearchPage(val items: List<VideoItem>, val nextPage: Page?)

/** A channel's page: who it is and its newest videos (more with [YouTubeRepository.channelMore]). */
data class ChannelPage(
    val channel: Channel,
    /** -1 when the channel hides it */
    val subscriberCount: Long,
    val videos: List<VideoItem>,
    /** The channel's Videos tab, to fetch more from; null if it has none. */
    val videosTab: ListLinkHandler?,
    val nextPage: Page?,
)

/** Thin wrapper around NewPipeExtractor. Everything here does network I/O. */
object YouTubeRepository {

    private val youtube get() = ServiceList.YouTube
    private val videosOnly = listOf(YoutubeSearchQueryHandlerFactory.VIDEOS)

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val extractor = youtube.getSearchExtractor(query, videosOnly, "")
        extractor.fetchPage()
        extractor.initialPage.toSearchPage()
    }

    /** What YouTube suggests while [query] is being typed, best first. */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        youtube.suggestionExtractor.suggestionList(query)
    }

    suspend fun searchMore(query: String, page: Page): SearchPage = withContext(Dispatchers.IO) {
        youtube.getSearchExtractor(query, videosOnly, "").getPage(page).toSearchPage()
    }

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(youtube, url)
        val channel = Channel(
            url = normalizeChannelUrl(info.url) ?: url,
            name = info.name,
            avatarUrl = info.avatars.pick(CHANNEL_AVATAR_HEIGHT),
        )
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
            ?: return@withContext ChannelPage(channel, info.subscriberCount, emptyList(), null, null)
        val videos = ChannelTabInfo.getInfo(youtube, tab)
        ChannelPage(
            channel = channel,
            subscriberCount = info.subscriberCount,
            videos = videos.relatedItems.toChannelVideos(channel),
            videosTab = tab,
            nextPage = if (videos.hasNextPage()) videos.nextPage else null,
        )
    }

    /**
     * A channel's avatar (for subscriptions imported without one), or null if it has none.
     * First from a channel search for its name, about 15 KB, where the channel's own page is
     * about 180 KB; the result has to be that channel (its id), else it's the page after all.
     */
    suspend fun channelAvatar(channel: Channel): String? = withContext(Dispatchers.IO) {
        val searchable = channel.name.isNotBlank() && !Regex("""UC[\w-]{22}""").matches(channel.name) // Takeout's untitled ones
        if (searchable) {
            val found = try {
                val search = youtube.getSearchExtractor(channel.name, listOf(YoutubeSearchQueryHandlerFactory.CHANNELS), "")
                search.fetchPage()
                search.initialPage.items.filterIsInstance<ChannelInfoItem>()
                    .firstOrNull { normalizeChannelUrl(it.url) == channel.url }
                    ?.thumbnails?.pick(CHANNEL_AVATAR_HEIGHT)
            } catch (e: ReCaptchaException) {
                throw e // YouTube is limiting requests: the page wouldn't come either
            } catch (_: Exception) {
                null
            }
            if (found != null) return@withContext found
        }
        Log.i("Avatars", "${channel.name}: not found by search, using the channel's page")
        ChannelInfo.getInfo(youtube, channel.url).avatars.pick(CHANNEL_AVATAR_HEIGHT)
    }

    /** The next page of a channel's videos, and the page after it (null at the end). */
    suspend fun channelMore(channel: Channel, videosTab: ListLinkHandler, page: Page): Pair<List<VideoItem>, Page?> =
        withContext(Dispatchers.IO) {
            val more = ChannelTabInfo.getMoreItems(youtube, videosTab, page)
            more.items.toChannelVideos(channel) to (if (more.hasNextPage()) more.nextPage else null)
        }

    /** Videos on a channel's page often leave out who uploaded them: it's the channel. */
    private fun List<InfoItem>.toChannelVideos(channel: Channel): List<VideoItem> =
        filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem(fallbackUploader = channel.name).copy(channelUrl = channel.url, channelAvatarUrl = channel.avatarUrl) }
            .distinctBy { it.url }

    /**
     * A video's details and stream links. The last few are kept for [INFO_KEEP_MS], so going
     * Back through Up next doesn't fetch them again; [fresh] fetches them anyway (new links,
     * after a playback error). Links last some hours, far longer than they're kept.
     */
    suspend fun streamInfo(url: String, fresh: Boolean = false): StreamInfo {
        val id = videoIdOf(url)
        if (!fresh && id != null) {
            kept(id)?.let { return it }
            // Being fetched ahead (a guess, see prefetch): wait for that rather than fetch it twice.
            synchronized(recentInfo) { fetching[id] }?.let { ahead ->
                try {
                    return ahead.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // that one failed: try again below
                }
            }
        }
        return withContext(Dispatchers.IO) { fetchInfo(url, id) }
    }

    /**
     * Fetches a video's details before it's opened (Prefetcher's guesses), kept like any
     * other's. [streamInfo] waits for one that's still on its way.
     */
    suspend fun prefetch(url: String) {
        val id = videoIdOf(url) ?: return
        val job = synchronized(recentInfo) {
            if (kept(id) != null) return
            fetching[id] ?: ahead.async { fetchInfo(url, id) }.also { fetching[id] = it }
        }
        try {
            job.await()
        } finally {
            synchronized(recentInfo) { if (fetching[id] === job) fetching.remove(id) }
        }
    }

    /** A video's details fetched in the last [INFO_KEEP_MS], if they're kept. */
    private fun kept(id: String): StreamInfo? = synchronized(recentInfo) {
        recentInfo[id]?.takeIf { System.currentTimeMillis() - it.first < INFO_KEEP_MS }?.second
    }

    private fun fetchInfo(url: String, id: String?): StreamInfo {
        val now = System.currentTimeMillis()
        return StreamInfo.getInfo(youtube, url).also { info ->
            if (id != null) synchronized(recentInfo) { recentInfo[id] = now to info }
        }
    }

    /** The last [INFO_KEPT] videos' details, by id, with when they were fetched; least recently used first. */
    private val recentInfo = object : LinkedHashMap<String, Pair<Long, StreamInfo>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, StreamInfo>>) = size > INFO_KEPT
    }
    private const val INFO_KEPT = 8
    private const val INFO_KEEP_MS = 30 * 60 * 1000L

    /** Details being fetched ahead, by video id (guarded by [recentInfo]). */
    private val fetching = HashMap<String, Deferred<StreamInfo>>()

    /** Fetches ahead keep going even if what started them stops waiting: they're kept for whoever opens the video. */
    private val ahead = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun InfoItemsPage<InfoItem>.toSearchPage(): SearchPage {
        val videos = items.filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem() }
            .distinctBy { it.url }
        return SearchPage(videos, if (hasNextPage()) nextPage else null)
    }
}

/** Shared by search results and the subscription feed. */
fun StreamInfoItem.toVideoItem(fallbackUploader: String = ""): VideoItem = VideoItem(
    url = url,
    title = name,
    uploader = uploaderName?.takeIf { it.isNotBlank() } ?: fallbackUploader,
    durationSeconds = duration,
    thumbnailUrl = thumbnails.pick(THUMBNAIL_HEIGHT),
    uploadedAtMs = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0L,
    uploadedText = textualUploadDate,
    channelUrl = uploaderUrl?.let(::normalizeChannelUrl),
    channelAvatarUrl = uploaderAvatars.pick(CHANNEL_AVATAR_HEIGHT),
)

/** The video currently being played, in the same shape as list items. */
fun StreamInfo.toVideoItem(): VideoItem = VideoItem(
    url = url,
    title = name,
    uploader = uploaderName.orEmpty(),
    durationSeconds = duration,
    thumbnailUrl = thumbnails.pick(THUMBNAIL_HEIGHT),
    uploadedAtMs = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0L,
    uploadedText = textualUploadDate,
    channelUrl = normalizeChannelUrl(uploaderUrl.orEmpty()),
)

/**
 * Thumbnails are about 270 px tall: sharp in phone lists and on tablet cards (up to
 * ~450 px wide) without fetching the largest images.
 */
private const val THUMBNAIL_HEIGHT = 270

/** Channel avatars are at most 72 dp (the channel page), so about 216 px at 3x. */
private const val CHANNEL_AVATAR_HEIGHT = 176

/** The smallest image at least [minHeight] tall, or the largest there is. */
fun List<Image>.pick(minHeight: Int): String? {
    val bySize = sortedBy { it.height }
    return (bySize.firstOrNull { it.height >= minHeight } ?: bySize.lastOrNull())?.url
}

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return ""
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "3 hours ago" style label for a video. */
fun VideoItem.ageLabel(now: Long = System.currentTimeMillis()): String = when {
    uploadedAtMs > 0 -> DateUtils.getRelativeTimeSpanString(
        uploadedAtMs, now, DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    else -> uploadedText.orEmpty()
}
