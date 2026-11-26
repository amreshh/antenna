package com.antenna.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class WatchEntry(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    /** YouTube's "up next" suggestions for this video, used to build the For you tab. */
    val related: List<VideoItem> = emptyList(),
    /** YouTube's category for it ("Music", "Gaming"…), for the Analytics tab; null if not recorded. */
    val category: String? = null,
    /** What SponsorBlock skipped in it, each segment once ([withSkip]), for the Analytics tab. */
    val skipped: List<SkippedSegment> = emptyList(),
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Counts as watched once you're near the end ([reachedEnd]), like YouTube. */
    val isWatched: Boolean
        get() = reachedEnd(positionMs, durationMs)
}

/**
 * Whether [positionMs] is the end of a video [durationMs] long. Same idea as YouTube: 90% in,
 * or in the last 30 seconds once you're past halfway (the end screens of shorter videos).
 * Without the halfway check, a video under 30 seconds counted as watched as soon as it was
 * opened.
 */
fun reachedEnd(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0) return false
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    return fraction >= 0.9f || (fraction >= 0.5f && durationMs - positionMs < 30_000)
}

/** A SponsorBlock segment the player skipped in a video. */
data class SkippedSegment(
    /** SponsorBlock's category ("sponsor", "selfpromo"…). */
    val category: String,
    /** Where it starts: with [category], which segment it is. */
    val startMs: Long,
    /** How much of the video the skip jumped over. */
    val skippedMs: Long,
)

/**
 * This entry with [skip] noted. A segment skipped again (watching the video twice, or going
 * back into it) is still one segment: the longest jump over it is kept.
 */
internal fun WatchEntry.withSkip(skip: SkippedSegment): WatchEntry {
    val i = skipped.indexOfFirst { it.category == skip.category && it.startMs == skip.startMs }
    return when {
        i < 0 -> copy(skipped = skipped + skip)
        skipped[i].skippedMs >= skip.skippedMs -> this
        else -> copy(skipped = skipped.toMutableList().apply { set(i, skip) })
    }
}

/**
 * Local watch history (nothing leaves the phone), in two files in the app's files folder:
 * the history itself (each video and how far you got), and the videos' up next lists, which
 * are most of the size but change only when a video opens.
 *
 * The files are read in the background when the app starts ([loaded] says when that's
 * done), so a long history doesn't hold up the first screen. Anything recorded meanwhile is
 * merged in, and nothing is written until then, so the files are never replaced with a
 * partial history.
 *
 * While a video plays its position is recorded every few seconds, but written at most every
 * [SAVE_DELAY_MS]; the up next lists only when they changed. Removing or clearing history,
 * and [flush] (the player calls it when it goes to the background), write right away.
 * Writes go through [AtomicFile] one at a time, so a file is never half-written.
 */
