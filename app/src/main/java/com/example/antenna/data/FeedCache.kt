package com.example.antenna.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The feed as saved: its videos, the channels they're from (urls), and when it was saved. */
class CachedFeed(val items: List<VideoItem>, val channels: Set<String>, val savedAt: Long)

/**
 * The Subscriptions feed as it last loaded, so the tab shows it straight away while a fresh
 * one loads. It's in the app's cache folder: Android may clear that when it needs room,
 * which only costs one slower start. Writes go through [AtomicFile], one at a time, so the
 * file is never half-written.
 */
class FeedCache(context: Context) {

    private val file = AtomicFile(File(context.cacheDir, "feed.json"))
    private val writeLock = Mutex()

    /** The feed saved last, or null when there's none (or it can't be read). */
    suspend fun read(): CachedFeed? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(String(file.readFully()))
            if (o.optInt("version") != VERSION) return@runCatching null
            val items = o.getJSONArray("items")
            val channels = o.optJSONArray("channels") ?: JSONArray()
            CachedFeed(
                items = (0 until items.length()).map { videoFromJson(items.getJSONObject(it)) },
                channels = (0 until channels.length()).mapTo(HashSet()) { channels.getString(it) },
                savedAt = o.optLong("savedAt"), // 0 in older files: not fresh
            )
        }.getOrNull()
    }

    suspend fun write(items: List<VideoItem>, channels: Set<String>) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val json = JSONObject()
                .put("version", VERSION)
                .put("savedAt", System.currentTimeMillis())
                .put("channels", JSONArray(channels))
                .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            var out: FileOutputStream? = null
            try {
                out = file.startWrite()
                out.write(json.toString().toByteArray())
                file.finishWrite(out)
            } catch (e: IOException) {
                out?.let(file::failWrite)
                Log.w("FeedCache", "Save failed", e)
            }
        }
    }

    private companion object {
        /** Bump to ignore caches written in an older shape. */
        const val VERSION = 1
    }
}
