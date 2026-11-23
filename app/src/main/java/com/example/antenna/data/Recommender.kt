package com.example.antenna.data

import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Builds the "For you" list without a Google account.
 *
 * Every time you watch something, YouTube tells us which videos it recommends next
 * (the "up next" list, saved in your watch history). This combines those lists from
 * your recent videos:
 *  - videos suggested after several things you watched rank higher
 *  - recent watches, and videos you watched further into, count more
 *  - YouTube's own top suggestions (top of each list) count more
 *  - channels you keep coming back to rank higher ([channelAffinity], from your whole history)
 *  - new uploads from those channels are mixed in, even if you're not subscribed
 *    ([favouriteChannels]; the only part that needs requests, made by ForYouViewModel)
 *  - channels you're subscribed to get a small boost
 *  - videos you've already watched are left out
 *  - no channel gets more than a few spots near the top
 */
object Recommender {

    /** How many recent videos' "up next" lists are blended (older ones count less). */
    private const val RECENT_SOURCES = 100
    private const val MAX_RESULTS = 150
    private const val MAX_PER_CHANNEL_UP_TOP = 3

    /** A watch counts half as much towards a channel after this many days. */
    private const val AFFINITY_HALF_LIFE_DAYS = 60.0

    /** How strongly liking a channel lifts its videos: ×1.2 for one video watched, ×1.6 for ten. */
    private const val AFFINITY_WEIGHT = 0.25

    /** Channels whose new uploads are mixed in: about two videos' worth watched, recently. */
    private const val FAVOURITE_MIN_AFFINITY = 1.5
    private const val MAX_FAVOURITES = 8

    /** New uploads count for less as they age: half after a week, not at all after a month. */
    private const val UPLOAD_HALF_LIFE_DAYS = 7.0
    private const val UPLOAD_MAX_AGE_DAYS = 30.0
    private const val MAX_UPLOADS_PER_CHANNEL = 2

    /** A brand-new upload from your favourite channel scores like a strong "up next" pick. */
    private const val UPLOAD_WEIGHT = 1.5

    private const val DAY_MS = 24 * 60 * 60 * 1000.0

    /**
     * How much you like each channel, by its URL, from your whole history (up to the 1000
     * videos it keeps): every video of theirs you opened counts, more the further you
     * watched (0.2 just for opening it, 1.2 for finishing), and less the longer ago.
     */
    fun channelAffinity(history: Collection<WatchEntry>, now: Long): Map<String, Double> {
        val scores = HashMap<String, Double>()
        history.forEach { e ->
            val url = e.video.channelUrl ?: return@forEach
            scores.merge(url, e.affinityWeight(now), Double::plus)
        }
        return scores
    }

    /**
     * The channels you watch most, not counting those in [exclude] (your subscriptions,
     * which have their own tab): whose new uploads For you mixes in.
     */
    fun favouriteChannels(history: Collection<WatchEntry>, now: Long, exclude: Set<String>): List<Channel> {
        val names = HashMap<String, String>()
        history.sortedBy { it.lastWatchedAt }.forEach { e ->
            e.video.channelUrl?.let { names[it] = e.video.uploader } // the latest name wins
        }
        return channelAffinity(history, now).entries
            .filter { (url, score) -> url !in exclude && score >= FAVOURITE_MIN_AFFINITY }
            .sortedByDescending { it.value }
            .take(MAX_FAVOURITES)
            .map { (url, _) -> Channel(url = url, name = names[url].orEmpty()) }
    }

    fun build(
        history: Map<String, WatchEntry>,
        subscribedNames: Set<String>,
        seed: Long,
        /** Recent uploads from [favouriteChannels]; empty until they've been fetched. */
        channelUploads: List<VideoItem> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): List<VideoItem> {
        val sources = history.values
            .filter { it.related.isNotEmpty() }
            .sortedByDescending { it.lastWatchedAt }
            .take(RECENT_SOURCES)

        val scores = HashMap<String, Double>()
        val videos = HashMap<String, VideoItem>()

        sources.forEachIndexed { i, source ->
            val recency = 1.0 / (1 + i * 0.25)
            val engagement = 0.4 + source.fraction          // 0.4 … 1.4
            source.related.forEachIndexed { j, video ->
                val id = videoIdOf(video.url) ?: return@forEachIndexed
                if (history[id]?.isWatched == true) return@forEachIndexed
                val rank = 1.0 / (1 + j * 0.12)
                scores[id] = (scores[id] ?: 0.0) + recency * engagement * rank
                videos.putIfAbsent(id, video)
            }
        }

        // Channel affinity, by URL, and by name for "up next" lists saved before they had
        // channel URLs.
        val affinity = channelAffinity(history.values, now)
        val affinityByName = HashMap<String, Double>()
        history.values.forEach { e ->
            if (e.video.uploader.isNotBlank()) affinityByName.merge(e.video.uploader, e.affinityWeight(now), Double::plus)
        }
        fun affinityOf(v: VideoItem): Double = v.channelUrl?.let { affinity[it] } ?: affinityByName[v.uploader] ?: 0.0

        // New uploads from your favourite channels: fresher and from a channel you like more
        // rank higher, at most a couple per channel.
        val topAffinity = affinity.values.maxOrNull() ?: 0.0
        if (topAffinity > 0) {
            channelUploads
                .filter { it.uploadedAtMs > 0 }
                .sortedByDescending { it.uploadedAtMs }
                .groupBy { it.channelUrl }
                .values
                .flatMap { it.take(MAX_UPLOADS_PER_CHANNEL) }
                .forEach { video ->
                    val id = videoIdOf(video.url) ?: return@forEach
                    if (history[id]?.isWatched == true) return@forEach
                    val ageDays = (now - video.uploadedAtMs).coerceAtLeast(0) / DAY_MS
                    if (ageDays > UPLOAD_MAX_AGE_DAYS) return@forEach
                    val freshness = 0.5.pow(ageDays / UPLOAD_HALF_LIFE_DAYS)
                    scores[id] = (scores[id] ?: 0.0) + UPLOAD_WEIGHT * (affinityOf(video) / topAffinity) * freshness
                    videos.putIfAbsent(id, video)
                }
        }

        val ranked = scores.entries
            .map { (id, score) ->
                val video = videos.getValue(id)
                val subBoost = if (video.uploader in subscribedNames) 1.3 else 1.0
                val affinityBoost = 1 + AFFINITY_WEIGHT * ln(1 + affinityOf(video))
                // stable per-video noise so pull-to-refresh reshuffles a little
                val noise = 0.8 + Random(seed xor id.hashCode().toLong()).nextDouble() * 0.4
                video to score * subBoost * affinityBoost * noise
            }
            .sortedByDescending { it.second }
            .map { it.first }

        // Diversify: cap how often one channel appears before the rest of the list.
        val perChannel = HashMap<String, Int>()
        val (top, overflow) = ranked.partition { v ->
            val n = perChannel.merge(v.uploader, 1) { a, b -> a + b }!!
            n <= MAX_PER_CHANNEL_UP_TOP
        }
        return (top + overflow).take(MAX_RESULTS)
    }

    /** One watch's weight towards its channel: further watched counts more, older less. */
    private fun WatchEntry.affinityWeight(now: Long): Double {
        val ageDays = (now - lastWatchedAt).coerceAtLeast(0) / DAY_MS
        return (0.2 + fraction) * 0.5.pow(ageDays / AFFINITY_HALF_LIFE_DAYS)
    }
}
