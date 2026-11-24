package com.antenna.data

import com.antenna.data.StatsFilter.Kind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Time spent on one topic or category, for the Analytics tab. */
data class TopicTime(
    /** What it's grouped and filtered by: a topic in lower case, a category as YouTube writes it. */
    val key: String,
    /** How it's shown: the way it's written most often. */
    val name: String,
    val watchedMs: Long,
    val videos: Int,
)

/** Time spent on one channel, for the Analytics tab's top channels. */
data class ChannelTime(
    /** Which channel: [channelKey] of its videos. */
    val key: String,
    val name: String,
    /** null when its videos were saved without a channel link */
    val url: String?,
    val watchedMs: Long,
    val videos: Int,
)

/** What SponsorBlock skipped of one kind, for the Analytics tab. */
data class SkipTime(
    /** SponsorBlock's category ("sponsor", "selfpromo"…). */
    val category: String,
    val skippedMs: Long,
    val segments: Int,
    /** The videos it was skipped in. */
    val videos: Int,
)

/** Watch time, and the videos it's from. */
data class Share(val watchedMs: Long, val videos: Int)

/** Videos of one length ([WatchStats.LENGTH_LABELS]): watch time, how many, how many finished. */
data class LengthTime(val watchedMs: Long, val videos: Int, val finished: Int)

/** Videos opened from one place. */
data class SourceTime(val source: OpenSource, val watchedMs: Long, val videos: Int)

/**
 * The Analytics tab's numbers, worked out on the phone from the watch history (up to the
 * 1000 videos it keeps).
 *
 * Watch time is how far into each video you got: the history keeps one position per video,
 * so watching something twice, or skipping around, isn't counted separately. It's counted on
 * the day and hour you last watched it. (What you actually played is in the watch log:
 * [Analytics.sittings].)
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
    /** The last 7 days (today among them), and the 7 before. */
    val thisWeek: Share,
    val lastWeek: Share,
    /** The channels you've spent the most time on, most first. */
    val topChannels: List<ChannelTime>,
    /** Channels you subscribe to, and the rest. */
    val subscribed: Share,
    val notSubscribed: Share,
    /** The channels you've watched most without subscribing to them, most first. */
    val unsubscribedChannels: List<ChannelTime>,
    /** Watch time by the hour of the day you watched, 0…23. */
    val byHour: List<Long>,
    /**
     * Watch time by the hour you watched in, over the whole history, for the zoomable chart:
     * by [hourIndex] (only hours with any).
     */
    val hourly: Map<Long, Long>,
    /** How far you get into videos: [finished, over half, under half, barely (under 10%)]. */
    val completion: List<Int>,
    /** By how long the videos are ([LENGTH_LABELS]); only videos whose length is known. */
    val byLength: List<LengthTime>,
    /** By where you opened them from, most first; only videos opened since that's recorded. */
    val bySource: List<SourceTime>,
    val withSource: Int,
    /** By how new videos were when you first watched them ([AGE_LABELS]); only those with an upload date. */
    val byAge: List<Share>,
    val withAge: Int,
    /** Half the videos were newer than this when you first watched them. */
    val medianAgeMs: Long?,
    /** YouTube's categories, by watch time; only videos watched since categories are recorded. */
    val categories: List<TopicTime>,
    /** How many videos have a category (the rest were watched before it was recorded). */
    val withCategory: Int,
    /** The topics you watch most, by watch time, from titles and tags ([topicsOf]). */
    val topics: List<TopicTime>,
    /** What SponsorBlock skipped, by kind, most first; only in videos watched since skips are recorded. */
    val skips: List<SkipTime>,
    /** All of [skips] together: the time skipped, the segments, and the videos they were in. */
    val skippedMs: Long,
    val skippedSegments: Int,
    val videosWithSkips: Int,
) {
    companion object {
        const val DAYS = 30
        const val TOP_CHANNELS = 8
        const val TOP_CATEGORIES = 8
        const val TOP_TOPICS = 12
        const val TOP_UNSUBSCRIBED = 5

        /** A topic counts once it comes up in this many videos. */
        const val MIN_TOPIC_VIDEOS = 2
        val COMPLETION_LABELS = listOf("Finished", "Over half", "Under half", "Barely started")
        val LENGTH_LABELS = listOf("Under 5 min", "5–20 min", "20–60 min", "Over an hour")
        val AGE_LABELS = listOf("The day it came out", "Within a week", "Within a month", "Within a year", "Older")
    }
}

