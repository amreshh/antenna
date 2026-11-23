package com.example.antenna.ui

import androidx.core.content.edit
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.coroutineScope
import com.example.antenna.data.YouTubeRepository
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antenna.data.Channel
import com.example.antenna.data.FeedCache
import com.example.antenna.data.FeedRepository
import com.example.antenna.data.GuessReason
import com.example.antenna.data.Prefetcher
import com.example.antenna.data.SubscriptionStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.example.antenna.data.VideoItem
import com.example.antenna.data.exportTakeoutCsv
import com.example.antenna.data.parseTakeoutCsv
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

/** A subscriptions.csv is about 100 bytes per channel. */
private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/** How long a saved feed counts as fresh, so opening the tab doesn't load it all again. */
private const val FRESH_FOR_MS = 15 * 60 * 1000L

/** How long a channel whose feed and page failed is spared the page (about 180 KB) on refreshes. */
private const val FAILURE_MEMORY_MS = 24 * 60 * 60 * 1000L

/** Channel avatars fetched at a time, and how many are saved together. */
private const val AVATAR_REQUESTS = 4
private const val AVATAR_BATCH = 8

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SubscriptionStore.get(app)
    val channels: StateFlow<List<Channel>> = store.channels

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** Channels whose videos are currently in the feed. */
    private var loaded = emptySet<String>()
    private var job: Job? = null

    private val cache = FeedCache(app)

    /** Channels whose feed (and page) failed, by url, with when: likely gone (see FeedRepository.fetch). */
    private val failures = app.getSharedPreferences("feed_failures", Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            // The feed as it last loaded, straight away; only for channels you're still subscribed to.
            val cached = cache.read()
            val urls = store.channels.value.mapTo(HashSet()) { it.url }
            if (cached != null) _state.update { it.copy(items = cached.items.filter { v -> v.channelUrl in urls }) }
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
                    val merged = if (replace) result.items
                    else (s.items + result.items).distinctBy { it.url }.sortedByDescending { it.uploadedAtMs }
                    s.copy(
                        items = merged,
                        refreshing = false,
                        progress = null,
                        failedChannels = result.failedChannels,
                    )
                }
                val items = _state.value.items
                val saved = loaded
                launch { cache.write(items, saved) }
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
