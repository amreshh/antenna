package com.example.antenna.data

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.io.File

data class WatchEntry(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    /** YouTube's "up next" suggestions for this video, used to build the For you tab. */
    val related: List<VideoItem> = emptyList(),
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Same idea as YouTube: counts as watched once you're near the end. */
    val isWatched: Boolean
        get() = durationMs > 0 && (fraction >= 0.9f || durationMs - positionMs < 30_000)
}

/**
 * Local watch history (nothing leaves the phone). Stored as JSON in the app's files
 * folder, loaded once at startup, and saved a second after the last change.
 */
class WatchHistoryStore private constructor(context: Context) {

    private val file = File(context.filesDir, "watch_history.json")
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Keyed by YouTube video id. */
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, WatchEntry>> = _entries.asStateFlow()

    fun entryFor(url: String): WatchEntry? = videoIdOf(url)?.let { _entries.value[it] }

    /** Saves the playback position. [related] = null keeps the list stored earlier. */
    fun record(video: VideoItem, positionMs: Long, durationMs: Long, related: List<VideoItem>? = null) {
        val id = videoIdOf(video.url) ?: return
        _entries.update { map ->
            val old = map[id]
            val entry = WatchEntry(
                video = video,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs,
                lastWatchedAt = System.currentTimeMillis(),
                related = related ?: old?.related.orEmpty(),
            )
            trim(map + (id to entry))
        }
        scheduleSave()
    }

    fun remove(url: String) {
        val id = videoIdOf(url) ?: return
        _entries.update { it - id }
        scheduleSave()
    }

    fun clear() {
        _entries.value = emptyMap()
        scheduleSave()
    }

    /** Keeps the newest [MAX_ENTRIES]; only the newest [MAX_WITH_RELATED] keep their related lists. */
    private fun trim(map: Map<String, WatchEntry>): Map<String, WatchEntry> {
        val sorted = map.entries.sortedByDescending { it.value.lastWatchedAt }.take(MAX_ENTRIES)
        return sorted.mapIndexed { i, (id, e) ->
            id to if (i >= MAX_WITH_RELATED && e.related.isNotEmpty()) e.copy(related = emptyList()) else e
        }.toMap()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = io.launch {
            delay(1000)
            val json = JSONArray()
            _entries.value.forEach { (_, e) ->
                json.put(
                    JSONObject()
                        .put("video", e.video.toJson())
                        .put("pos", e.positionMs)
                        .put("dur", e.durationMs)
                        .put("at", e.lastWatchedAt)
                        .put("related", JSONArray().apply { e.related.forEach { put(it.toJson()) } }),
                )
            }
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.toString())
                tmp.renameTo(file)
            }.onFailure { Log.w("History", "Save failed", it) }
        }
    }

    private fun load(): Map<String, WatchEntry> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val video = videoFromJson(o.getJSONObject("video"))
            val id = videoIdOf(video.url) ?: return@mapNotNull null
            val rel = o.optJSONArray("related") ?: JSONArray()
            id to WatchEntry(
                video = video,
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                lastWatchedAt = o.optLong("at"),
                related = (0 until rel.length()).map { videoFromJson(rel.getJSONObject(it)) },
            )
        }.toMap()
    }.getOrElse {
        Log.w("History", "Load failed", it)
        emptyMap()
    }

    companion object {
        private const val MAX_ENTRIES = 1000
        private const val MAX_WITH_RELATED = 50

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
    runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(url) }.getOrNull()
        ?: ID_PATTERN.find(url)?.groupValues?.get(1)

private fun VideoItem.toJson(): JSONObject = JSONObject()
    .put("url", url)
    .put("title", title)
    .put("uploader", uploader)
    .put("duration", durationSeconds)
    .put("thumb", thumbnailUrl ?: JSONObject.NULL)
    .put("uploadedAt", uploadedAtMs)
    .put("uploadedText", uploadedText ?: JSONObject.NULL)
    .put("channelUrl", channelUrl ?: JSONObject.NULL)

private fun videoFromJson(o: JSONObject) = VideoItem(
    url = o.getString("url"),
    title = o.optString("title"),
    uploader = o.optString("uploader"),
    durationSeconds = o.optLong("duration"),
    thumbnailUrl = o.optStringOrNull("thumb"),
    uploadedAtMs = o.optLong("uploadedAt"),
    uploadedText = o.optStringOrNull("uploadedText"),
    channelUrl = o.optStringOrNull("channelUrl"),
)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