/**
 * Something the Analytics tab can be narrowed to, picked by tapping it in its card: a day, an
 * hour, a channel… [key] says which, the way its [Kind] describes; [label] is how it's shown.
 */
data class StatsFilter(val kind: Kind, val key: String, val label: String) {

    enum class Kind {
        /** Videos last watched on a day: [key] is its epoch day. */
        Day,

        /** Last watched in an hour of the day, "0" to "23". */
        Hour,

        /** From a channel: its [channelKey]. */
        Channel,

        /** From channels you subscribe to ([SUBSCRIBED]) or not ([NOT_SUBSCRIBED]). */
        Subscribed,

        /** By how far you got into them: an index of [WatchStats.COMPLETION_LABELS]. */
        Completion,

        /** By length: an index of [WatchStats.LENGTH_LABELS]. */
        Length,

        /** Opened from somewhere: an [OpenSource]'s name. */
        Source,

        /** By how new they were when you first watched them: an index of [WatchStats.AGE_LABELS]. */
        Age,

        /** About a topic: its key ([topicsOf]), in lower case. */
        Topic,

        /** In one of YouTube's categories, as it writes it. */
        Category,

        /** With a kind of segment skipped: SponsorBlock's category ("sponsor"…). */
        Skipped,
    }

    companion object {
        const val SUBSCRIBED = "subscribed"
        const val NOT_SUBSCRIBED = "other"

        /** [filters] as strings, to keep with a screen's saved state; [restore] reads them back. */
        fun save(filters: List<StatsFilter>): List<String> = filters.flatMap { listOf(it.kind.name, it.key, it.label) }

        fun restore(saved: List<String>): List<StatsFilter> = saved.chunked(3).mapNotNull { part ->
            val kind = Kind.entries.firstOrNull { it.name == part.getOrNull(0) } ?: return@mapNotNull null
            if (part.size == 3) StatsFilter(kind, part[1], part[2]) else null
        }
    }
}

/**
 * An hour of a day as one number: the epoch day × 24 + the hour of the day (local time), so
 * the hours of all days are in order. A day of 23 or 25 hours (the clocks changing) still has
 * 24 of them: the hour skipped is empty, the one repeated counts both.
 */
fun hourIndex(epochDay: Long, hour: Int): Long = epochDay * 24 + hour

/**
 * The card a pick is made in. The watch time chart picks a day, or an hour of a day, so its
 * day and hour are one card's picks: it leaves out both.
 */
private val Kind.card: Kind get() = if (this == Kind.Hour) Kind.Day else this

/**
 * The Analytics tab's numbers with [filters] picked, one of each [Kind] at most. The tiles
 * show [shown], the videos matching every filter. Each card shows [forCard]: the videos
 * matching every filter but its own, so it still lists what else could be picked in it, with
 * its pick marked. A channel, topic or category picked is listed even when it isn't among the
 * top ones.
 */
class Analytics internal constructor(
    val filters: List<StatsFilter>,
    /** The whole history. */
    val all: WatchStats,
    val shown: WatchStats,
    private val cards: Map<Kind, WatchStats>,
    /** From the watch log: how you sit down to watch, with the videos shown; null before anything's recorded. */
    val sittings: SittingStats?,
    /** Subscriptions you haven't watched in a while ([unwatchedSubscriptions]); null when it's too early to tell. */
    val unwatchedSubscriptions: List<Channel>?,
) {
    fun forCard(kind: Kind): WatchStats = cards[kind.card] ?: shown

    /** What's picked in the card for [kind], if anything. */
    fun picked(kind: Kind): StatsFilter? = filters.firstOrNull { it.kind == kind }
}

fun analytics(
    history: Collection<WatchEntry>,
    filters: List<StatsFilter>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    subscriptions: List<Channel> = emptyList(),
    spans: List<WatchSpan> = emptyList(),
): Analytics {
    val firstDay = firstDayOf(now, zone)
    val subscribed = subscriptions.mapTo(HashSet()) { it.url }
    val spansOf = spans.groupBy { it.videoId }
    val facts = history.map { e -> Facts(e, zone, subscribed, spansOf) }
    val tests = filters.map { it to it.test() }
    /** The videos matching every filter but those picked in [card]. */
    fun matching(card: Kind?) = facts.filter { f -> tests.all { (filter, test) -> filter.kind.card == card || test(f) } }
    val all = statsOf(facts, firstDay, subscribed)
    val shownFacts = if (filters.isEmpty()) facts else matching(card = null)
    val shown = if (filters.isEmpty()) all else statsOf(shownFacts, firstDay, subscribed)
    val cards = filters.groupBy { it.kind.card }.mapValues { (card, own) ->
        statsOf(matching(card), firstDay, subscribed, keep = own.first())
    }
    // The sittings of the videos shown: everything recorded, when nothing's picked.
    val shownIds = shownFacts.mapNotNullTo(HashSet()) { it.id }
    val sittings = sittingStats(if (filters.isEmpty()) spans else spans.filter { it.videoId in shownIds }, now, zone)
    return Analytics(filters, all, shown, cards, sittings, unwatchedSubscriptions(subscriptions, history, spans, now))
}

