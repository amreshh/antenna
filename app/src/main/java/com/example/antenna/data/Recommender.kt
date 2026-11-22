package com.example.antenna.data

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
 *  - channels you're subscribed to get a small boost
 *  - videos you've already watched are left out
 *  - no channel gets more than a few spots near the top
 *
 * It all runs on the phone from data you already have, so it needs no extra requests.
 */
object Recommender {

    private const val RECENT_SOURCES = 30
    private const val MAX_RESULTS = 150
    private const val MAX_PER_CHANNEL_UP_TOP = 3

    fun build(
        history: Map<String, WatchEntry>,
        subscribedNames: Set<String>,
        seed: Long,
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

        val ranked = scores.entries
            .map { (id, score) ->
                val video = videos.getValue(id)
                val subBoost = if (video.uploader in subscribedNames) 1.3 else 1.0
                // stable per-video noise so pull-to-refresh reshuffles a little
                val noise = 0.8 + Random(seed xor id.hashCode().toLong()).nextDouble() * 0.4
                video to score * subBoost * noise
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
}
