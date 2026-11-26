package com.antenna.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/** How watching a video went, as For you reads it ([Recommender.outcomes]). */
enum class Watched {
    /** Left within its first 30 seconds (its first half, for a shorter video). */
    LeftEarly,

    /** Some of it; or it's the one you opened last, which may still be playing. */
    Partly,

    /** To the end, at some point, even if you've started it again since. */
    Finished,
}

/** How a video in your history went, and how far into it you got at most (0 to 1). */
data class Outcome(val watched: Watched, val fraction: Float)

/**
 * Builds the "For you" list without a Google account.
 *
 * Every time you watch something, YouTube tells us which videos it recommends next
 * (the "up next" list, saved in your watch history). This combines those lists from
 * your recent videos:
 *  - videos suggested after several things you watched rank higher
 *  - recent watches, and videos you watched further into, count more; one you left in its
 *    first 30 seconds doesn't count, and counts against its channel ([outcomes])
 *  - YouTube's own top suggestions (top of each list) count more
 *  - channels you keep coming back to rank higher ([channelAffinity], from your whole history)
 *  - new uploads from those channels are mixed in, even if you're not subscribed
 *    ([favouriteChannels]; the only part that needs requests, made by ForYouViewModel)
 *  - channels you're subscribed to get a small boost
 *  - a video ranks lower each day it was shown here and you passed it over, like on
 *    YouTube's home page ([ShownInForYou])
 *  - left out: videos you finished or left early, the ones you marked Not interested, and
 *    the channels you said not to recommend ([ForYouFeedback])
 *  - no channel gets more than a few spots near the top
 */
object Recommender {

    /** How many recent videos' "up next" lists are blended (older ones count less). */
    private const val RECENT_SOURCES = 100
    private const val MAX_RESULTS = 150
    private const val MAX_PER_CHANNEL_UP_TOP = 3

    /** A watch counts half as much towards a channel after this many days. */
    private const val AFFINITY_HALF_LIFE_DAYS = 60.0

    /**
     * How strongly liking a channel lifts its videos: ×1.2 for one video watched, ×1.6 for
     * ten. Disliking one lowers them as much: ÷1.2, ÷1.6.
     */
    private const val AFFINITY_WEIGHT = 0.25

    /** What a video you left early, or marked Not interested, counts towards its channel (one you watched: 0.2 to 1.2). */
    private const val DISLIKE = -0.3

    /** Channels whose new uploads are mixed in: about two videos' worth watched, recently. */
    private const val FAVOURITE_MIN_AFFINITY = 1.5
    private const val MAX_FAVOURITES = 8

    /** New uploads count for less as they age: half after a week, not at all after a month. */
    private const val UPLOAD_HALF_LIFE_DAYS = 7.0
    private const val UPLOAD_MAX_AGE_DAYS = 30.0
    private const val MAX_UPLOADS_PER_CHANNEL = 2

    /** A brand-new upload from your favourite channel scores like a strong "up next" pick. */
    private const val UPLOAD_WEIGHT = 1.5

    /** For each day a video was shown here and passed over, its score is multiplied by this: 0.7, 0.49, 0.34… */
    private const val PASSED_OVER = 0.7

    /** Played less than this (or than half of a shorter video), a video was left early. */
    private const val LEFT_EARLY_MS = 30_000L

    /** The video opened last isn't judged for this long: it may still be playing, or paused. */
    private const val STILL_OPEN_MS = 60 * 60 * 1000L

    private const val DAY_MS = 24 * 60 * 60 * 1000.0

