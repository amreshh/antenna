package com.example.antenna.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets the videos you're likely to open ready before you tap them. Most of the wait when a
 * video starts is fetching its details and links (1–3 s; buffering is a fraction of that),
 * so when the app opens it guesses a few videos (Guesses.kt) and fetches their details:
 * only those, a few small requests each, never any video.
 *
 * Every guess is checked against what you open, fetched or not, and kept with the score in
 * a file in the app's files (nothing leaves the phone). That decides what's guessed, and
 * how many guesses are fetched: none, once they keep missing, until the ones it still makes
 * would have been right. A YouTube limit pauses it for a day. Settings can turn it off; the
 * Analytics tab shows how it's doing.
 */
class Prefetcher private constructor(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "guesses.json"))
    private val prefs = Prefs(context)
    private val history = WatchHistoryStore.get(context)
    private val subscriptions = SubscriptionStore.get(context)
    private val watchLater = WatchLaterStore.get(context)
    private val feedCache = FeedCache(context)

    /** One change at a time, in the order they come; the file is read first. */
    private val oneAtATime = Dispatchers.IO.limitedParallelism(1)
    private val serial = CoroutineScope(SupervisorJob() + oneAtATime)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _log = MutableStateFlow(GuessLog())
    val log: StateFlow<GuessLog> = _log.asStateFlow()

    /** The lists the screens show, as candidates (For you's, Subscriptions'). */
    private val shown = ConcurrentHashMap<GuessReason, List<VideoItem>>()
    private var lastRound = 0L
    private var round: Job? = null

    init {
        serial.launch { _log.value = read() }
    }

    /** What a screen shows: candidates for the next guesses. */
    fun shows(reason: GuessReason, videos: List<VideoItem>) {
        shown[reason] = videos.take(MAX_SHOWN)
    }

    /**
     * The app came onto the screen: guess, and fetch what's worth it, once its screens have
     * had a moment to show their lists. Not again within [MIN_GAP_MS].
     */
    @Synchronized
    fun onAppOpened() {
        val now = System.currentTimeMillis()
        if (!prefs.prefetch || round?.isActive == true || now - lastRound < MIN_GAP_MS) return
        lastRound = now
        round = work.launch {
            delay(SETTLE_MS)
            guess()
        }
    }

    private suspend fun guess() {
        history.awaitLoaded()
        val now = System.currentTimeMillis()
        val entries = history.entries.value.values
        val subscribed = subscriptions.channels.value.mapTo(HashSet()) { it.url }
        // Subscriptions' list as the tab last showed it, or as it was saved if the tab hasn't been opened yet.
        val feed = shown[GuessReason.NewUpload] ?: feedCache.read()?.items.orEmpty().filter { it.channelUrl in subscribed }
        val candidates = candidates(entries, watchLater.videos.value, shown[GuessReason.ForYou].orEmpty(), feed, now)
        val log = withContext(oneAtATime) { _log.value } // after the file is read, and changes so far
        val picks = rankCandidates(candidates, entries, log, now).take(GuessLog.GUESSES)
        if (picks.isEmpty()) return
        val fetch = fetchCount(log, now)
        val guesses = picks.mapIndexed { i, c ->
            Guess(videoIdOf(c.video.url)!!, now, c.reason, c.video.channelKey(), rank = i + 1, fetched = i < fetch)
        }
        change { it.guessed(guesses, now) }
        Log.i(TAG, "Guessed ${guesses.joinToString { "${it.videoId} (${it.reason})" }}; fetching $fetch")
        guesses.filter { it.fetched }.forEachIndexed { i, g ->
            try {
                YouTubeRepository.prefetch(watchUrl(g.videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: ReCaptchaException) {
                // YouTube is limiting requests: no more guesses for a day, so it isn't the
                // guessing that keeps the limit going.
                Log.w(TAG, "YouTube is limiting requests: no fetching for a day")
                val rest = guesses.filter { it.fetched }.drop(i)
                change { log -> rest.fold(log.copy(pausedUntil = now + PAUSE_MS)) { l, r -> l.notFetched(r) } }
                return
            } catch (e: Exception) {
                Log.i(TAG, "Couldn't fetch ${g.videoId}: ${e.message}") // gone, or no connection
                change { it.notFetched(g) }
            }
        }
    }

    /** You opened [url]: tapped it, or a link to it (not Back to it, or the player reopening it). */
    fun onVideoOpened(url: String) {
        val id = videoIdOf(url) ?: return
        val now = System.currentTimeMillis()
        change { it.opened(id, now) }
    }

    /** The video at [url] started playing; fetching its details took [detailsMs]. */
    fun onVideoStarted(url: String, detailsMs: Long) {
        val id = videoIdOf(url) ?: return
        val now = System.currentTimeMillis()
        change { it.started(id, detailsMs, now) }
    }

    /** [videoId] was taken out of the history: its guesses, and its opening, go too. */
    fun forget(videoId: String) = change { log ->
        log.copy(guesses = log.guesses.filterNot { it.videoId == videoId }, opens = log.opens.filterNot { it.videoId == videoId })
    }

    /** The history was cleared: the score goes with it. */
    fun clear() = change { GuessLog() }

    private fun change(update: (GuessLog) -> GuessLog) {
        serial.launch {
            _log.value = update(_log.value)
            write(_log.value)
        }
    }

    private fun read(): GuessLog = try {
        guessLogFromJson(String(file.readFully()))
    } catch (_: FileNotFoundException) {
        GuessLog() // nothing guessed yet
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable, starting afresh", e) // only the score: nothing to keep a copy of
        GuessLog()
    }

    private fun write(log: GuessLog) {
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(log.toJson().toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            out?.let(file::failWrite)
            Log.w(TAG, "Save failed", e)
        }
    }

    companion object {
        private const val TAG = "Prefetch"

        /** How long the screens get to show their lists after the app opens, before guessing. */
        private const val SETTLE_MS = 1_500L

        /** Coming back to the app within this doesn't guess again. */
        private const val MIN_GAP_MS = 10 * 60 * 1000L

        private const val PAUSE_MS = 24 * 60 * 60 * 1000L
        private const val MAX_SHOWN = 10

        @Volatile
        private var instance: Prefetcher? = null

        fun get(context: Context): Prefetcher =
            instance ?: synchronized(this) {
                instance ?: Prefetcher(context.applicationContext).also { instance = it }
            }
    }
}
