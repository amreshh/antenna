package com.example.antenna.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Videos saved to watch later, newest first. Like the subscriptions, they live only on the
 * phone (SharedPreferences, kept out of cloud backups) and are exposed as a StateFlow, so
 * every screen updates when one is saved or removed.
 */
class WatchLaterStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences("watch_later", Context.MODE_PRIVATE)
    private val _videos = MutableStateFlow(load())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    /** The saved videos' ids, for quick "is it saved?" checks (the player's button). */
    private val _ids = MutableStateFlow(idsOf(_videos.value))
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun contains(url: String): Boolean = videoIdOf(url)?.let { it in _ids.value } == true

    // Changes are read-modify-write of the whole list, so one at a time.
    @Synchronized
    fun add(video: VideoItem) {
        val id = videoIdOf(video.url) ?: return
        val rest = _videos.value.filterNot { videoIdOf(it.url) == id }
        save((listOf(video) + rest).take(MAX_VIDEOS))
    }

    @Synchronized
    fun remove(url: String) {
        val id = videoIdOf(url) ?: return
        save(_videos.value.filterNot { videoIdOf(it.url) == id })
    }

    /** Saves it if it isn't saved, removes it if it is. Returns whether it's saved now. */
    @Synchronized
    fun toggle(video: VideoItem): Boolean =
        if (contains(video.url)) {
            remove(video.url)
            false
        } else {
            add(video)
            true
        }

    /** Removes the videos [watched] says you've finished. Returns how many. */
    @Synchronized
    fun removeWatched(watched: (VideoItem) -> Boolean): Int {
        val keep = _videos.value.filterNot(watched)
        val removed = _videos.value.size - keep.size
        if (removed > 0) save(keep)
        return removed
    }

    private fun save(list: List<VideoItem>) {
        sp.edit { putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
        _videos.value = list
        _ids.value = idsOf(list)
    }

    private fun idsOf(list: List<VideoItem>): Set<String> = list.mapNotNullTo(HashSet()) { videoIdOf(it.url) }

    /**
     * A video that can't be read is skipped, not the whole list. A list that can't be read at
     * all is kept aside (under [KEY_UNREADABLE]) before the next change would overwrite it.
     */
    private fun load(): List<VideoItem> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            sp.edit { putString(KEY_UNREADABLE, raw) }
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { runCatching { videoFromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    companion object {
        private const val KEY = "videos"
        private const val KEY_UNREADABLE = "videos_unreadable"
        private const val MAX_VIDEOS = 1000

        @Volatile
        private var instance: WatchLaterStore? = null

        fun get(context: Context): WatchLaterStore =
            instance ?: synchronized(this) {
                instance ?: WatchLaterStore(context.applicationContext).also { instance = it }
            }
    }
}
