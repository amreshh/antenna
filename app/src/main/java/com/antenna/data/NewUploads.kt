package com.antenna.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long an upload counts as new, while its channel isn't opened. */
const val NEW_UPLOAD_DAYS = 7

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Which uploads you've seen in the Subscriptions tab's channel list. A blue dot marks a channel
 * with an upload you haven't, like YouTube's ([channelsWithNewUploads]). Opening a channel in
 * the list counts as seeing all of its uploads in the feed: you've looked at the channel, not
 * necessarily watched them. Kept on the phone, in SharedPreferences: for each channel opened,
 * the ids of its videos in the feed when it was.
 */
class SeenUploads private constructor(context: Context) {

    private val sp = context.getSharedPreferences("seen_uploads", Context.MODE_PRIVATE)
    private val _seen = MutableStateFlow(load())

    /** The ids of the videos seen, by channel url. */
    val seen: StateFlow<Map<String, Set<String>>> = _seen.asStateFlow()

    /**
     * The channel at [channelUrl] was opened, with [ids] its videos in the feed. None (its feed
     * didn't load this time) changes nothing, rather than making all of them new again.
     */
    @Synchronized
    fun markSeen(channelUrl: String, ids: Set<String>) {
        if (ids.isEmpty() || _seen.value[channelUrl] == ids) return
        sp.edit { putStringSet(channelUrl, HashSet(ids)) }
        _seen.value += channelUrl to ids
    }

    private fun load(): Map<String, Set<String>> =
        sp.all.mapNotNull { (url, ids) -> (ids as? Set<*>)?.let { url to it.filterIsInstance<String>().toSet() } }.toMap()

    companion object {
        @Volatile
        private var instance: SeenUploads? = null

        fun get(context: Context): SeenUploads =
            instance ?: synchronized(this) {
                instance ?: SeenUploads(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * The channels (urls) with a new upload in [feed]: one from the last [NEW_UPLOAD_DAYS] days
 * that wasn't in the feed yet when the channel was last opened ([seen], video ids by channel).
 * Shorts count: they're uploads too.
 */
fun channelsWithNewUploads(feed: List<VideoItem>, seen: Map<String, Set<String>>, now: Long): Set<String> {
    val since = now - NEW_UPLOAD_DAYS * DAY_MS
    val found = HashSet<String>()
    feed.forEach { v ->
        val channel = v.channelUrl ?: return@forEach
        if (channel in found || v.uploadedAtMs <= since) return@forEach
        val id = videoIdOf(v.url) ?: return@forEach
        if (id !in seen[channel].orEmpty()) found += channel
    }
    return found
}