    /**
     * How each video in [history] went: from the watch log's [spans] (what was actually
     * played), or for videos watched before the log, from how far in the history says you got.
     * The video you opened last, in the last hour, isn't counted as left early: it may still
     * be playing.
     */
    fun outcomes(history: Map<String, WatchEntry>, spans: List<WatchSpan>, now: Long): Map<String, Outcome> {
        val played = HashMap<String, Long>()
        val furthest = HashMap<String, Long>()
        spans.forEach { s ->
            played.merge(s.videoId, s.playedMs, Long::plus)
            furthest.merge(s.videoId, s.toMs, ::maxOf)
        }
        val latest = history.entries.maxByOrNull { it.value.lastWatchedAt }
            ?.takeIf { now - it.value.lastWatchedAt < STILL_OPEN_MS }?.key
        return history.mapValues { (id, e) ->
            val far = maxOf(e.positionMs, furthest[id] ?: 0L)
            val fraction = if (e.durationMs <= 0) 0f else (far.toFloat() / e.durationMs).coerceIn(0f, 1f)
            val early = if (e.durationMs > 0) minOf(LEFT_EARLY_MS, e.durationMs / 2) else LEFT_EARLY_MS
            val watched = when {
                e.isWatched || reachedEnd(far, e.durationMs) -> Watched.Finished
                id != latest && (played[id] ?: e.positionMs) < early -> Watched.LeftEarly
                else -> Watched.Partly
            }
            Outcome(watched, fraction)
        }
    }

    /**
     * How much you like each channel, by its URL, from your whole history (up to the 1000
     * videos it keeps): every video of theirs you opened counts, more the further you
     * watched (0.2 just for opening it, 1.2 for finishing), and less the longer ago. One you
     * left early, or marked Not interested, counts against it.
     */
    fun channelAffinity(
        history: Map<String, WatchEntry>,
        now: Long,
        outcomes: Map<String, Outcome> = outcomes(history, emptyList(), now),
        feedback: ForYouFeedback = ForYouFeedback(),
    ): Map<String, Double> = affinity(history, now, outcomes, feedback).byUrl

