package com.example.antenna.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/*
 * Guessing which videos you'll open next, so Prefetcher can fetch their details before you
 * tap them, and keeping score: every guess is checked against what you open, fetched or
 * not. The score decides what's guessed (which kinds of guess, and which channels, tend to
 * be opened) and how many guesses are fetched (none, while they keep missing).
 */

/** Where a guess came from. */
enum class GuessReason {
    /** A video you started lately and didn't finish. */
    Unfinished,

    /** Among the newest in Watch later. */
    WatchLater,

    /** Near the top of For you. */
    ForYou,

    /** A new upload in Subscriptions. */
    NewUpload,

    /**
     * For a channel you're working through in order: the oldest of its uploads after the
     * newest one you've finished ([nextInLine]).
     */
    NextInLine,
}

/** A video that could be guessed: where it came from, and how far down that list it is (0 at the top). */
data class Candidate(val video: VideoItem, val reason: GuessReason, val position: Int)

/** One guess, and whether it was right. */
data class Guess(
    val videoId: String,
    val at: Long,
    val reason: GuessReason,
    /** Its video's channel ([channelKey]). */
    val channel: String,
    /** 1 for the likeliest of its round, then 2, 3. */
    val rank: Int,
    /** Its details were fetched. If not, it was only noted, to see whether it would have been right. */
    val fetched: Boolean,
    /** Opened within [GuessLog.WINDOW_MS] of being guessed. */
    val used: Boolean = false,
)

/** A video you opened: whether a guess had it ready, and how long its details took (-1 until it started). */
data class Opened(val videoId: String, val at: Long, val ready: Boolean, val detailsMs: Long = -1)

/** The guesses and opened videos of the last [KEEP_MS]. */
data class GuessLog(
    val guesses: List<Guess> = emptyList(),
    val opens: List<Opened> = emptyList(),
    /** Nothing is fetched before this time: YouTube was limiting requests. */
    val pausedUntil: Long = 0,
) {
    companion object {
        /**
         * How long a guess counts: as long as the app keeps a video's details
         * (YouTubeRepository). Opened later, it wouldn't have been ready: a miss.
         */
        const val WINDOW_MS = 30 * 60 * 1000L

        const val KEEP_MS = 30 * 24 * 60 * 60 * 1000L
        const val MAX_RECORDS = 500

        /** Guesses made each time, the likeliest first. At most these many are fetched. */
        const val GUESSES = 3

        /** How many of a rank's latest settled guesses decide whether it's fetched. */
        const val RECENT = 20

        /** With fewer settled guesses at a rank than this it's too early to tell: only the first is fetched. */
        const val MIN_SETTLED = 8

        /** A rank's guesses are fetched while at least this share of its recent ones are opened. */
        const val FETCH_RATE = 0.2

        /** The usual wait for a video's details, before any has been measured (on the emulator: 1.2–1.4 s). */
        const val TYPICAL_DETAILS_MS = 1_300L
    }
}

/** Settled: opened, or too old to be (a miss). */
fun Guess.settled(now: Long): Boolean = used || now - at > GuessLog.WINDOW_MS

// --- What to guess ---------------------------------------------------------------------

/** Per kind of candidate. */
private const val PER_SOURCE = 5

/** Started this recently, and between 5% and 90% of the way in: unfinished. */
private const val UNFINISHED_MS = 14 * 24 * 60 * 60 * 1000L

/**
 * What could be guessed, from what the app has at hand: videos you started lately and
 * didn't finish, the newest in Watch later, the top of For you, and the newest uploads in
 * Subscriptions (not Shorts).
 */
fun candidates(
    history: Collection<WatchEntry>,
    watchLater: List<VideoItem>,
    forYou: List<VideoItem>,
    feed: List<VideoItem>,
    now: Long,
): List<Candidate> {
    val unfinished = history
        .filter { !it.isWatched && it.fraction in 0.05f..0.9f && now - it.lastWatchedAt in 0..UNFINISHED_MS }
        .sortedByDescending { it.lastWatchedAt }
        .map { it.video }
    val newest = feed.filterNot { it.isShort }.sortedByDescending { it.uploadedAtMs }
    return listOf(
        GuessReason.Unfinished to unfinished,
        GuessReason.WatchLater to watchLater,
        GuessReason.ForYou to forYou,
        GuessReason.NewUpload to newest,
        GuessReason.NextInLine to nextInLine(history, feed),
    ).flatMap { (reason, videos) -> videos.take(PER_SOURCE).mapIndexed { i, video -> Candidate(video, reason, i) } }
}

