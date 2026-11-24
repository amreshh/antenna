package com.antenna.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where you opened a video from. */
enum class OpenSource { ForYou, Subscriptions, WatchLater, History, Search, Channel, UpNext, Link }

/**
 * One stretch of watching: from where a video started playing to where it stopped (a pause,
 * a seek, a SponsorBlock skip, another video). Unlike the history, which keeps how far into
 * each video you got and when you last opened it, these add up to what you actually played,
 * and when: rewatches count, skipped parts don't.
 */
data class WatchSpan(
    val videoId: String,
    /** Its video's channel ([channelKey]). */
    val channel: String,
    /** When it started playing. */
    val startedAt: Long,
    /** The part of the video it played. */
    val fromMs: Long,
    val toMs: Long,
    /** Where the video was opened from; null when that isn't known (reopened after Android closed the app). */
    val source: OpenSource?,
) {
    val playedMs: Long get() = (toMs - fromMs).coerceAtLeast(0)
}

/**
 * Every stretch of watching (WatchSpan), on the phone, in two files in the app's files:
 * the stretches so far, one line each, only ever added to; and the one playing now, saved
 * every few seconds, so a crash loses at most those seconds (it's added when the app next
 * starts). Kept for [KEEP_DAYS]. Removing a video from the history removes its stretches
 * ([remove]), clearing the history clears them all ([clear]).
 */
class WatchLogStore private constructor(context: Context) {

    private val file = File(context.filesDir, "watch_log.jsonl")
    private val playing = File(context.filesDir, "watch_span.json")

    /** One change at a time, in the order they come; the files are read first. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _spans = MutableStateFlow<List<WatchSpan>>(emptyList())
    val spans: StateFlow<List<WatchSpan>> = _spans.asStateFlow()

    init {
        io.launch { load() }
    }

    /** A stretch has ended (shorter than [MIN_SPAN_MS] is left out: a quick seek, a stutter). */
    fun add(span: WatchSpan) {
        io.launch {
            playing.delete()
            if (span.playedMs < MIN_SPAN_MS) return@launch
            try {
                FileOutputStream(file, true).use { it.write((span.toJson() + "\n").toByteArray()) }
            } catch (e: IOException) {
                Log.w(TAG, "Save failed", e)
            }
            _spans.update { it + span }
        }
    }

    /** The stretch playing now, as far as it's got. */
    fun checkpoint(span: WatchSpan) {
        io.launch {
            try {
                playing.writeText(span.toJson())
            } catch (e: IOException) {
                Log.w(TAG, "Checkpoint failed", e)
            }
        }
    }

    /** Takes out [videoId]'s stretches (it was removed from the history). */
    fun remove(videoId: String) {
        io.launch {
            if (_spans.value.none { it.videoId == videoId }) return@launch
            _spans.update { spans -> spans.filterNot { it.videoId == videoId } }
            rewrite(_spans.value)
        }
    }

    /** Takes out every stretch (the history was cleared). */
    fun clear() {
        io.launch {
            _spans.value = emptyList()
            file.delete()
            playing.delete()
        }
    }

    private fun load() {
        val read = try {
            file.readLines().mapNotNull(::spanFromJson) // a line cut short by a crash is skipped
        } catch (_: IOException) {
            emptyList() // nothing watched yet
        }
        // The one that was playing when the app was closed, as far as it got.
        val recovered = runCatching { spanFromJson(playing.readText()) }.getOrNull()?.takeIf { it.playedMs >= MIN_SPAN_MS }
        playing.delete()
        val now = System.currentTimeMillis()
        val kept = (read + listOfNotNull(recovered)).filter { now - it.startedAt <= KEEP_DAYS * DAY_MS }
        if (kept.size != read.size) rewrite(kept)
        _spans.value = kept
    }

    /** The whole file again, written next to it and then put in its place, so it's never half-written. */
    private fun rewrite(spans: List<WatchSpan>) {
        val next = File(file.path + ".new")
        try {
            next.writeText(spans.joinToString("") { it.toJson() + "\n" })
            if (!next.renameTo(file)) throw IOException("couldn't replace ${file.name}")
        } catch (e: IOException) {
            Log.w(TAG, "Save failed", e)
            next.delete()
        }
    }

