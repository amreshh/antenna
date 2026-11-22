package com.example.antenna.data

import android.content.Context
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

    fun subscribe(channel: Channel) {
        val url = normalizeChannelUrl(channel.url) ?: return
        if (isSubscribed(url)) return
        save(_channels.value + channel.copy(url = url))
    }

    fun unsubscribe(url: String) {
        val key = normalizeChannelUrl(url) ?: url
        save(_channels.value.filterNot { it.url == key })
    }

    /** Adds channels that aren't already subscribed. Returns how many were new. */
    fun addAll(channels: List<Channel>): Int {
        val existing = _channels.value.map { it.url }.toMutableSet()
        val fresh = channels.mapNotNull { c ->
            val url = normalizeChannelUrl(c.url) ?: return@mapNotNull null
            if (existing.add(url)) c.copy(url = url) else null
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
                    .put("avatar", it.avatarUrl ?: JSONObject.NULL),
            )
        }
        sp.edit().putString(KEY, json.toString()).apply()
        _channels.value = sorted
    }

    private fun load(): List<Channel> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(
                    url = o.getString("url"),
                    name = o.optString("name"),
                    avatarUrl = if (o.isNull("avatar")) null else o.optString("avatar"),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "channels"

        @Volatile
        private var instance: SubscriptionStore? = null

        fun get(context: Context): SubscriptionStore =
            instance ?: synchronized(this) {
                instance ?: SubscriptionStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Turns any youtube.com/channel/UC… link into one canonical form so duplicates match.
 * Returns null for links without a UC… channel id (e.g. bare @handles), since the
 * RSS feed needs the id.
 */
fun normalizeChannelUrl(url: String): String? {
    val id = runCatching { YoutubeChannelLinkHandlerFactory.getInstance().getId(url) }.getOrNull()
        ?: Regex("""/channel/(UC[\w-]{22})""").find(url)?.groupValues?.get(1)
    return if (id != null && id.startsWith("UC")) "https://www.youtube.com/channel/$id" else null
}

/**
 * Parses the subscriptions.csv from Google Takeout
 * (takeout.google.com → YouTube and YouTube Music → subscriptions).
 * Columns: Channel Id, Channel Url, Channel Title.
 */
fun parseTakeoutCsv(csv: String): List<Channel> =
    csv.lineSequence()
        .map { it.trim().removePrefix("﻿") }
        .filter { it.isNotEmpty() && !it.startsWith("Channel Id", ignoreCase = true) }
        .mapNotNull { line ->
            // split on the first two commas only, titles can contain commas
            val first = line.indexOf(',')
            val second = line.indexOf(',', first + 1)
            if (first < 0 || second < 0) return@mapNotNull null
            val id = line.substring(0, first).trim()
            val title = line.substring(second + 1).trim().removeSurrounding("\"").replace("\"\"", "\"")
            if (!id.startsWith("UC")) null
            else Channel("https://www.youtube.com/channel/$id", title.ifEmpty { id })
        }
        .toList()