fun watchStats(history: Collection<WatchEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): WatchStats =
    statsOf(history.map { Facts(it, zone, emptySet(), emptyMap()) }, firstDayOf(now, zone), emptySet())

/** The first of the [WatchStats.DAYS] days the tab shows, today last. */
private fun firstDayOf(now: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(WatchStats.DAYS - 1L)

/** Not watched for this long, a subscription counts as no longer watched. */
private const val UNWATCHED_DAYS = 90L
private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * The subscriptions you haven't watched anything from in [UNWATCHED_DAYS] days (and that you
 * subscribed to longer ago than that), A–Z. Null when the history doesn't go back that far:
 * then it can't tell.
 */
fun unwatchedSubscriptions(
    subscriptions: List<Channel>,
    history: Collection<WatchEntry>,
    spans: List<WatchSpan>,
    now: Long,
): List<Channel>? {
    val window = UNWATCHED_DAYS * DAY_MS
    val oldest = minOf(history.minOfOrNull { it.lastWatchedAt } ?: Long.MAX_VALUE, spans.minOfOrNull { it.startedAt } ?: Long.MAX_VALUE)
    if (oldest == Long.MAX_VALUE || now - oldest < window) return null
    val watched = HashSet<String>()
    history.forEach { e -> if (now - e.lastWatchedAt <= window) e.video.channelUrl?.let(watched::add) }
    spans.forEach { s -> if (now - s.startedAt <= window) watched += s.channel }
    return subscriptions.filter { now - it.subscribedAt > window && it.url !in watched }.sortedBy { it.name.lowercase() }
}

/** A history entry with what the Analytics tab counts, groups and filters it by, worked out once. */
private class Facts(val entry: WatchEntry, zone: ZoneId, subscribedUrls: Set<String>, spansOf: Map<String, List<WatchSpan>>) {
    val id = videoIdOf(entry.video.url)
    val watchedMs = entry.watchedMs()
    private val at = Instant.ofEpochMilli(entry.lastWatchedAt).atZone(zone)
    val day = at.toLocalDate().toEpochDay()
    val hour = at.hour
    val completion = entry.completion()
    val channel = entry.channelKey()
    val subscribed = entry.video.channelUrl?.let { it in subscribedUrls } == true
    val length = lengthOf(entry.durationMs)

    /** (key, how it's written) of each of its topics. */
    val topics = topicsOf(entry)

    private val spans = id?.let(spansOf::get).orEmpty()

    /** Where it was first opened from, when that's recorded. */
    val source = spans.filter { it.source != null }.minByOrNull { it.startedAt }?.source

    /** How old it was when you first watched it (the watch log knows, else the history's last time). */
    val ageMs: Long? = entry.video.uploadedAtMs.takeIf { it > 0 }?.let { uploaded ->
        val first = minOf(spans.minOfOrNull { it.startedAt } ?: Long.MAX_VALUE, entry.lastWatchedAt)
        (first - uploaded).coerceAtLeast(0)
    }
    val age = ageMs?.let(::ageOf) ?: -1
}

/** An index of [WatchStats.LENGTH_LABELS], or -1 when the length isn't known. */
internal fun lengthOf(durationMs: Long): Int = when {
    durationMs <= 0 -> -1
    durationMs < 5 * 60_000 -> 0
    durationMs < 20 * 60_000 -> 1
    durationMs < 60 * 60_000 -> 2
    else -> 3
}

/** An index of [WatchStats.AGE_LABELS]. */
internal fun ageOf(ageMs: Long): Int = when {
    ageMs < DAY_MS -> 0
    ageMs < 7 * DAY_MS -> 1
    ageMs < 30 * DAY_MS -> 2
    ageMs < 365 * DAY_MS -> 3
    else -> 4
}

