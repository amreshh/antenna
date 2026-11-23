package com.example.antenna.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Time spent on one topic or category, for the Analytics tab. */
data class TopicTime(val name: String, val watchedMs: Long, val videos: Int)

/** Time spent on one channel, for the Analytics tab's top channels. */
data class ChannelTime(
    val name: String,
    /** null when its videos were saved without a channel link */
    val url: String?,
    val watchedMs: Long,
    val videos: Int,
)

/**
 * The Analytics tab's numbers, worked out on the phone from the watch history (up to the
 * 1000 videos it keeps).
 *
 * Watch time is how far into each video you got: the history keeps one position per video,
 * so watching something twice, or skipping around, isn't counted separately. It's counted on
 * the day and hour you last watched it.
 */
data class WatchStats(
    val videos: Int,
    val watchedMs: Long,
    val channels: Int,
    /** Share of videos watched to the end (WatchEntry.isWatched), 0…1. */
    val finishedShare: Float,
    /** Watch time per day for the last [DAYS] days, oldest first; the last is today. */
    val perDay: List<Long>,
    val firstDay: LocalDate,
    /** The channels you've spent the most time on, most first. */
    val topChannels: List<ChannelTime>,
    /** Watch time by the hour of the day you watched, 0…23. */
    val byHour: List<Long>,
    /** How far you get into videos: [finished, over half, under half, barely (under 10%)]. */
    val completion: List<Int>,
    /** YouTube's categories, by watch time; only videos watched since categories are recorded. */
    val categories: List<TopicTime>,
    /** How many videos have a category (the rest were watched before it was recorded). */
    val withCategory: Int,
    /** The topics you watch most, by watch time, from titles and tags ([topicsOf]). */
    val topics: List<TopicTime>,
) {
    companion object {
        const val DAYS = 30
        const val TOP_CHANNELS = 8
        const val TOP_CATEGORIES = 8
        const val TOP_TOPICS = 12

        /** A topic counts once it comes up in this many videos. */
        const val MIN_TOPIC_VIDEOS = 2
        val COMPLETION_LABELS = listOf("Finished", "Over half", "Under half", "Barely started")
    }
}

fun watchStats(history: Collection<WatchEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): WatchStats {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val firstDay = today.minusDays(WatchStats.DAYS - 1L)
    val perDay = LongArray(WatchStats.DAYS)
    val byHour = LongArray(24)
    val completion = IntArray(4)
    val channels = HashMap<String, ChannelTime>()

    history.forEach { e ->
        val watched = e.positionMs.coerceIn(0, e.durationMs.takeIf { it > 0 } ?: e.positionMs)
        val at = Instant.ofEpochMilli(e.lastWatchedAt).atZone(zone)
        val day = (at.toLocalDate().toEpochDay() - firstDay.toEpochDay()).toInt()
        if (day in perDay.indices) perDay[day] += watched
        byHour[at.hour] += watched

        completion[
            when {
                e.isWatched -> 0
                e.fraction >= 0.5f -> 1
                e.fraction >= 0.1f -> 2
                else -> 3
            },
        ]++

        val name = e.video.uploader.ifBlank { "Unknown channel" }
        val key = e.video.channelUrl ?: "name:$name"
        val old = channels[key]
        channels[key] = ChannelTime(
            name = name,
            url = e.video.channelUrl,
            watchedMs = (old?.watchedMs ?: 0) + watched,
            videos = (old?.videos ?: 0) + 1,
        )
    }

    return WatchStats(
        videos = history.size,
        watchedMs = history.sumOf { it.positionMs.coerceIn(0, it.durationMs.takeIf { d -> d > 0 } ?: it.positionMs) },
        channels = channels.size,
        finishedShare = if (history.isEmpty()) 0f else completion[0].toFloat() / history.size,
        perDay = perDay.toList(),
        firstDay = firstDay,
        topChannels = channels.values.sortedByDescending { it.watchedMs }.take(WatchStats.TOP_CHANNELS),
        byHour = byHour.toList(),
        completion = completion.toList(),
        categories = rank(history.mapNotNull { e -> e.category?.let { it to e } }.map { (c, e) -> Triple(c, c, e) })
            .take(WatchStats.TOP_CATEGORIES),
        withCategory = history.count { it.category != null },
        topics = topTopics(history),
    )
}

/** Watched time of an entry: how far in it got, never past the end. */
private fun WatchEntry.watchedMs(): Long = positionMs.coerceIn(0, durationMs.takeIf { it > 0 } ?: positionMs)

/**
 * Totals per key, most watched first. Each item is (key, how it's shown, entry); the name
 * shown is the most common way it was written.
 */