    companion object {
        private const val TAG = "WatchLog"
        private const val KEEP_DAYS = 400L
        private const val MIN_SPAN_MS = 1_000L

        @Volatile
        private var instance: WatchLogStore? = null

        fun get(context: Context): WatchLogStore =
            instance ?: synchronized(this) {
                instance ?: WatchLogStore(context.applicationContext).also { instance = it }
            }
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Takes [url]'s video out of the history, and out of everything else recorded about it. */
fun forgetWatched(context: Context, url: String) {
    WatchHistoryStore.get(context).remove(url)
    videoIdOf(url)?.let { id ->
        WatchLogStore.get(context).remove(id)
        Prefetcher.get(context).forget(id)
    }
}

/** Clears the history, and everything else recorded about what was watched. */
fun clearWatched(context: Context) {
    WatchHistoryStore.get(context).clear()
    WatchLogStore.get(context).clear()
    Prefetcher.get(context).clear()
}

internal fun WatchSpan.toJson(): String = JSONObject()
    .put("v", videoId)
    .put("c", channel)
    .put("at", startedAt)
    .put("from", fromMs)
    .put("to", toMs)
    .put("src", source?.name ?: JSONObject.NULL)
    .toString()

/** A line of the log, or null when it can't be read. */
internal fun spanFromJson(line: String): WatchSpan? = runCatching {
    val o = JSONObject(line)
    WatchSpan(
        videoId = o.getString("v"),
        channel = o.optString("c"),
        startedAt = o.getLong("at"),
        fromMs = o.getLong("from"),
        toMs = o.getLong("to"),
        source = if (o.isNull("src")) null else OpenSource.entries.firstOrNull { it.name == o.optString("src") },
    )
}.getOrNull()

// --- Sittings ------------------------------------------------------------------------------

/** What the stretches of watching say: how you sit down to watch. */
data class SittingStats(
    /** Sittings in the last [SITTING_DAYS] days, what they played, and how many videos they had in all. */
    val sittings: Int,
    val playedMs: Long,
    val videos: Int,
    /** Days in a row with something watched: the most, and the run up to today (or yesterday). */
    val longestStreak: Int,
    val currentStreak: Int,
    /** Videos with part of them played more than once, in the last [SITTING_DAYS] days. */
    val rewatched: Int,
    /** The first stretch recorded: these numbers count from then. */
    val since: Long,
)

/** A pause longer than this starts a new sitting. */
private const val SITTING_GAP_MS = 30 * 60 * 1000L
const val SITTING_DAYS = 30

/** Playing a part again for longer than this makes a video rewatched (not a few seconds back). */
private const val REWATCH_MS = 30_000L

/** The sittings in [spans] (null without any): stretches less than half an hour apart are one sitting. */
fun sittingStats(spans: List<WatchSpan>, now: Long, zone: ZoneId = ZoneId.systemDefault()): SittingStats? {
    if (spans.isEmpty()) return null
    val sorted = spans.sortedBy { it.startedAt }
    val recent = sorted.filter { now - it.startedAt in 0..SITTING_DAYS * DAY_MS }

    var sittings = 0
    var videos = 0
    var lastEnd = Long.MIN_VALUE
    val inSitting = HashSet<String>()
    recent.forEach { s ->
        if (lastEnd == Long.MIN_VALUE || s.startedAt - lastEnd > SITTING_GAP_MS) {
            sittings++
            videos += inSitting.size
            inSitting.clear()
        }
        inSitting += s.videoId
        lastEnd = maxOf(lastEnd, s.startedAt + s.playedMs)
    }
    videos += inSitting.size

    // Streaks of days, over everything recorded.
    val days = sorted.mapTo(sortedSetOf<LocalDate>()) { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    days.forEach { day ->
        run = if (previous?.plusDays(1) == day) run + 1 else 1
        longest = maxOf(longest, run)
        previous = day
    }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val current = if (previous == today || previous == today.minusDays(1)) run else 0

    // Rewatched: the parts played add up to more than the parts of the video they cover.
    val rewatched = recent.groupBy { it.videoId }.count { (_, parts) ->
        val played = parts.sumOf { it.playedMs }
        var covered = 0L
        var end = Long.MIN_VALUE
        parts.sortedBy { it.fromMs }.forEach { p ->
            val start = maxOf(p.fromMs, end)
            if (p.toMs > start) covered += p.toMs - start
            end = maxOf(end, p.toMs)
        }
        played - covered > REWATCH_MS
    }

    return SittingStats(
        sittings = sittings,
        playedMs = recent.sumOf { it.playedMs },
        videos = videos,
        longestStreak = longest,
        currentStreak = current,
        rewatched = rewatched,
        since = sorted.first().startedAt,
    )
}
