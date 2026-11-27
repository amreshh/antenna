package com.antenna.ui

import androidx.core.content.edit
import android.content.Context
import android.util.Log
import com.antenna.data.VideosPage
import com.antenna.data.lengths
import com.antenna.data.videoIdOf
import com.antenna.data.withLengths
import org.schabi.newpipe.extractor.Page
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.coroutineScope
import com.antenna.data.YouTubeRepository
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antenna.data.Channel
import com.antenna.data.FeedCache
import com.antenna.data.FeedRepository
import com.antenna.data.GuessReason
import com.antenna.data.Prefetcher
import com.antenna.data.SubscriptionStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.antenna.data.VideoItem
import com.antenna.data.exportTakeoutCsv
import com.antenna.data.parseTakeoutCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

data class FeedUiState(
    val items: List<VideoItem> = emptyList(),
    val refreshing: Boolean = false,
    /** done / total channels while a refresh is running */
    val progress: Pair<Int, Int>? = null,
    val failedChannels: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * A channel's Videos tab, as far as it's loaded: further back than its videos in the feed,
 * whose RSS has only its last 15 uploads, Shorts among them. The channel picked in the
 * Subscriptions tab loads more of it as you scroll; the first page also gives the lengths of
 * the channel's videos in the feed.
 */
data class ChannelVideos(
    /** Newest first. */
    val videos: List<VideoItem> = emptyList(),
    /** The page after [videos]: null at the end, and before the first page has loaded ([loaded]). */
    val next: Page? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Why the last page didn't load. */
    val error: String? = null,
) {
    /** There's more of the tab to load: its first page, or the one after. */
    val hasMore: Boolean get() = !loaded || next != null

    /**
     * With [page] loaded: after the videos so far, or, the first page loaded again ([first]:
     * for the lengths of new uploads), in front of them, still going as far back. A page that
     * adds nothing is taken as the end, so a list that keeps coming back the same can't keep
     * loading.
     */
    fun plus(page: VideosPage, first: Boolean): ChannelVideos = when {
        !loaded -> copy(videos = page.videos, next = page.next, loaded = true)
        first -> copy(videos = (page.videos + videos).distinctBy { it.url })
        else -> {
            val more = (videos + page.videos).distinctBy { it.url }
            copy(videos = more, next = page.next.takeIf { more.size > videos.size })
        }
    }
}

/** A subscriptions.csv is about 100 bytes per channel. */
private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/** How long a saved feed counts as fresh, so opening the tab doesn't load it all again. */
private const val FRESH_FOR_MS = 15 * 60 * 1000L

/** How long a channel whose feed and page failed is spared the page (about 180 KB) on refreshes. */
private const val FAILURE_MEMORY_MS = 24 * 60 * 60 * 1000L

/** Channel avatars fetched at a time, and how many are saved together. */
private const val AVATAR_REQUESTS = 4
private const val AVATAR_BATCH = 8

/** Channels whose video lengths are fetched at a time. */
private const val LENGTH_REQUESTS = 3

/** Lengths filled in are saved this long after the last, so a screenful is saved once. */
private const val LENGTHS_SAVE_DELAY_MS = 2_000L

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SubscriptionStore.get(app)
    val channels: StateFlow<List<Channel>> = store.channels

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** Channels whose videos are currently in the feed. */
    private var loaded = emptySet<String>()
    private var job: Job? = null

    private val cache = FeedCache(app)

    /** When the feed was last loaded: saved with it, so it isn't loaded again too soon. */
    private var fetchedAt = 0L
    private var saveJob: Job? = null

    /** Channels whose feed (and page) failed, by url, with when: likely gone (see FeedRepository.fetch). */
    private val failures = app.getSharedPreferences("feed_failures", Context.MODE_PRIVATE)

    /** Videos whose length was looked for, found or not, by id (lengthWanted). */
    private val lengthsLookedFor = HashSet<String>()
    private val lengthsGate = Semaphore(LENGTH_REQUESTS)

    private val _channelVideos = MutableStateFlow<Map<String, ChannelVideos>>(emptyMap())

    /** Channels' Videos tabs as far as they're loaded, by channel url (loadMoreVideos, lengthWanted). */
    val channelVideos: StateFlow<Map<String, ChannelVideos>> = _channelVideos.asStateFlow()

    /** YouTube is limiting requests: no more lengths until the app starts again. */
    private var lengthsLimited = false

    init {
        viewModelScope.launch {
            // The feed as it last loaded, straight away; only for channels you're still subscribed to.
            val cached = cache.read()
            val urls = store.channels.value.mapTo(HashSet()) { it.url }
            if (cached != null) {
                _state.update { it.copy(items = cached.items.filter { v -> v.channelUrl in urls }) }
                fetchedAt = cached.savedAt
            }
            // Loaded minutes ago: no need to load it all again (pulling it down does). Channels
            // subscribed to since are fetched by sync, below.
            if (cached != null && System.currentTimeMillis() - cached.savedAt in 0 until FRESH_FOR_MS) {
                loaded = cached.channels intersect urls
            } else {
                refresh()
            }
            // Once the feed has loaded (not to slow it down), the avatars imported channels lack.
            launch {
                job?.join()
                fillMissingAvatars()
            }
            // Keep the feed in sync when you subscribe/unsubscribe anywhere in the app.
            store.channels.collect { sync(it) }
        }
        // The newest uploads are likely taps: guesses for Prefetcher.
        viewModelScope.launch {
            _state.map { it.items }.distinctUntilChanged().collect { Prefetcher.get(app).shows(GuessReason.NewUpload, it) }
        }
    }

    fun refresh() = fetch(store.channels.value, replace = true)

    fun unsubscribe(channel: Channel) = store.unsubscribe(channel.url)

    /**
     * [video] is on screen without its length: the RSS feed doesn't have them. Loads the first
     * page of its channel's Videos tab (loadVideos), which fills in all of that channel's
     * videos in the feed at once, and saves them with the feed, which keeps them when it's
     * loaded again. So a channel is asked again only when it has a new upload, or the app has
     * started again since one wasn't found (a live stream: those aren't among its videos).
     * Shorts are left out: YouTube shows no length for them.
     */
    fun lengthWanted(video: VideoItem) {
        if (video.durationSeconds > 0 || video.isShort || lengthsLimited) return
        val channel = video.channelUrl ?: return
        val id = videoIdOf(video.url) ?: return
        if (id in lengthsLookedFor || _channelVideos.value[channel]?.loading == true) return
        loadVideos(channel, page = null, gated = true)
    }

    /**
     * The channel at [channelUrl] is picked, and the end of its videos is on screen: loads
     * more of them from its Videos tab, its first page or the next one.
     */
    fun loadMoreVideos(channelUrl: String) {
        val was = _channelVideos.value[channelUrl] ?: ChannelVideos()
        if (!was.loading && was.hasMore) loadVideos(channelUrl, was.next)
    }

    /**
     * Loads a page of [channelUrl]'s Videos tab, [page] or (null) the first, into
     * [channelVideos]. The first page's lengths fill in the channel's videos in the feed, and
     * are saved with it; found or not, those videos' lengths aren't looked for again (until the
     * app starts again). [gated]: one of the lengths fetched [LENGTH_REQUESTS] at a time.
     */
    private fun loadVideos(channelUrl: String, page: Page?, gated: Boolean = false) {
        val channel = store.channels.value.firstOrNull { it.url == channelUrl } ?: return
        updateVideos(channelUrl) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val got = if (gated) lengthsGate.withPermit { FeedRepository.videos(channel, page) }
                else FeedRepository.videos(channel, page)
                updateVideos(channelUrl) { it.plus(got, first = page == null).copy(loading = false) }
                val found = if (page == null) got.videos.lengths() else emptyMap()
                if (found.isNotEmpty()) {
                    _state.update { it.copy(items = it.items.withLengths(found)) }
                    save(after = LENGTHS_SAVE_DELAY_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ReCaptchaException) lengthsLimited = true
                Log.i("Feed", "Videos of ${channel.name} didn't load", e)
                // Said only when they were asked for: lengths looked for unseen are tried again on scrolling there.
                updateVideos(channelUrl) { it.copy(loading = false, error = e.readable().takeUnless { gated }) }
            } finally {
                if (page == null) {
                    _state.value.items.forEach { v -> if (v.channelUrl == channelUrl) videoIdOf(v.url)?.let(lengthsLookedFor::add) }
                }
            }
        }
    }

    private fun updateVideos(channelUrl: String, change: (ChannelVideos) -> ChannelVideos) =
        _channelVideos.update { it + (channelUrl to change(it[channelUrl] ?: ChannelVideos())) }

    /**
     * Saves the feed as it is then, [after] a while. A save still waiting is replaced, so the
     * newest one is written last.
     */
    private fun save(after: Long = 0) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(after)
            cache.write(_state.value.items, loaded, fetchedAt)
        }
    }

    /**
     * Imports a Google Takeout subscriptions.csv. The file is read off the main thread: it
     * may come from a cloud provider that downloads it first. [onResult] gets a message.
     */
    fun importTakeout(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val csv = withContext(Dispatchers.IO) { readText(uri) }
                val added = store.addAll(parseTakeoutCsv(csv))
                if (added == 0) "No new channels found in that file" else "Imported $added channels"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't read that file: ${e.message}"
            }
            onResult(message)
        }
    }

    /**
     * Saves your subscriptions to [uri] as Google Takeout's subscriptions.csv (see
     * exportTakeoutCsv). The file is written off the main thread. [onResult] gets a message.
     */
    fun exportTakeout(uri: Uri, onResult: (String) -> Unit) {
        val channels = store.channels.value
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("it couldn't be opened")
                    out.use { it.write(exportTakeoutCsv(channels).toByteArray()) }
                }
                "Exported ${channels.size} channel${if (channels.size == 1) "" else "s"}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't save the file: ${e.message}"
            }
            onResult(message)
        }
    }

    /** The file's text, refusing anything far bigger than a subscriptions.csv. */
    private fun readText(uri: Uri): String {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IOException("it couldn't be opened")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) throw IOException("it's over 5 MB, too big for a subscriptions.csv")
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    /** Drops removed channels locally and fetches only newly added ones. */
    private fun sync(list: List<Channel>) {
        val current = list.map { it.url }.toSet()
        _channelVideos.update { m -> m.filterKeys { it in current } }
        if (current == loaded || _state.value.refreshing) return
        val removed = loaded - current
        if (removed.isNotEmpty()) {
            _state.update { s -> s.copy(items = s.items.filter { it.channelUrl in current }) }
            loaded = loaded - removed
        }
        val added = list.filter { it.url !in loaded }
        if (added.isNotEmpty()) fetch(added, replace = false)
    }

    /**
     * Channels imported from Google Takeout have no avatar (its file has only their ids and
     * names): fetches each one's, [AVATAR_REQUESTS] at a time, saving them as they come in so
     * the icons fill in. Once saved, an avatar isn't fetched again. A channel that's gone is
     * skipped; YouTube limiting requests stops it until the app starts again.
     */
    private suspend fun fillMissingAvatars() {
        val missing = store.channels.value.filter { it.avatarUrl == null }
        if (missing.isEmpty()) return
        val gate = Semaphore(AVATAR_REQUESTS)
        val found = HashMap<String, String>() // guarded by itself
        val limited = AtomicBoolean(false)
        fun saveFound() {
            val batch = synchronized(found) { HashMap(found).also { found.clear() } }
            store.setAvatars(batch)
        }
        coroutineScope {
            missing.forEach { channel ->
                launch {
                    gate.withPermit {
                        if (limited.get()) return@withPermit
                        val avatar = try {
                            YouTubeRepository.channelAvatar(channel)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: ReCaptchaException) {
                            limited.set(true)
                            null
                        } catch (_: Exception) {
                            null // gone, or unreadable
                        }
                        if (avatar != null && synchronized(found) { found[channel.url] = avatar; found.size } >= AVATAR_BATCH) saveFound()
                    }
                }
            }
        }
        saveFound()
    }

    private fun fetch(channels: List<Channel>, replace: Boolean) {
        if (channels.isEmpty()) {
            if (replace) {
                loaded = emptySet()
                _state.update { FeedUiState() }
            }
            return
        }
        job?.cancel()
        _state.update { it.copy(refreshing = true, progress = 0 to channels.size, error = null) }
        job = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val skip = failures.all.filter { (_, at) -> at is Long && now - at in 0 until FAILURE_MEMORY_MS }.keys
                val result = FeedRepository.fetch(channels, skipPageFor = skip) { done, total ->
                    _state.update { it.copy(progress = done to total) }
                }
                // Remember channels that failed for a day (keeping when they first did); forget
                // the ones that work again.
                failures.edit {
                    result.failedUrls.forEach { if (it !in skip) putLong(it, now) }
                    channels.forEach { if (it.url !in result.failedUrls) remove(it.url) }
                }
                val urls = channels.map { it.url }.toSet()
                loaded = if (replace) urls else loaded + urls
                _state.update { s ->
                    // The feed has no lengths: keep the ones found for its videos before.
                    val fresh = result.items.withLengths(s.items.lengths())
                    val merged = if (replace) fresh
                    else (s.items + fresh).distinctBy { it.url }.sortedByDescending { it.uploadedAtMs }
                    s.copy(
                        items = merged,
                        refreshing = false,
                        progress = null,
                        failedChannels = result.failedChannels,
                    )
                }
                fetchedAt = now
                save()
                // catch subscriptions that changed while we were loading
                sync(store.channels.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, progress = null, error = e.readable()) }
            }
        }
    }
}