/**
 * For each channel in [feed] you're behind on, the video after the last one you finished:
 * the oldest of its uploads (not Shorts) newer than the newest upload of it you've finished,
 * that you haven't finished. The channels you finished one of most recently come first.
 *
 * A channel is left out when you've watched none of its videos, when you're up to date, or
 * when you're further behind than [feed] goes back (its RSS has the last 15 or so uploads):
 * then the next one isn't in it, and the oldest that is would be a guess too far ahead.
 */
fun nextInLine(history: Collection<WatchEntry>, feed: List<VideoItem>): List<VideoItem> {
    val finished = history.filter { it.isWatched }
    val finishedIds = finished.mapNotNullTo(HashSet()) { videoIdOf(it.video.url) }
    return feed.filterNot { it.isShort || it.uploadedAtMs <= 0 }
        .groupBy { it.channelUrl ?: return@groupBy null }
        .mapNotNull { (channel, uploads) ->
            if (channel == null) return@mapNotNull null
            // The newest upload of the channel you've finished, and when you finished it.
            val last = finished.filter { it.video.channelUrl == channel && it.video.uploadedAtMs > 0 }
                .maxByOrNull { it.video.uploadedAtMs } ?: return@mapNotNull null
            val oldestInFeed = uploads.minOf { it.uploadedAtMs }
            if (last.video.uploadedAtMs < oldestInFeed) return@mapNotNull null // behind beyond the feed
            val next = uploads.filter { it.uploadedAtMs > last.video.uploadedAtMs && videoIdOf(it.url) !in finishedIds }
                .minByOrNull { it.uploadedAtMs } ?: return@mapNotNull null
            next to last.lastWatchedAt
        }
        .sortedByDescending { it.second }
        .map { it.first }
}

/**
 * The candidates, likeliest first, each video once and none you've finished: how likely
 * its channel is to be watched at this hour ([channelHabits]), times how often guesses of
 * its kind and of its channel have been opened, less the further down its list it is.
 */