class WatchHistoryStore private constructor(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "watch_history.json"))
    private val relatedFile = AtomicFile(File(context.filesDir, "watch_related.json"))
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var pendingSave: Job? = null

    /** Keyed by YouTube video id. Empty until [loaded]. */
    private val _entries = MutableStateFlow<Map<String, WatchEntry>>(emptyMap())
    val entries: StateFlow<Map<String, WatchEntry>> = _entries.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** Whether the history has been read from disk; screens wait for it rather than show "nothing yet". */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** The up next lists changed since they were last written. */
    private val relatedChanged = AtomicBoolean(false)

    // Removals before the history finished loading, to apply to it then (guarded by this).
    private val removedEarly = HashSet<String>()
    private var clearedEarly = false

    init {
        io.launch { loadFromDisk() }
    }

    suspend fun awaitLoaded() {
        _loaded.first { it }
    }

    /** This video's entry. Before [loaded], only what was recorded since the app started. */
    fun entryFor(url: String): WatchEntry? = videoIdOf(url)?.let { _entries.value[it] }

    /**
     * Saves the playback position. [related] and [category] are known when a video opens;
     * null keeps what was stored earlier.
     */
    fun record(
        video: VideoItem,
        positionMs: Long,
        durationMs: Long,
        related: List<VideoItem>? = null,
        category: String? = null,
    ) {
        val id = videoIdOf(video.url) ?: return
        var added = false
        _entries.update { map ->
            val old = map[id]
            added = old == null
            val entry = WatchEntry(
                video = video,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs,
                lastWatchedAt = System.currentTimeMillis(),
                related = related ?: old?.related.orEmpty(),
                category = category?.takeIf { it.isNotBlank() } ?: old?.category,
                skipped = old?.skipped.orEmpty(),
            )
            val updated = map + (id to entry)
            // Only a new video or a new up next list can push older ones past the limits;
            // a position update (every few seconds while playing) can't.
            if (old == null || related != null) trim(updated) else updated
        }
        if (added || related != null) relatedChanged.set(true)
        saveSoon()
    }

    /**
     * Notes that SponsorBlock's segment of [category] starting at [startMs] was skipped in
     * [video], jumping [skippedMs] (the Analytics tab counts them). Skipping it again
     * doesn't count twice ([withSkip]).
     */
    fun recordSkip(video: VideoItem, category: String, startMs: Long, skippedMs: Long) {
        val id = videoIdOf(video.url) ?: return
        val skip = SkippedSegment(category, startMs, skippedMs.coerceAtLeast(0))
        var changed = false
        _entries.update { map ->
            val old = map[id] ?: return@update map // taken out of the history meanwhile
            val new = old.withSkip(skip)
            changed = new !== old
            if (changed) map + (id to new) else map
        }
        if (changed) saveSoon()
    }

    fun remove(url: String) {
        val id = videoIdOf(url) ?: return
        synchronized(this) {
            if (!_loaded.value) removedEarly += id
            _entries.update { it - id }
        }
        relatedChanged.set(true)
        flush()
    }

    fun clear() {
        synchronized(this) {
            if (!_loaded.value) clearedEarly = true
            _entries.value = emptyMap()
        }
        relatedChanged.set(true)
        flush()
    }

    /** Writes the history now, instead of when the pending save is due. */
    fun flush() {
        pendingSave?.cancel()
        pendingSave = null
        io.launch { write() }
    }

    /** Keeps the newest [MAX_ENTRIES]; only the newest [MAX_WITH_RELATED] keep their related lists. */
    private fun trim(map: Map<String, WatchEntry>): Map<String, WatchEntry> {
        val sorted = map.entries.sortedByDescending { it.value.lastWatchedAt }.take(MAX_ENTRIES)
        return sorted.mapIndexed { i, (id, e) ->
            id to if (i >= MAX_WITH_RELATED && e.related.isNotEmpty()) e.copy(related = emptyList()) else e
        }.toMap()
    }

    /** Saves in [SAVE_DELAY_MS], unless a save is already due: that one writes the latest state. */
    private fun saveSoon() {
        if (pendingSave?.isActive == true) return
        pendingSave = io.launch {
            delay(SAVE_DELAY_MS)
            write()
        }
    }

    private suspend fun write() {
        awaitLoaded() // never replace the files with a partial history
        writeLock.withLock {
            // Before taking the entries, so a list recorded meanwhile is written next time.
            val relatedToo = relatedChanged.getAndSet(false)
            val entries = _entries.value
            writeFile(file, encodeHistory(entries))
            if (relatedToo && !writeFile(relatedFile, encodeRelated(entries))) relatedChanged.set(true)
        }
    }

    private fun writeFile(target: AtomicFile, text: String): Boolean {
        var out: FileOutputStream? = null
        return try {
            out = target.startWrite()
            out.write(text.toByteArray())
            target.finishWrite(out)
            true
        } catch (e: IOException) {
            out?.let(target::failWrite)
            Log.w(TAG, "Save failed", e)
            false
        }
    }

    private fun loadFromDisk() {
        val decoded = readFiles()
        synchronized(this) {
            _entries.update { early ->
                val disk = if (clearedEarly) emptyMap() else decoded.entries - removedEarly
                trim(disk + early) // anything recorded since the app started is newer
            }
            _loaded.value = true
        }
        // A history from before the up next lists had their own file: write it in two now.
        if (decoded.relatedInline) {
            relatedChanged.set(true)
            flush()
        }
    }

    private fun readFiles(): DecodedHistory {
        val history = try {
            String(file.readFully())
        } catch (_: FileNotFoundException) {
            return DecodedHistory(emptyMap(), relatedInline = false) // nothing watched yet
        } catch (e: IOException) {
            Log.w(TAG, "Load failed", e)
            return DecodedHistory(emptyMap(), relatedInline = false)
        }
        val related = try {
            String(relatedFile.readFully())
        } catch (_: IOException) {
            null // none yet, or unreadable: the history still loads, without up next lists
        }
        return try {
            decodeHistory(history, related).also {
                if (it.skipped > 0) Log.w(TAG, "Skipped ${it.skipped} unreadable entries")
            }
        } catch (e: Exception) {
            // Not a history at all (damaged): keep a copy rather than overwrite it with a new one.
            Log.w(TAG, "History unreadable, kept as ${file.baseFile.name}.unreadable", e)
            runCatching { file.baseFile.copyTo(File(file.baseFile.path + ".unreadable"), overwrite = true) }
            DecodedHistory(emptyMap(), relatedInline = false)
        }
    }

    companion object {
        private const val TAG = "History"
        private const val MAX_ENTRIES = 1000
        private const val MAX_WITH_RELATED = 150
        private const val SAVE_DELAY_MS = 30_000L

        @Volatile
        private var instance: WatchHistoryStore? = null

        fun get(context: Context): WatchHistoryStore =
            instance ?: synchronized(this) {
                instance ?: WatchHistoryStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * What [decodeHistory] read: the entries, whether the up next lists were in the history
 * file (older files), and how many entries couldn't be read.
 */
internal class DecodedHistory(val entries: Map<String, WatchEntry>, val relatedInline: Boolean, val skipped: Int = 0)

/** The history file: every video's entry, without its up next list (see [encodeRelated]). */
internal fun encodeHistory(entries: Map<String, WatchEntry>): String {
    val json = JSONArray()
    entries.values.forEach { e ->
        json.put(
            JSONObject()
                .put("video", e.video.toJson())
                .put("pos", e.positionMs)
                .put("dur", e.durationMs)
                .put("at", e.lastWatchedAt)
                .put("cat", e.category ?: JSONObject.NULL)
                .apply { if (e.skipped.isNotEmpty()) put("skips", skipsToJson(e.skipped)) },
        )
    }
    return json.toString()
}

private fun skipsToJson(skips: List<SkippedSegment>) = JSONArray().apply {
    skips.forEach { put(JSONObject().put("cat", it.category).put("start", it.startMs).put("ms", it.skippedMs)) }
}

/** The skips in a history entry's "skips" (none in older files); one that can't be read is left out. */
private fun skipsFromJson(arr: JSONArray?): List<SkippedSegment> =
    if (arr == null) emptyList()
    else (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        SkippedSegment(o.optString("cat"), o.optLong("start"), o.optLong("ms")).takeIf { it.category.isNotEmpty() }
    }

/** The related file: the up next lists, by video id, of the entries that have one. */
internal fun encodeRelated(entries: Map<String, WatchEntry>): String {
    val json = JSONObject()
    entries.forEach { (id, e) ->
        if (e.related.isNotEmpty()) json.put(id, JSONArray().apply { e.related.forEach { put(it.toJson()) } })
    }
    return json.toString()
}

/**
 * Reads the history file, and the related file if there is one. Older history files have
 * each up next list inline ("related"); those are read too. An entry that can't be read is
 * left out and the rest still load; only a file that isn't a history at all throws.
 */
internal fun decodeHistory(history: String, related: String?): DecodedHistory {
    val arr = JSONArray(history)
    val lists = related?.let { runCatching { JSONObject(it) }.getOrNull() }
    var relatedInline = false
    var skipped = 0
    val entries = HashMap<String, WatchEntry>()
    for (i in 0 until arr.length()) {
        try {
            val o = arr.getJSONObject(i)
            val video = videoFromJson(o.getJSONObject("video"))
            val id = videoIdOf(video.url) ?: continue
            val inline = o.optJSONArray("related")
            if (inline != null && inline.length() > 0) relatedInline = true
            val rel = inline?.takeIf { it.length() > 0 } ?: lists?.optJSONArray(id) ?: JSONArray()
            entries[id] = WatchEntry(
                video = video,
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                lastWatchedAt = o.optLong("at"),
                related = (0 until rel.length()).mapNotNull { j -> runCatching { videoFromJson(rel.getJSONObject(j)) }.getOrNull() },
                category = o.optString("cat").takeIf { it.isNotEmpty() && !o.isNull("cat") },
                skipped = skipsFromJson(o.optJSONArray("skips")),
            )
        } catch (_: Exception) {
            skipped++
        }
    }
    return DecodedHistory(entries, relatedInline, skipped)
}

private val ID_PATTERN = Regex("""(?:v=|youtu\.be/|/shorts/|/live/|/embed/)([\w-]{11})""")

/** YouTube video id from any watch / youtu.be / shorts link. */
fun videoIdOf(url: String): String? =
    youtubeVideoId(url) ?: ID_PATTERN.find(url)?.groupValues?.get(1)

/** The id of a YouTube video link, or null for any other link (no guessing from the URL's shape). */
fun youtubeVideoId(url: String): String? =
    runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(url) }.getOrNull()

/** The canonical link to a video. */
fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

/** A video as JSON, for the files and preferences the app keeps (history, Watch later, the feed). */
internal fun VideoItem.toJson(): JSONObject = JSONObject()
    .put("url", url)
    .put("title", title)
    .put("uploader", uploader)
    .put("duration", durationSeconds)
    .put("thumb", thumbnailUrl ?: JSONObject.NULL)
    .put("uploadedAt", uploadedAtMs)
    .put("uploadedText", uploadedText ?: JSONObject.NULL)
    .put("channelUrl", channelUrl ?: JSONObject.NULL)
    .put("avatar", channelAvatarUrl ?: JSONObject.NULL)

internal fun videoFromJson(o: JSONObject) = VideoItem(
    url = o.getString("url"),
    title = o.optString("title"),
    uploader = o.optString("uploader"),
    durationSeconds = o.optLong("duration"),
    thumbnailUrl = o.optStringOrNull("thumb"),
    uploadedAtMs = o.optLong("uploadedAt"),
    uploadedText = o.optStringOrNull("uploadedText"),
    channelUrl = o.optStringOrNull("channelUrl"),
    channelAvatarUrl = o.optStringOrNull("avatar"),
)

/** The string at [key], or null when it's missing or null (older files lack newer keys). */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
