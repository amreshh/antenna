package com.example.antenna.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory

data class Channel(
    /** Always the canonical https://www.youtube.com/channel/UC… form. */
    val url: String,
    val name: String,
    val avatarUrl: String? = null,
    /** When you subscribed; 0 when that isn't known (subscribed before it was recorded). */
    val subscribedAt: Long = 0,
)

/**
 * Subscriptions live only on the phone: no Google account and nothing sent anywhere.
 * Stored as a small JSON list in SharedPreferences, exposed as a StateFlow so every
 * screen updates when you subscribe or unsubscribe.
 */
class SubscriptionStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)
    private val _channels = MutableStateFlow(load())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    fun isSubscribed(url: String): Boolean {
        val key = normalizeChannelUrl(url) ?: url
        return _channels.value.any { it.url == key }
    }

    // Changes are read-modify-write of the whole list, so one at a time.
    @Synchronized
    fun subscribe(channel: Channel) {
        val url = normalizeChannelUrl(channel.url) ?: return
        if (isSubscribed(url)) return
        save(_channels.value + channel.copy(url = url, subscribedAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun unsubscribe(url: String) {
        val key = normalizeChannelUrl(url) ?: url
        save(_channels.value.filterNot { it.url == key })
    }

    /** Unsubscribes from all of these (the cleanup). */
    @Synchronized
    fun removeAll(urls: Set<String>) {
        if (urls.isEmpty()) return
        save(_channels.value.filterNot { it.url in urls })
    }

    /** Sets avatars, by channel url (for channels imported without one). */
    @Synchronized
    fun setAvatars(avatars: Map<String, String>) {
        if (avatars.isEmpty()) return
        save(_channels.value.map { c -> avatars[c.url]?.let { c.copy(avatarUrl = it) } ?: c })
    }

    /** Adds channels that aren't already subscribed. Returns how many were new. */
    @Synchronized
    fun addAll(channels: List<Channel>): Int {
        val existing = _channels.value.map { it.url }.toMutableSet()
        val now = System.currentTimeMillis()
        val fresh = channels.mapNotNull { c ->
            val url = normalizeChannelUrl(c.url) ?: return@mapNotNull null
            if (existing.add(url)) c.copy(url = url, subscribedAt = now) else null
        }
        if (fresh.isNotEmpty()) save(_channels.value + fresh)
        return fresh.size
    }

    private fun save(list: List<Channel>) {
        val sorted = list.sortedBy { it.name.lowercase() }
        val json = JSONArray()
        sorted.forEach {
            json.put(
                JSONObject()
                    .put("url", it.url)
                    .put("name", it.name)
                    .put("avatar", it.avatarUrl ?: JSONObject.NULL)
                    .put("since", it.subscribedAt),
            )
        }
        sp.edit { putString(KEY, json.toString()) }
        _channels.value = sorted
    }

    /**
     * An entry that can't be read is skipped, not the whole list. A list that can't be read at
     * all is kept aside (under [KEY_UNREADABLE]) before the next change would overwrite it.
     */
    private fun load(): List<Channel> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            sp.edit { putString(KEY_UNREADABLE, raw) }
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Channel(
                    url = o.getString("url"),
                    name = o.optString("name"),
                    avatarUrl = if (o.isNull("avatar")) null else o.optString("avatar"),
                    subscribedAt = o.optLong("since"), // 0 in older lists
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val KEY = "channels"
        private const val KEY_UNREADABLE = "channels_unreadable"

        @Volatile
        private var instance: SubscriptionStore? = null

        fun get(context: Context): SubscriptionStore =
            instance ?: synchronized(this) {
                instance ?: SubscriptionStore(context.applicationContext).also { instance = it }
            }
    }
}

/** A YouTube channel id: "UC" and 22 more characters. */
private val CHANNEL_ID = Regex("""UC[\w-]{22}""")

/**
 * Turns any youtube.com/channel/UC… link into one canonical form so duplicates match.
 * Returns null for links without a UC… channel id (e.g. bare @handles), since the
 * RSS feed needs the id.
 */
fun normalizeChannelUrl(url: String): String? {
    // Newer NewPipeExtractor versions return "channel/UC…" here rather than the bare id.
    val id = runCatching { YoutubeChannelLinkHandlerFactory.getInstance().getId(url) }.getOrNull()
        ?.removePrefix("channel/")
        ?.takeIf { CHANNEL_ID.matches(it) }
        ?: Regex("""/channel/(UC[\w-]{22})""").find(url)?.groupValues?.get(1)
    return id?.let { "https://www.youtube.com/channel/$it" }
}

/**
 * Subscriptions as Google Takeout's subscriptions.csv writes them, so the file works for
 * importing anywhere that takes Takeout's (this app included, [parseTakeoutCsv]): a "Channel
 * Id,Channel Url,Channel Title" header, then one channel per line with an http:// channel
 * link, a title in quotes only when it has a comma or a quote in it, and \n line ends.
 */
fun exportTakeoutCsv(channels: List<Channel>): String = buildString {
    append("Channel Id,Channel Url,Channel Title\n")
    channels.forEach { c ->
        val id = Regex("""/channel/(UC[\w-]{22})""").find(c.url)?.groupValues?.get(1) ?: return@forEach
        val title = c.name.replace("\r", " ").replace("\n", " ")
        val field = if (title.contains(',') || title.contains('"')) "\"" + title.replace("\"", "\"\"") + "\"" else title
        append(id).append(",http://www.youtube.com/channel/").append(id).append(',').append(field).append('\n')
    }
}

/**
 * Parses the subscriptions.csv from Google Takeout
 * (takeout.google.com → YouTube and YouTube Music → subscriptions).
 * Columns: Channel Id, Channel Url, Channel Title.
 */
fun parseTakeoutCsv(csv: String): List<Channel> =
    csv.lineSequence()
        .map { it.trim().removePrefix("\uFEFF") }
        .filter { it.isNotEmpty() && !it.startsWith("Channel Id", ignoreCase = true) }
        .mapNotNull { line ->
            // split on the first two commas only, titles can contain commas
            val first = line.indexOf(',')
            val second = line.indexOf(',', first + 1)
            if (first < 0 || second < 0) return@mapNotNull null
            val id = line.substring(0, first).trim()
            val title = line.substring(second + 1).trim().removeSurrounding("\"").replace("\"\"", "\"")
            if (!CHANNEL_ID.matches(id)) null
            else Channel("https://www.youtube.com/channel/$id", title.ifEmpty { id })
        }
        .toList()
