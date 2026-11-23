package com.example.antenna.data

/** Builders for the tests: videos, channels and watch history entries. */
internal object TestData {
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A fixed "now", so the tests don't depend on the clock. */
    const val NOW = 1_800_000_000_000L

    /** A valid 11-character video id from a number. */
    fun id(n: Int): String = "v%010d".format(n)

    /** A valid channel URL from a short name (padded to a UC… id). */
    fun channelUrl(name: String): String =
        "https://www.youtube.com/channel/UC" + name.padEnd(22, 'x').take(22)

    fun video(
        n: Int,
        channel: String = "chan$n",
        uploadedAtMs: Long = 0,
        withChannelUrl: Boolean = true,
    ) = VideoItem(
        url = watchUrl(id(n)),
        title = "Video $n",
        uploader = channel,
        durationSeconds = 600,
        thumbnailUrl = null,
        uploadedAtMs = uploadedAtMs,
        channelUrl = if (withChannelUrl) channelUrl(channel) else null,
    )

    /** A history entry: [fraction] of the video watched, [daysAgo] days ago. */
    fun watched(
        video: VideoItem,
        fraction: Double = 1.0,
        daysAgo: Double = 0.0,
        related: List<VideoItem> = emptyList(),
    ) = WatchEntry(
        video = video,
        positionMs = (fraction * 600_000).toLong(),
        durationMs = 600_000,
        lastWatchedAt = NOW - (daysAgo * DAY_MS).toLong(),
        related = related,
    )

    /** History keyed by video id, as WatchHistoryStore keeps it. */
    fun history(vararg entries: WatchEntry): Map<String, WatchEntry> =
        entries.associateBy { videoIdOf(it.video.url)!! }
}
