package com.antenna.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate

/*
 * What For you learns from you besides what you watch: Not interested and Don't recommend
 * channel, from a video's ⋮ menu, and which videos it showed you that you passed over. All of
 * it stays on the phone.
 */

/** A video you marked Not interested in For you: it isn't recommended again, and its channel a little less. */
data class NotInterested(
    val videoId: String,
    val channelUrl: String?,
    val channel: String,
    val at: Long,
    /** Its title, for the list of what's hidden (Analytics). */
    val title: String = "",
)

/** A channel you said not to recommend: For you leaves out all of its videos. */
data class HiddenChannel(
    /** Its link; null when the video it was hidden from was saved without one (then [name] is matched). */
    val url: String?,
    val name: String,
    val at: Long,
) {
    fun sameAs(other: HiddenChannel): Boolean =
        if (url != null && other.url != null) url == other.url else name == other.name
}

/** Your Not interested videos, by id, and the channels you said not to recommend, the latest first. */
data class ForYouFeedback(
    val notInterested: Map<String, NotInterested> = emptyMap(),
    val hiddenChannels: List<HiddenChannel> = emptyList(),
) {
    private val hiddenUrls = hiddenChannels.mapNotNullTo(HashSet()) { it.url }
    private val hiddenNames = hiddenChannels.mapNotNullTo(HashSet()) { c -> c.name.takeIf { it.isNotBlank() } }
    private val namesWithoutUrl = hiddenChannels.mapNotNullTo(HashSet()) { c -> c.name.takeIf { c.url == null && it.isNotBlank() } }

    /**
     * Whether the channel at [url] named [name] is one you said not to recommend: by its link,
     * or by name when the channel (or the one hidden) was saved without a link.
     */
    fun hidesChannel(url: String?, name: String): Boolean =
        if (url != null) url in hiddenUrls || name in namesWithoutUrl else name in hiddenNames

    /** Whether For you leaves out [video], whose id is [id]: Not interested, or from a hidden channel. */
    fun hides(id: String, video: VideoItem): Boolean = id in notInterested || hidesChannel(video.channelUrl, video.uploader)

    val isEmpty: Boolean get() = notInterested.isEmpty() && hiddenChannels.isEmpty()
}

/**
 * Not interested and Don't recommend channel, on the phone in SharedPreferences (kept out of
 * cloud backups, like the rest). The Analytics tab lists them, to show them again; clearing
 * the history leaves them.
 */
class ForYouFeedbackStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences("for_you", Context.MODE_PRIVATE)
    private val _feedback = MutableStateFlow(load())
    val feedback: StateFlow<ForYouFeedback> = _feedback.asStateFlow()

    /** You're not interested in [video]. Returns what was saved (for Undo), or null for a link that isn't a video. */
    @Synchronized
    fun notInterested(video: VideoItem, now: Long = System.currentTimeMillis()): NotInterested? {
        val id = videoIdOf(video.url) ?: return null
        val entry = NotInterested(id, video.channelUrl, video.uploader, now, video.title)
        val rest = (_feedback.value.notInterested - id).values.sortedByDescending { it.at }.take(MAX_VIDEOS - 1)
        save(_feedback.value.copy(notInterested = (listOf(entry) + rest).associateBy { it.videoId }))
        return entry
    }

    /** Takes back Not interested on [entry]'s video. */
    @Synchronized
    fun forget(entry: NotInterested) {
        if (entry.videoId in _feedback.value.notInterested) save(_feedback.value.copy(notInterested = _feedback.value.notInterested - entry.videoId))
    }

    @Synchronized
    fun clearNotInterested() {
        if (_feedback.value.notInterested.isNotEmpty()) save(_feedback.value.copy(notInterested = emptyMap()))
    }

    /** Don't recommend [video]'s channel. Returns what was saved (for Undo), or null when the video has no channel. */
    @Synchronized
    fun hideChannel(video: VideoItem, now: Long = System.currentTimeMillis()): HiddenChannel? {
        if (video.channelUrl == null && video.uploader.isBlank()) return null
        val channel = HiddenChannel(video.channelUrl, video.uploader, now)
        val rest = _feedback.value.hiddenChannels.filterNot { it.sameAs(channel) }.take(MAX_CHANNELS - 1)
        save(_feedback.value.copy(hiddenChannels = listOf(channel) + rest))
        return channel
    }

    /** Lets For you recommend [channel] again. */
    @Synchronized
    fun showAgain(channel: HiddenChannel) {
        val rest = _feedback.value.hiddenChannels.filterNot { it.sameAs(channel) }
        if (rest.size != _feedback.value.hiddenChannels.size) save(_feedback.value.copy(hiddenChannels = rest))
    }

    private fun save(feedback: ForYouFeedback) {
        sp.edit {
            putString(KEY_VIDEOS, notInterestedToJson(feedback.notInterested.values))
            putString(KEY_CHANNELS, hiddenChannelsToJson(feedback.hiddenChannels))
        }
        _feedback.value = feedback
    }

    private fun load() = ForYouFeedback(
        notInterested = sp.getString(KEY_VIDEOS, null)?.let(::notInterestedFromJson).orEmpty().associateBy { it.videoId },
        hiddenChannels = sp.getString(KEY_CHANNELS, null)?.let(::hiddenChannelsFromJson).orEmpty(),
    )

    companion object {
        private const val KEY_VIDEOS = "not_interested"
        private const val KEY_CHANNELS = "hidden_channels"
        private const val MAX_VIDEOS = 1000
        private const val MAX_CHANNELS = 500

        @Volatile
        private var instance: ForYouFeedbackStore? = null

        fun get(context: Context): ForYouFeedbackStore =
            instance ?: synchronized(this) {
                instance ?: ForYouFeedbackStore(context.applicationContext).also { instance = it }
            }
    }
}