private fun rank(items: List<Triple<String, String, WatchEntry>>): List<TopicTime> {
    class Total(var ms: Long = 0, var videos: Int = 0, val spellings: HashMap<String, Int> = HashMap())
    val totals = LinkedHashMap<String, Total>()
    items.forEach { (key, shown, e) ->
        val t = totals.getOrPut(key) { Total() }
        t.ms += e.watchedMs()
        t.videos++
        t.spellings.merge(shown, 1, Int::plus)
    }
    return totals.values
        .map { t -> TopicTime(t.spellings.maxBy { it.value }.key, t.ms, t.videos) }
        .sortedWith(compareByDescending<TopicTime> { it.watchedMs }.thenByDescending { it.videos })
}

private fun topTopics(history: Collection<WatchEntry>): List<TopicTime> =
    rank(history.flatMap { e -> topicsOf(e).map { (key, shown) -> Triple(key, shown, e) } })
        .filter { it.videos >= WatchStats.MIN_TOPIC_VIDEOS }
        .take(WatchStats.TOP_TOPICS)

/**
 * What a video is about, as (key, how it's written): the words of its title and its short
 * tags, each once. Left out: common words and titles' filler ("official", "4K"), the
 * uploader's own name, numbers, and words under 3 letters unless they're capitals (AI, PC).
 */
internal fun topicsOf(e: WatchEntry): Set<Pair<String, String>> {
    val uploader = e.video.uploader.lowercase()
    val uploaderWords = words(uploader).toSet()
    val found = LinkedHashMap<String, String>()
    words(e.video.title).forEach { word ->
        val key = word.lowercase()
        val short = word.length < 3 && !(word.length == 2 && word.all { it.isUpperCase() })
        if (short || key in STOP_WORDS || key in uploaderWords || key.all { it.isDigit() }) return@forEach
        found.putIfAbsent(key, word)
    }
    e.tags.forEach { tag ->
        val text = tag.trim()
        val key = text.lowercase()
        // Short tags only (long ones are sentences), and not the channel's own name.
        if (text.isEmpty() || words(text).size > 3 || key in STOP_WORDS || key.all { it.isDigit() } ||
            key.length < 3 || uploader.isNotEmpty() && (key in uploader || uploader in key)
        ) return@forEach
        found.putIfAbsent(key, text)
    }
    return found.entries.map { it.key to it.value }.toSet()
}

private val WORD_SPLIT = Regex("""[^\p{L}\p{N}+#]+""")
private fun words(text: String): List<String> = text.split(WORD_SPLIT).filter { it.isNotEmpty() }

/** Common English words, and words video titles use whatever they're about. */
private val STOP_WORDS = setOf(
    "the", "and", "for", "are", "but", "not", "you", "your", "yours", "all", "any", "can", "had", "her", "was",
    "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old", "see", "two",
    "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use", "that", "this", "with",
    "from", "they", "them", "their", "there", "then", "than", "what", "when", "where", "which", "while",
    "will", "would", "could", "should", "have", "been", "were", "into", "onto", "about", "after", "before",
    "over", "under", "again", "just", "only", "also", "very", "more", "most", "much", "many", "some", "such",
    "here", "why", "because", "being", "does", "doing", "done", "don", "doesn", "isn", "aren", "wasn", "won",
    "didn", "can't", "cant", "dont", "i'm", "im", "ive", "youre", "it's", "these", "those", "each", "every",
    "other", "another", "same", "own", "off", "down", "back", "still", "even", "ever", "never", "always",
    "really", "thing", "things", "make", "made", "makes", "making", "going", "goes", "gone", "got", "like",
    "know", "want", "need", "look", "looks", "take", "takes", "come", "comes", "time", "times", "year", "years",
    "first", "last", "next", "best", "better", "worst", "good", "bad", "big", "small", "huge", "top", "every",
    "everything", "nothing", "something", "anything", "someone", "everyone", "people", "way", "ways", "part",
    "via", "per", "yet", "yes", "lot", "lots", "vs", "versus", "inside", "without", "within", "through",
    // titles' filler
    "official", "video", "videos", "music", "audio", "lyrics", "lyric", "full", "episode", "episodes", "ep",
    "season", "trailer", "teaser", "clip", "clips", "live", "stream", "streams", "shorts", "short", "hd", "4k",
    "8k", "uhd", "remaster", "remastered", "version", "edit", "feat", "ft", "featuring", "prod", "channel",
    "subscribe", "watch", "watching", "today", "tonight", "week", "breaking", "update", "updates", "news",
    "show", "podcast", "highlights", "explained", "reaction", "reacts", "review", "compilation",
)

/** "45 min", "3 h 5 min", "12 h": watch time, rounded to minutes ("< 1 min" for a few seconds). */
fun formatWatchTime(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return when {
        minutes == 0L && ms > 0 -> "< 1 min"
        h == 0L -> "$m min"
        m == 0L || h >= 100 -> "$h h"
        else -> "$h h $m min"
    }
}