/** Whether a video is one this filter picks. */
private fun StatsFilter.test(): (Facts) -> Boolean {
    val number = key.toLongOrNull()
    return when (kind) {
        Kind.Day -> { f -> f.day == number }
        Kind.Hour -> { f -> f.hour.toLong() == number }
        Kind.Channel -> { f -> f.channel == key }
        Kind.Subscribed -> { f -> f.subscribed == (key == StatsFilter.SUBSCRIBED) }
        Kind.Completion -> { f -> f.completion.toLong() == number }
        Kind.Length -> { f -> f.length.toLong() == number }
        Kind.Source -> { f -> f.source?.name == key }
        Kind.Age -> { f -> f.age.toLong() == number }
        Kind.Topic -> { f -> f.topics.any { it.first == key } }
        Kind.Category -> { f -> f.entry.category == key }
        Kind.Skipped -> { f -> f.entry.skipped.any { it.category == key } }
    }
}

/** The numbers for [facts]; [keep] is what the card they're for has picked, listed even if it's not among the top. */
private fun statsOf(facts: List<Facts>, firstDay: LocalDate, subscribed: Set<String>, keep: StatsFilter? = null): WatchStats {
    val perDay = LongArray(WatchStats.DAYS)
    val videosPerDay = IntArray(WatchStats.DAYS)
    val byHour = LongArray(24)
    val hourly = HashMap<Long, Long>()
    val completion = IntArray(4)
    val channels = HashMap<String, ChannelTime>()
    val lengthMs = LongArray(WatchStats.LENGTH_LABELS.size)
    val lengthVideos = IntArray(WatchStats.LENGTH_LABELS.size)
    val lengthFinished = IntArray(WatchStats.LENGTH_LABELS.size)
    val ageMs = LongArray(WatchStats.AGE_LABELS.size)
    val ageVideos = IntArray(WatchStats.AGE_LABELS.size)
    val sources = HashMap<OpenSource, Share>()
    val first = firstDay.toEpochDay()

    facts.forEach { f ->
        val day = f.day - first
        if (day >= 0 && day < WatchStats.DAYS) {
            perDay[day.toInt()] += f.watchedMs
            videosPerDay[day.toInt()]++
        }
        byHour[f.hour] += f.watchedMs
        if (f.watchedMs > 0) hourly.merge(hourIndex(f.day, f.hour), f.watchedMs, Long::plus)
        completion[f.completion]++
        val old = channels[f.channel]
        channels[f.channel] = ChannelTime(
            key = f.channel,
            name = f.entry.video.uploader.ifBlank { "Unknown channel" },
            url = f.entry.video.channelUrl,
            watchedMs = (old?.watchedMs ?: 0) + f.watchedMs,
            videos = (old?.videos ?: 0) + 1,
        )
        if (f.length >= 0) {
            lengthMs[f.length] += f.watchedMs
            lengthVideos[f.length]++
            if (f.completion == 0) lengthFinished[f.length]++
        }
        if (f.age >= 0) {
            ageMs[f.age] += f.watchedMs
            ageVideos[f.age]++
        }
        f.source?.let { s -> sources[s] = (sources[s] ?: Share(0, 0)).let { Share(it.watchedMs + f.watchedMs, it.videos + 1) } }
    }

    fun kept(kind: Kind) = keep?.takeIf { it.kind == kind }?.key
    val keptChannel = kept(Kind.Channel)
    val keptCategory = kept(Kind.Category)
    val keptTopic = kept(Kind.Topic)
    val skips = skipsOf(facts)
    fun share(of: List<Facts>) = Share(of.sumOf { it.watchedMs }, of.size)
    val (fromSubscribed, fromOthers) = facts.partition { it.subscribed }
    // The last 7 days are the last 7 of the [DAYS]; the 7 before, the 7 before those.
    fun week(from: Int) = Share((from until from + 7).sumOf { perDay[it] }, (from until from + 7).sumOf { videosPerDay[it] })
    val ages = facts.mapNotNull { it.ageMs }.sorted()
    return WatchStats(
        videos = facts.size,
        watchedMs = facts.sumOf { it.watchedMs },
        channels = channels.size,
        finishedShare = if (facts.isEmpty()) 0f else completion[0].toFloat() / facts.size,
        perDay = perDay.toList(),
        firstDay = firstDay,
        thisWeek = week(WatchStats.DAYS - 7),
        lastWeek = week(WatchStats.DAYS - 14),
        topChannels = channels.values.sortedByDescending { it.watchedMs }
            .topWith(WatchStats.TOP_CHANNELS) { it.key == keptChannel },
        subscribed = share(fromSubscribed),
        notSubscribed = share(fromOthers),
        unsubscribedChannels = channels.values
            .filter { it.url != null && it.url !in subscribed }
            .sortedByDescending { it.watchedMs }
            .take(WatchStats.TOP_UNSUBSCRIBED),
        byHour = byHour.toList(),
        hourly = hourly,
        completion = completion.toList(),
        byLength = lengthMs.indices.map { LengthTime(lengthMs[it], lengthVideos[it], lengthFinished[it]) },
        bySource = sources.map { (s, share) -> SourceTime(s, share.watchedMs, share.videos) }
            .sortedWith(compareByDescending<SourceTime> { it.watchedMs }.thenBy { it.source.ordinal }),
        withSource = facts.count { it.source != null },
        byAge = ageMs.indices.map { Share(ageMs[it], ageVideos[it]) },
        withAge = ages.size,
        medianAgeMs = ages.getOrNull(ages.size / 2),
        categories = rank(facts.mapNotNull { f -> f.entry.category?.let { Triple(it, it, f.watchedMs) } })
            .topWith(WatchStats.TOP_CATEGORIES) { it.key == keptCategory },
        withCategory = facts.count { it.entry.category != null },
        topics = rank(facts.flatMap { f -> f.topics.map { (key, shown) -> Triple(key, shown, f.watchedMs) } })
            .filter { it.videos >= WatchStats.MIN_TOPIC_VIDEOS || it.key == keptTopic }
            .topWith(WatchStats.TOP_TOPICS) { it.key == keptTopic },
        skips = skips,
        skippedMs = skips.sumOf { it.skippedMs },
        skippedSegments = skips.sumOf { it.segments },
        videosWithSkips = facts.count { it.entry.skipped.isNotEmpty() },
    )
}

