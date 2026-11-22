package com.example.antenna.data

import android.text.format.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
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
    /** Set for feed items so we know which subscription they came from. */
    val channelUrl: String? = null,
)

data class SearchPage(val items: List<VideoItem>, val nextPage: Page?)

/** Thin wrapper around NewPipeExtractor. Everything here does network I/O. */
object YouTubeRepository {

    private val youtube get() = ServiceList.YouTube
    private val videosOnly = listOf(YoutubeSearchQueryHandlerFactory.VIDEOS)

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val extractor = youtube.getSearchExtractor(query, videosOnly, "")
        extractor.fetchPage()
        extractor.initialPage.toSearchPage()
    }

    suspend fun searchMore(query: String, page: Page): SearchPage = withContext(Dispatchers.IO) {
        youtube.getSearchExtractor(query, videosOnly, "").getPage(page).toSearchPage()
    }

    suspend fun streamInfo(url: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(youtube, url)
    }

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