internal fun notInterestedToJson(entries: Collection<NotInterested>): String = JSONArray().apply {
    entries.forEach { e ->
        put(
            JSONObject().put("id", e.videoId).put("channelUrl", e.channelUrl ?: JSONObject.NULL).put("channel", e.channel)
                .put("at", e.at).put("title", e.title),
        )
    }
}.toString()

/** The entries saved; one that can't be read is left out, and a list that can't be read at all is empty. */
internal fun notInterestedFromJson(text: String): List<NotInterested> {
    val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            NotInterested(o.getString("id"), o.optStringOrNull("channelUrl"), o.optString("channel"), o.getLong("at"), o.optString("title"))
        }.getOrNull()
    }
}

internal fun hiddenChannelsToJson(channels: List<HiddenChannel>): String = JSONArray().apply {
    channels.forEach { c -> put(JSONObject().put("url", c.url ?: JSONObject.NULL).put("name", c.name).put("at", c.at)) }
}.toString()

internal fun hiddenChannelsFromJson(text: String): List<HiddenChannel> {
    val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val o = arr.getJSONObject(i)
            HiddenChannel(o.optStringOrNull("url"), o.getString("name"), o.getLong("at"))
        }.getOrNull()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

// --- Passed over --------------------------------------------------------------------------

/** For you counts the days each video was shown on, this many days back. */
const val SHOWN_DAYS = 30

/**
 * The days (epoch days, local time) For you showed each video on, at most once a day and
 * [SHOWN_DAYS] back, so it can rank lower what you keep passing over, like YouTube's home page
 * does. Kept in a file in the app's files, read in the background when the app starts
 * ([awaitLoaded]), and written a few seconds after a change.
 */
class ShownInForYou private constructor(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "for_you_shown.json"))

    /** One change at a time, in the order they come; the file is read first. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val days = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    private val loaded = MutableStateFlow(false)
    private var pendingSave: Job? = null

    init {
        io.launch {
            days.value = read().recent(today())
            loaded.value = true
        }
    }

    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    /** [videoId] is on screen in For you. */
    fun shown(videoId: String) {
        val today = today()
        io.launch {
            days.value = days.value.withShown(videoId, today) ?: return@launch
            saveSoon()
        }
    }

    /** On how many days each video was shown, of the last [SHOWN_DAYS]. */
    fun counts(): Map<String, Int> = days.value.shownCounts(today())

    /** Takes [videoId] out (it was removed from the history). */
    fun forget(videoId: String) {
        io.launch {
            if (videoId !in days.value) return@launch
            days.value -= videoId
            saveSoon()
        }
    }

    /** Takes everything out (the history was cleared). */
    fun clear() {
        io.launch {
            pendingSave?.cancel()
            days.value = emptyMap()
            file.delete()
        }
    }

    private fun saveSoon() {
        if (pendingSave?.isActive == true) return
        pendingSave = io.launch {
            delay(SAVE_DELAY_MS)
            write(days.value)
        }
    }

    private fun read(): Map<String, List<Int>> = try {
        shownFromJson(String(file.readFully()))
    } catch (_: FileNotFoundException) {
        emptyMap() // nothing shown yet
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable, starting afresh", e) // only what was passed over: nothing to keep a copy of
        emptyMap()
    }

    private fun write(days: Map<String, List<Int>>) {
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(shownToJson(days).toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            out?.let(file::failWrite)
            Log.w(TAG, "Save failed", e)
        }
    }

    companion object {
        private const val TAG = "ShownInForYou"
        private const val SAVE_DELAY_MS = 5_000L

        private fun today(): Int = LocalDate.now().toEpochDay().toInt()

        @Volatile
        private var instance: ShownInForYou? = null

        fun get(context: Context): ShownInForYou =
            instance ?: synchronized(this) {
                instance ?: ShownInForYou(context.applicationContext).also { instance = it }
            }
    }
}

/** These days with [id] shown on [today]; null when it already was today. Days over [SHOWN_DAYS] back go. */
internal fun Map<String, List<Int>>.withShown(id: String, today: Int): Map<String, List<Int>>? {
    val before = this[id].orEmpty()
    if (today in before) return null
    return this + (id to before.filter { today - it in 0 until SHOWN_DAYS } + today)
}

/** On how many days each video was shown, of the last [SHOWN_DAYS] up to [today]. */
internal fun Map<String, List<Int>>.shownCounts(today: Int): Map<String, Int> =
    mapValues { (_, days) -> days.count { today - it in 0 until SHOWN_DAYS } }.filterValues { it > 0 }

/** Without the days over [SHOWN_DAYS] back, and the videos that leaves none for. */
internal fun Map<String, List<Int>>.recent(today: Int): Map<String, List<Int>> =
    mapValues { (_, days) -> days.filter { today - it in 0 until SHOWN_DAYS } }.filterValues { it.isNotEmpty() }

internal fun shownToJson(days: Map<String, List<Int>>): String =
    JSONObject().apply { days.forEach { (id, d) -> put(id, JSONArray(d)) } }.toString()

/** The days saved; a video whose days can't be read is left out. */
internal fun shownFromJson(text: String): Map<String, List<Int>> {
    val o = JSONObject(text)
    return o.keys().asSequence().mapNotNull { id ->
        runCatching { o.getJSONArray(id).let { a -> id to (0 until a.length()).map { a.getInt(it) } } }.getOrNull()
    }.toMap()
}