/** What SponsorBlock skipped in [facts], by kind, the most time first. */
private fun skipsOf(facts: List<Facts>): List<SkipTime> {
    class Total(var ms: Long = 0, var segments: Int = 0, var videos: Int = 0)
    val totals = HashMap<String, Total>()
    facts.forEach { f ->
        f.entry.skipped.groupBy { it.category }.forEach { (category, segments) ->
            val t = totals.getOrPut(category) { Total() }
            t.ms += segments.sumOf { it.skippedMs }
            t.segments += segments.size
            t.videos++
        }
    }
    return totals.map { (category, t) -> SkipTime(category, t.ms, t.segments, t.videos) }
        .sortedWith(compareByDescending<SkipTime> { it.skippedMs }.thenBy { it.category })
}

/** The first [n], then the one [isKept] picks if it's further down. */
private fun <T> List<T>.topWith(n: Int, isKept: (T) -> Boolean): List<T> {
    val top = take(n)
    if (top.any(isKept)) return top
    return top + (drop(n).firstOrNull(isKept) ?: return top)
}

/**
 * Which channel a watched video is from: its link, or for videos saved without one, its
 * name. The Analytics tab groups, and filters, by it.
 */
fun WatchEntry.channelKey(): String = video.channelKey()

/** Which channel a video is from, the same way as [WatchEntry.channelKey]. */
fun VideoItem.channelKey(): String = channelUrl ?: "$NAME_KEY${uploader.ifBlank { "Unknown channel" }}"

/** The channel's link in a [channelKey], or null for a channel known only by its name. */
fun channelUrlOfKey(key: String): String? = key.takeUnless { it.startsWith(NAME_KEY) }

private const val NAME_KEY = "name:"

/** How far into it you got, as an index of [WatchStats.COMPLETION_LABELS]. */
internal fun WatchEntry.completion(): Int = when {
    isWatched -> 0
    fraction >= 0.5f -> 1
    fraction >= 0.1f -> 2
    else -> 3
}

/** Watched time of an entry: how far in it got, never past the end. */
private fun WatchEntry.watchedMs(): Long = positionMs.coerceIn(0, durationMs.takeIf { it > 0 } ?: positionMs)

/**
 * Totals per key, most watched first. Each item is (key, how it's written, watch time); a key
 * is shown the way it's written most often.
 */
private fun rank(items: List<Triple<String, String, Long>>): List<TopicTime> {
    class Total(var ms: Long = 0, var videos: Int = 0, val spellings: HashMap<String, Int> = HashMap())
    val totals = LinkedHashMap<String, Total>()
    items.forEach { (key, shown, ms) ->
        val t = totals.getOrPut(key) { Total() }
        t.ms += ms
        t.videos++
        t.spellings.merge(shown, 1, Int::plus)
    }
    return totals.map { (key, t) -> TopicTime(key, t.spellings.maxBy { it.value }.key, t.ms, t.videos) }
        .sortedWith(compareByDescending<TopicTime> { it.watchedMs }.thenByDescending { it.videos })
}

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