fun rankCandidates(
    candidates: List<Candidate>,
    history: Collection<WatchEntry>,
    log: GuessLog,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Candidate> {
    val habits = channelHabits(history, now, zone)
    val settled = log.guesses.filter { it.settled(now) }
    val byReason = settled.groupBy { it.reason }
    val byChannel = settled.groupBy { it.channel }
    val finished = history.filter { it.isWatched }.mapNotNullTo(HashSet()) { videoIdOf(it.video.url) }
    return candidates
        .mapNotNull { c -> videoIdOf(c.video.url)?.let { id -> Triple(c, id, c.video.channelKey()) } }
        .filter { (_, id, _) -> id !in finished }
        .map { (c, id, channel) ->
            val score = habits.of(channel) * openedRate(byReason[c.reason]) * openedRate(byChannel[channel]) / (1 + 0.5 * c.position)
            Triple(c, id, score)
        }
        .sortedByDescending { it.third }
        .distinctBy { it.second }
        .map { it.first }
}

/**
 * How often guesses were opened, nudged towards half when there are few: no guesses count
 * as 1 in 2, and one miss doesn't rule anything out.
 */
private fun openedRate(guesses: List<Guess>?): Double =
    ((guesses?.count { it.used } ?: 0) + 1.0) / ((guesses?.size ?: 0) + 2.0)

/** How likely each channel is to be watched now; see [channelHabits]. */
class Habits internal constructor(
    private val nearNow: Map<String, Double>,
    private val nearTotal: Double,
    private val anytime: Map<String, Int>,
    private val anytimeTotal: Int,
) {
    fun of(channel: String): Double {
        val share = (anytime[channel] ?: 0).toDouble() / anytimeTotal.coerceAtLeast(1)
        return ((nearNow[channel] ?: 0.0) + BLEND * share) / (nearTotal + BLEND) + FLOOR
    }

    private companion object {
        /** How many videos' worth the share at any hour counts for, when there are few around this hour. */
        const val BLEND = 2.0

        /** A channel you haven't watched can still be guessed, just last. */
        const val FLOOR = 0.02
    }
}

/** History this recent says what you watch at this hour. */
private const val HABIT_DAYS = 60L

/**
 * How likely each channel is to be watched now, from the watch history (when you last watched
 * each video, in the last [HABIT_DAYS] days): its share of the videos watched around this
 * hour of the day, an hour either side counting half, blended with its share at any hour.
 */
fun channelHabits(history: Collection<WatchEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Habits {
    val hourNow = Instant.ofEpochMilli(now).atZone(zone).hour
    val nearNow = HashMap<String, Double>()
    var nearTotal = 0.0
    val anytime = HashMap<String, Int>()
    var anytimeTotal = 0
    history.forEach { e ->
        if (now - e.lastWatchedAt !in 0..HABIT_DAYS * 24 * 60 * 60 * 1000) return@forEach
        val channel = e.channelKey()
        anytime.merge(channel, 1, Int::plus)
        anytimeTotal++
        val apart = abs(Instant.ofEpochMilli(e.lastWatchedAt).atZone(zone).hour - hourNow).let { minOf(it, 24 - it) }
        val weight = when (apart) {
            0 -> 1.0
            1 -> 0.5
            else -> 0.0
        }
        if (weight > 0) {
            nearNow.merge(channel, weight, Double::plus)
            nearTotal += weight
        }
    }
    return Habits(nearNow, nearTotal, anytime, anytimeTotal)
}

/**
 * How many of a round's guesses to fetch: the leading ranks whose latest settled guesses
 * (fetched or only noted: both are checked) were opened at least [GuessLog.FETCH_RATE] of
 * the time. Before a rank has [GuessLog.MIN_SETTLED] of them, only the first is fetched.
 * None while paused. So guessing wrong stops the fetching, and guessing right (guesses are
 * still made, and checked) starts it again.
 */
fun fetchCount(log: GuessLog, now: Long): Int {
    if (now < log.pausedUntil) return 0
    var count = 0
    for (rank in 1..GuessLog.GUESSES) {
        val recent = log.guesses.filter { it.rank == rank && it.settled(now) }.sortedByDescending { it.at }.take(GuessLog.RECENT)
        val fetch = if (recent.size < GuessLog.MIN_SETTLED) rank == 1
        else recent.count { it.used } >= GuessLog.FETCH_RATE * recent.size
        if (!fetch) break
        count = rank
    }
    return count
}

// --- Keeping score -----------------------------------------------------------------------

fun GuessLog.guessed(new: List<Guess>, now: Long): GuessLog = copy(guesses = guesses + new).trimmed(now)

/** [guess] turned out not to be fetched after all (the video's gone, or no connection). */
fun GuessLog.notFetched(guess: Guess): GuessLog =
    copy(guesses = guesses.map { if (it.videoId == guess.videoId && it.at == guess.at) it.copy(fetched = false) else it })

/**
 * [videoId] was opened: its guesses of the last [GuessLog.WINDOW_MS] were right, and it was
 * ready if one of them was fetched.
 */
fun GuessLog.opened(videoId: String, now: Long): GuessLog {
    var ready = false
    val marked = guesses.map { g ->
        if (g.videoId != videoId || g.used || now - g.at !in 0..GuessLog.WINDOW_MS) return@map g
        if (g.fetched) ready = true
        g.copy(used = true)
    }
    return copy(guesses = marked, opens = opens + Opened(videoId, now, ready)).trimmed(now)
}

/** How long [videoId]'s details took when it was opened (lately). */
fun GuessLog.started(videoId: String, detailsMs: Long, now: Long): GuessLog {
    val i = opens.indexOfLast { it.videoId == videoId && it.detailsMs < 0 && now - it.at in 0..10 * 60 * 1000L }
    if (i < 0) return this
    return copy(opens = opens.toMutableList().apply { set(i, get(i).copy(detailsMs = detailsMs)) })
}

private fun GuessLog.trimmed(now: Long) = copy(
    guesses = guesses.filter { now - it.at <= GuessLog.KEEP_MS }.takeLast(GuessLog.MAX_RECORDS),
    opens = opens.filter { now - it.at <= GuessLog.KEEP_MS }.takeLast(GuessLog.MAX_RECORDS),
)

// --- The numbers -------------------------------------------------------------------------

/** For the Analytics tab: how the guessing went, over the last 30 days. */
data class GuessStats(
    /** Videos opened, and how many of them were ready. */
    val opened: Int,
    val ready: Int,
    /** About how much waiting the ready ones saved. */
    val savedMs: Long,
    /** Fetched guesses that are settled, and how many of them were opened. */
    val fetched: Int,
    val used: Int,
    /** The same by where the guesses came from, the most fetched first. */
    val byReason: List<ReasonScore>,
    /** How many it fetches now, each time the app opens. */
    val fetching: Int,
    /** Its latest settled first guesses, fetched or not, and how many were opened: why it fetches none. */
    val recent: Int,
    val recentUsed: Int,
    val pausedUntil: Long,
)

data class ReasonScore(val reason: GuessReason, val fetched: Int, val used: Int)

fun guessStats(log: GuessLog, now: Long): GuessStats {
    val opens = log.opens.filter { now - it.at <= GuessLog.KEEP_MS }
    // A ready video saved the usual wait: the median of the others' (the ones measured).
    val waits = opens.filter { !it.ready && it.detailsMs >= 0 }.map { it.detailsMs }.sorted()
    val usual = if (waits.isEmpty()) GuessLog.TYPICAL_DETAILS_MS else waits[waits.size / 2]
    val saved = opens.filter { it.ready && it.detailsMs >= 0 }.sumOf { (usual - it.detailsMs).coerceAtLeast(0) }
    val settled = log.guesses.filter { it.fetched && it.settled(now) }
    val recent = log.guesses.filter { it.rank == 1 && it.settled(now) }.sortedByDescending { it.at }.take(GuessLog.RECENT)
    return GuessStats(
        opened = opens.size,
        ready = opens.count { it.ready },
        savedMs = saved,
        fetched = settled.size,
        used = settled.count { it.used },
        byReason = settled.groupBy { it.reason }
            .map { (reason, guesses) -> ReasonScore(reason, guesses.size, guesses.count { it.used }) }
            .sortedWith(compareByDescending<ReasonScore> { it.fetched }.thenBy { it.reason.ordinal }),
        fetching = fetchCount(log, now),
        recent = recent.size,
        recentUsed = recent.count { it.used },
        pausedUntil = log.pausedUntil,
    )
}

// --- The file ------------------------------------------------------------------------------

internal fun GuessLog.toJson(): String = JSONObject()
    .put(
        "guesses",
        JSONArray().apply {
            guesses.forEach { g ->
                put(
                    JSONObject().put("id", g.videoId).put("at", g.at).put("reason", g.reason.name).put("channel", g.channel)
                        .put("rank", g.rank).put("fetched", g.fetched).put("used", g.used),
                )
            }
        },
    )
    .put(
        "opens",
        JSONArray().apply {
            opens.forEach { o -> put(JSONObject().put("id", o.videoId).put("at", o.at).put("ready", o.ready).put("details", o.detailsMs)) }
        },
    )
    .put("pausedUntil", pausedUntil)
    .toString()

/** The log as saved; a record that can't be read is left out. */
internal fun guessLogFromJson(text: String): GuessLog {
    val o = JSONObject(text)
    val guesses = o.optJSONArray("guesses") ?: JSONArray()
    val opens = o.optJSONArray("opens") ?: JSONArray()
    return GuessLog(
        guesses = (0 until guesses.length()).mapNotNull { i ->
            runCatching {
                val g = guesses.getJSONObject(i)
                Guess(
                    videoId = g.getString("id"),
                    at = g.getLong("at"),
                    reason = GuessReason.valueOf(g.getString("reason")),
                    channel = g.optString("channel"),
                    rank = g.optInt("rank", 1),
                    fetched = g.optBoolean("fetched"),
                    used = g.optBoolean("used"),
                )
            }.getOrNull()
        },
        opens = (0 until opens.length()).mapNotNull { i ->
            runCatching {
                val p = opens.getJSONObject(i)
                Opened(p.getString("id"), p.getLong("at"), p.optBoolean("ready"), p.optLong("details", -1))
            }.getOrNull()
        },
        pausedUntil = o.optLong("pausedUntil"),
    )
}