    /**
     * The channels you watch most, not counting those in [exclude] (your subscriptions,
     * which have their own tab) and the ones you said not to recommend: whose new uploads For
     * you mixes in.
     */
    fun favouriteChannels(
        history: Map<String, WatchEntry>,
        now: Long,
        exclude: Set<String>,
        outcomes: Map<String, Outcome> = outcomes(history, emptyList(), now),
        feedback: ForYouFeedback = ForYouFeedback(),
    ): List<Channel> {
        val names = HashMap<String, String>()
        history.values.sortedBy { it.lastWatchedAt }.forEach { e ->
            e.video.channelUrl?.let { names[it] = e.video.uploader } // the latest name wins
        }
        return channelAffinity(history, now, outcomes, feedback).entries
            .filter { (url, score) ->
                url !in exclude && score >= FAVOURITE_MIN_AFFINITY && !feedback.hidesChannel(url, names[url].orEmpty())
            }
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
        /** How each video in [history] went ([outcomes]); without the watch log, from the history alone. */
        outcomes: Map<String, Outcome> = outcomes(history, emptyList(), now),
        /** Not interested, and the channels not to recommend. */
        feedback: ForYouFeedback = ForYouFeedback(),
        /** On how many recent days each video was shown here ([ShownInForYou]). */
        shown: Map<String, Int> = emptyMap(),
    ): List<VideoItem> {
        /** Not suggested: finished, left early, Not interested, or from a channel not to recommend. */
        fun leftOut(id: String, video: VideoItem): Boolean {
            val watched = outcomes[id]?.watched
            return watched == Watched.Finished || watched == Watched.LeftEarly || feedback.hides(id, video)
        }

        // The up next lists of what you watched, unless you left it early or aren't interested in it.
        val sources = history.entries
            .filter { (id, e) -> e.related.isNotEmpty() && outcomes[id]?.watched != Watched.LeftEarly && id !in feedback.notInterested }
            .sortedByDescending { it.value.lastWatchedAt }
            .take(RECENT_SOURCES)

        val scores = HashMap<String, Double>()
        val videos = HashMap<String, VideoItem>()

        sources.forEachIndexed { i, (sourceId, source) ->
            val recency = 1.0 / (1 + i * 0.25)
            val engagement = 0.4 + (outcomes[sourceId]?.fraction ?: source.fraction) // 0.4 … 1.4
            source.related.forEachIndexed { j, video ->
                val id = videoIdOf(video.url) ?: return@forEachIndexed
                if (leftOut(id, video)) return@forEachIndexed
                val rank = 1.0 / (1 + j * 0.12)
                scores[id] = (scores[id] ?: 0.0) + recency * engagement * rank
                videos.putIfAbsent(id, video)
            }
        }

        val affinity = affinity(history, now, outcomes, feedback)

        // New uploads from your favourite channels: fresher and from a channel you like more
        // rank higher, at most a couple per channel.
        val topAffinity = affinity.byUrl.values.maxOrNull() ?: 0.0
        if (topAffinity > 0) {
            channelUploads
                .filter { it.uploadedAtMs > 0 }
                .sortedByDescending { it.uploadedAtMs }
                .groupBy { it.channelUrl }
                .values
                .flatMap { it.take(MAX_UPLOADS_PER_CHANNEL) }
                .forEach { video ->
                    val id = videoIdOf(video.url) ?: return@forEach
                    if (leftOut(id, video)) return@forEach
                    val liked = affinity.of(video)
                    val ageDays = (now - video.uploadedAtMs).coerceAtLeast(0) / DAY_MS
                    if (liked <= 0 || ageDays > UPLOAD_MAX_AGE_DAYS) return@forEach
                    val freshness = 0.5.pow(ageDays / UPLOAD_HALF_LIFE_DAYS)
                    scores[id] = (scores[id] ?: 0.0) + UPLOAD_WEIGHT * (liked / topAffinity) * freshness
                    videos.putIfAbsent(id, video)
                }
        }

        val ranked = scores.entries
            .map { (id, score) ->
                val video = videos.getValue(id)
                val subBoost = if (video.uploader in subscribedNames) 1.3 else 1.0
                // shown here on earlier days and passed over (one you opened wasn't)
                val passedOver = if (id in history) 1.0 else PASSED_OVER.pow(shown[id] ?: 0)
                // stable per-video noise so pull-to-refresh reshuffles a little
                val noise = 0.8 + Random(seed xor id.hashCode().toLong()).nextDouble() * 0.4
                video to score * subBoost * lift(affinity.of(video)) * passedOver * noise
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

    /** How much you like each channel: by its link, and by its name for up next lists saved before they had links. */
    private class Affinity(val byUrl: Map<String, Double>, val byName: Map<String, Double>) {
        fun of(v: VideoItem): Double = v.channelUrl?.let { byUrl[it] } ?: byName[v.uploader] ?: 0.0
    }

    private fun affinity(
        history: Map<String, WatchEntry>,
        now: Long,
        outcomes: Map<String, Outcome>,
        feedback: ForYouFeedback,
    ): Affinity {
        val byUrl = HashMap<String, Double>()
        val byName = HashMap<String, Double>()
        fun add(url: String?, name: String, weight: Double) {
            url?.let { byUrl.merge(it, weight, Double::plus) }
            if (name.isNotBlank()) byName.merge(name, weight, Double::plus)
        }
        history.forEach { (id, e) ->
            // further watched counts more, left early counts against
            val o = outcomes[id]
            val weight = if (o?.watched == Watched.LeftEarly) DISLIKE else 0.2 + (o?.fraction ?: e.fraction)
            add(e.video.channelUrl, e.video.uploader, weight * fade(e.lastWatchedAt, now))
        }
        feedback.notInterested.values.forEach { add(it.channelUrl, it.channel, DISLIKE * fade(it.at, now)) }
        return Affinity(byUrl, byName)
    }

    /** What something [at] then still counts for: half after [AFFINITY_HALF_LIFE_DAYS]. */
    private fun fade(at: Long, now: Long): Double = 0.5.pow((now - at).coerceAtLeast(0) / DAY_MS / AFFINITY_HALF_LIFE_DAYS)

    /** How a channel's [affinity] lifts its videos' scores: above 1 for one you like, below for one you don't. */
    private fun lift(affinity: Double): Double {
        val l = AFFINITY_WEIGHT * ln(1 + abs(affinity))
        return if (affinity >= 0) 1 + l else 1 / (1 + l)
    }
}
