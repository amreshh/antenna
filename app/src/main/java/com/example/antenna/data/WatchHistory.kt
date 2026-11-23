package com.example.antenna.data

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

data class WatchEntry(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    /** YouTube's "up next" suggestions for this video, used to build the For you tab. */
    val related: List<VideoItem> = emptyList(),
    /** YouTube's category for it ("Music", "Gaming"…), for the Analytics tab; null if not recorded. */
    val category: String? = null,
    /** The uploader's tags for it (the first [WatchHistoryStore.MAX_TAGS]), for the Analytics tab's topics. */
    val tags: List<String> = emptyList(),
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Same idea as YouTube: counts as watched once you're near the end, meaning 90% in, or
     * in the last 30 seconds once you're past halfway (the end screens of shorter videos).
     * Without the halfway check, a video under 30 seconds counted as watched as soon as
     * it was opened.
     */
    val isWatched: Boolean
        get() = durationMs > 0 &&
            (fraction >= 0.9f || (fraction >= 0.5f && durationMs - positionMs < 30_000))
}

/**
 * Local watch history (nothing leaves the phone). Stored as JSON in the app's files
 * folder and loaded once at startup.
 *
 * Saving rewrites the whole file, so while a video plays (its position is recorded every
 * few seconds) it's written at most every [SAVE_DELAY_MS]. Removing or clearing history,
 * and [flush] (the player calls it when it goes to the background), write right away.
 * Writes go through [AtomicFile] one at a time, so the file is never half-written.
 */
class WatchHistoryStore private constructor(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "watch_history.json"))
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var pendingSave: Job? = null

    /** Keyed by YouTube video id. */
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, WatchEntry>> = _entries.asStateFlow()

    fun entryFor(url: String): WatchEntry? = videoIdOf(url)?.let { _entries.value[it] }

    /**
     * Saves the playback position. [related], [category] and [tags] are known when a video
     * opens; null keeps what was stored earlier.
     */
    fun record(
        video: VideoItem,
        positionMs: Long,
        durationMs: Long,
        related: List<VideoItem>? = null,
        category: String? = null,
        tags: List<String>? = null,
    ) {
        val id = videoIdOf(video.url) ?: return
        _entries.update { map ->
            val old = map[id]
            val entry = WatchEntry(
                video = video,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs,
                lastWatchedAt = System.currentTimeMillis(),
                related = related ?: old?.related.orEmpty(),
                category = category?.takeIf { it.isNotBlank() } ?: old?.category,
                tags = tags?.take(MAX_TAGS) ?: old?.tags.orEmpty(),
            )
            trim(map + (id to entry))
        }
        saveSoon()
    }

    fun remove(url: String) {
        val id = videoIdOf(url) ?: return
        _entries.update { it - id }
        flush()
    }

    fun clear() {
        _entries.value = emptyMap()
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

    private suspend fun write() = writeLock.withLock {
        val json = JSONArray()
        _entries.value.forEach { (_, e) ->
            json.put(
                JSONObject()
                    .put("video", e.video.toJson())
                    .put("pos", e.positionMs)
                    .put("dur", e.durationMs)
                    .put("at", e.lastWatchedAt)
                    .put("related", JSONArray().apply { e.related.forEach { put(it.toJson()) } })
                    .put("cat", e.category ?: JSONObject.NULL)
                    .put("tags", JSONArray(e.tags)),
            )
        }
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(json.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            out?.let(file::failWrite)
            Log.w("History", "Save failed", e)
        }
    }

    private fun load(): Map<String, WatchEntry> = runCatching {
        val arr = try {
            JSONArray(String(file.readFully()))
        } catch (_: FileNotFoundException) {
            return@runCatching emptyMap() // nothing watched yet
        }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val video = videoFromJson(o.getJSONObject("video"))
            val id = videoIdOf(video.url) ?: return@mapNotNull null
            val rel = o.optJSONArray("related") ?: JSONArray()
            val tags = o.optJSONArray("tags") ?: JSONArray()
            id to WatchEntry(
                video = video,
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                lastWatchedAt = o.optLong("at"),
                related = (0 until rel.length()).map { videoFromJson(rel.getJSONObject(it)) },
                category = o.optString("cat").takeIf { it.isNotEmpty() && !o.isNull("cat") },
                tags = (0 until tags.length()).map { tags.getString(it) },
            )
        }.toMap()
    }.getOrElse {
        Log.w("History", "Load failed", it)
        emptyMap()
    }

    companion object {
        private const val MAX_ENTRIES = 1000
        const val MAX_TAGS = 15
        private const val MAX_WITH_RELATED = 150
        private const val SAVE_DELAY_MS = 10_000L

        @Volatile
        private var instance: WatchHistoryStore? = null

        fun get(context: Context): WatchHistoryStore =
            instance ?: synchronized(this) {
                instance ?: WatchHistoryStore(context.applicationContext).also { instance = it }
            }
    }
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
