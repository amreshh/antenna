package com.antenna.ui

import androidx.media3.exoplayer.DefaultLoadControl
import android.net.ConnectivityManager
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import com.antenna.App
import com.antenna.data.Channel
import com.antenna.data.OpenSource
import com.antenna.data.Prefetcher
import com.antenna.data.Prefs
import com.antenna.data.SubscriptionStore
import com.antenna.data.WatchLogStore
import com.antenna.data.WatchSpan
import com.antenna.data.channelKey
import com.antenna.data.VideoItem
import com.antenna.data.WatchHistoryStore
import com.antenna.data.WatchLaterStore
import com.antenna.data.YouTubeRepository
import com.antenna.data.ageLabel
import com.antenna.data.formatDuration
import com.antenna.data.normalizeChannelUrl
import com.antenna.data.pick
import com.antenna.data.toVideoItem
import com.antenna.data.videoIdOf
import com.antenna.data.watchUrl
import com.antenna.data.youtubeVideoId
import com.antenna.player.StreamPicker
import com.antenna.sponsorblock.Segment
import com.antenna.sponsorblock.SponsorBlockClient
import com.antenna.sponsorblock.SponsorCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * The player, kept by MainActivity for as long as the app is open, so a video keeps playing
 * while you browse the tabs with it minimized (see PlayerSheet). It plays one video at a
 * time: opening another one replaces it.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private companion object {
        /** Resume only if you got past this point and aren't basically finished. */
        const val RESUME_MIN_MS = 10_000L

        // Kept when Android ends the app in the background, so it comes back on the same video.
        const val STATE_URL = "player_url"
        const val STATE_BACK_STACK = "player_back_stack"
        const val STATE_EXPANDED = "player_expanded"
        const val STATE_FULLSCREEN = "player_fullscreen"
        const val STATE_SPEED = "player_speed"

        /** Fresh links per video before giving up and showing the error. */
        const val MAX_REFRESHES = 3

        /** How far ahead the player buffers: it loads again below the first, and stops at the second. */
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 30_000

        /**
         * How much has to be buffered before a video starts: 1 s rather than Media3's 2.5 s, so
         * it starts sooner. After a stall it's still Media3's 5 s, so a slow connection doesn't
         * stall again straight away.
         */
        const val START_BUFFER_MS = 1_000

        const val TAG = "Player"
    }

    private val app get() = getApplication<Application>()
    private val prefs = Prefs(app)
    private val subscriptions = SubscriptionStore.get(app)
    private val history = WatchHistoryStore.get(app)
    private val watchLater = WatchLaterStore.get(app)
    private val sponsorBlock = SponsorBlockClient(App.http)
    private val prefetcher = Prefetcher.get(app)
    private val watchLog = WatchLogStore.get(app)

    /** Where the videos opened since the app started were opened from, by id: for the watch log. */
    private val sources = HashMap<String, OpenSource>()

    /** The stretch of watching going on now: it ends when the video pauses, seeks or stops (WatchLogStore). */
    private var span: WatchSpan? = null

    val subscribedChannels: StateFlow<List<Channel>> get() = subscriptions.channels

    /** Ids of the videos saved to watch later (the player's Watch later button). */
    val watchLaterIds: StateFlow<Set<String>> get() = watchLater.ids

    /** The video that's open, or null when the player is closed. */
    var currentUrl by mutableStateOf<String?>(null)
        private set

    var uiState by mutableStateOf<PlayerUiState>(PlayerUiState.Loading)
        private set

    /** Covering the screen, or minimized to the bar above the tabs. */
    var expanded by mutableStateOf(saved[STATE_EXPANDED] ?: true)
        private set

    /** Fullscreen chosen with the player's button (a phone held sideways is fullscreen anyway). */
    var userFullscreen by mutableStateOf(saved[STATE_FULLSCREEN] ?: false)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    /** Whether a play/pause button should show "play" (paused, ended, or failed). */
    var showPlayButton by mutableStateOf(true)
        private set

    /** The qualities the video can play in (e.g. 1080, 720), best first; empty while loading or live. */
    var qualities by mutableStateOf(emptyList<Int>())
        private set

    /** The quality picked in the player's menu, or null for Auto (the best up to the Settings max). */
    var chosenQuality by mutableStateOf<Int?>(null)
        private set

    /** The quality playing now (the video's shorter side), once it's known. */
    var playingQuality by mutableStateOf<Int?>(null)
        private set

    /**
     * How fast videos play, from the player's menu (1 is normal speed). It stays for the next
     * videos until it's changed, like YouTube's, and the sound keeps its pitch.
     */
    var playbackSpeed by mutableFloatStateOf(saved[STATE_SPEED] ?: 1f)
        private set

    /**
     * Why the video stopped playing, when fetching fresh links didn't help (see
     * [onPlaybackError]); shown above its details, with Try again. Null while it plays.
     */
    var playbackError by mutableStateOf<String?>(null)
        private set

    private var exoPlayer: ExoPlayer? = null

    /** Made the first time a video opens, then reused for every video after it. */
    val player: ExoPlayer
        get() = exoPlayer ?: buildPlayer().also { exoPlayer = it }

    // current video
    private var currentVideo: VideoItem? = null
    private var currentInfo: StreamInfo? = null
    private var segments: List<Segment> = emptyList()
    private val allowed = mutableSetOf<String>()       // segments the user chose to watch
    private val reported = mutableSetOf<String>()      // skips already reported to SponsorBlock
    private var loadJob: Job? = null

    /** Fetching fresh stream links after a playback error ([refreshStreams]). */
    private var refreshJob: Job? = null

    /** How many times this video's links were refreshed after an error. */
    private var refreshes = 0

    // When the open video started loading, when its streams went to the player, and when its
    // first frame showed: for how long it took to start playing, logged then (logStart).
    private var openedAt = 0L
    private var preparedAt = 0L
    private var firstFrameAt = 0L

    /** The open video's start is still to be logged: it was opened to play straight away. */
    private var timingStart = false

    /** Videos you came from via "Up next", so Back goes to the previous one like YouTube. */
    private val backStack = ArrayDeque<String>()

    init {
        saved.get<String>(STATE_URL)?.let { url ->
            saved.get<ArrayList<String>>(STATE_BACK_STACK)?.let(backStack::addAll)
            // Paused, as it was when the app went to the background.
            show(url, addToBackStack = false, autoplay = false)
        }
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(app)
        // Pause for calls and other apps' audio, and when headphones are unplugged
        // (instead of carrying on through the speaker).
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        // Buffer 15-30 s ahead instead of Media3's 50 s: what's buffered is downloaded for
        // nothing when you leave a video early (at 1080p, 50 s is some 15-30 MB).
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    START_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build(),
        )
        // No wake lock: the screen stays on while a video plays (keepScreenOn) and leaving
        // the app pauses it, so the CPU never needs keeping awake. (Media3 now asks for one
        // by default; the manifest removes its WAKE_LOCK permission.)
        .setWakeMode(C.WAKE_MODE_NONE)
        .build()
        .apply {
            setPlaybackSpeed(playbackSpeed) // as it was before Android ended the app in the background
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = onPlaybackError(error)

                override fun onRenderedFirstFrame() {
                    // Shown while it buffers, before it plays: of this video's streams only.
                    if (firstFrameAt == 0L && preparedAt >= openedAt) firstFrameAt = SystemClock.elapsedRealtime()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) saveProgress()
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    this@PlayerViewModel.isPlaying = playing
                    if (playing) {
                        logStart()
                        startSpan()
                    } else {
                        endSpan()
                        saveProgress()
                    }
                }

                // A seek (yours, or a SponsorBlock skip) ends the stretch where it was, and
                // starts the next where it lands: what was jumped over wasn't watched.
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
                    if (span == null) return
                    endSpan(oldPosition.positionMs)
                    if (isPlaying) startSpan(newPosition.positionMs)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    showPlayButton = Util.shouldShowPlayButton(player)
                    playingQuality = player.videoSize.let { size ->
                        if (size.width > 0 && size.height > 0) minOf(size.width, size.height) else null
                    }
                }
            })
        }

    // --- Opening and closing ---------------------------------------------------------

    /** A video tapped in a list ([source]): it replaces whatever is playing, and Back minimizes it. */
    fun open(url: String, source: OpenSource) {
        if (url.sameVideoAs(currentUrl)) {
            expand()
            return
        }
        backStack.clear()
        opened(url, source)
        show(url, addToBackStack = false)
        expand()
    }

    /**
     * A video in Up next, a link from another app, or another video linked in the description
     * ([source]): the video that's playing goes on the back stack, so Back returns to it.
     */
    fun openLink(url: String, source: OpenSource) {
        if (!url.sameVideoAs(currentUrl)) {
            opened(url, source)
            show(url, addToBackStack = true)
        }
        expand()
    }

    /** You opened [url] from [source] (not Back to it): for the watch log, and the app's guesses. */
    private fun opened(url: String, source: OpenSource) {
        videoIdOf(url)?.let { sources[it] = source }
        prefetcher.onVideoOpened(url) // was it one the app guessed you'd open?
    }

    /** A stretch of watching starts, at [at] in the video. */
    private fun startSpan(at: Long = exoPlayer?.currentPosition ?: 0) {
        val video = currentVideo ?: return
        val id = videoIdOf(video.url) ?: return
        span = WatchSpan(id, video.channelKey(), System.currentTimeMillis(), at, at, sources[id])
    }

    /** The stretch of watching ends, at [at] in the video: into the watch log. */
    private fun endSpan(at: Long? = exoPlayer?.currentPosition) {
        val s = span ?: return
        span = null
        watchLog.add(s.copy(toMs = at ?: s.toMs))
    }

    /** Stops the video and removes the player from the screen. */
    fun close() {
        endSpan()
        saveProgress()
        loadJob?.cancel()
        exoPlayer?.run {
            stop()
            clearMediaItems()
        }
        currentUrl = null
        currentVideo = null
        currentInfo = null
        qualities = emptyList()
        chosenQuality = null
        refreshJob?.cancel()
        refreshes = 0
        playbackError = null
        segments = emptyList()
        allowed.clear()
        backStack.clear()
        uiState = PlayerUiState.Loading
        setFullscreen(false)
        expanded = true // the next video opens full size
        saved[STATE_URL] = null
        saved[STATE_BACK_STACK] = null
        saved[STATE_EXPANDED] = true
        history.flush()
    }

    fun expand() = updateExpanded(true)

    fun minimize() {
        setFullscreen(false)
        updateExpanded(false)
    }

    private fun updateExpanded(on: Boolean) {
        expanded = on
        saved[STATE_EXPANDED] = on
    }

    /** Back on the full-size player: leave fullscreen, then go back through Up next, then minimize. */
    fun back() {
        if (userFullscreen) {
            setFullscreen(false)
            return
        }
        val previous = backStack.removeLastOrNull()
        if (previous != null) show(previous, addToBackStack = false) else minimize()
    }

    fun toggleFullscreen() = setFullscreen(!userFullscreen)

    private fun setFullscreen(on: Boolean) {
        userFullscreen = on
        saved[STATE_FULLSCREEN] = on
    }

    /**
     * Plays this video in [quality] (from [qualities]), or null for Auto. It carries on from
     * the same spot, playing or paused as it was.
     */
    fun setQuality(quality: Int?) {
        val info = currentInfo ?: return
        if (quality == chosenQuality) return
        chosenQuality = quality
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val (source, _) = pickStreams(info, quality)
            if (info !== currentInfo) return@launch // another video opened meanwhile
            val player = player
            val play = player.playWhenReady
            player.setMediaSource(source, player.currentPosition)
            player.prepare()
            player.playWhenReady = play
        }
    }

    /** Plays at [value] times normal speed, this video and the next ones (the player's menu). */
    fun setSpeed(value: Float) {
        playbackSpeed = value
        saved[STATE_SPEED] = value
        exoPlayer?.setPlaybackSpeed(value)
    }

    /**
     * The media source for [info] in [chosen] quality (null: Auto, up to the Settings max),
     * and the qualities it has. Off the main thread: it asks the device's decoders what they
     * can play, which can take a while the first time.
     */
    private suspend fun pickStreams(info: StreamInfo, chosen: Int?): Pair<MediaSource, List<Int>> =
        withContext(Dispatchers.Default) {
            StreamPicker.build(info, maxHeight = chosen ?: autoMaxHeight(), http = App.http, preferred = chosen) to
                StreamPicker.qualities(info)
        }

    /**
     * The best quality Auto picks: the Settings max, or on mobile data (or another metered
     * network) the lower mobile-data one, as the network is when the video opens.
     */
    private fun autoMaxHeight(): Int {
        val metered = app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        val cap = prefs.maxHeightMetered
        return if (metered && cap != Prefs.SAME_AS_WIFI) minOf(prefs.maxHeight, cap) else prefs.maxHeight
    }

    /**
     * The video stopped with an error. A network or HTTP problem (a refused or expired link,
     * a server that stayed unavailable through PatientRetryPolicy's retries) gets fresh links
     * from YouTube, often from another server, and carries on from the same spot, up to
     * [MAX_REFRESHES] times. YouTube's links also expire after some hours, which this covers
     * when a long-paused video is played again. Anything else is shown, with Try again.
     */
    private fun onPlaybackError(error: PlaybackException) {
        // Media3's ERROR_CODE_IO_* codes, 2000-2999: network and HTTP problems.
        val network = error.errorCode in 2000..2999
        if (network && currentInfo != null && refreshes < MAX_REFRESHES) {
            refreshes++
            refreshStreams()
        } else {
            playbackError = "Playback failed (${error.errorCodeName}): ${error.cause?.message ?: error.message}"
        }
    }

    /** Try again, after an error: the page if it didn't load, otherwise the video with fresh links. */
    fun retry() {
        val url = currentUrl ?: return
        if (currentInfo == null) {
            show(url, addToBackStack = false)
            return
        }
        refreshes = 0
        playbackError = null
        refreshStreams(play = true)
    }

    /** Fetches the video's stream links again and continues where it stopped. */
    private fun refreshStreams(play: Boolean? = null) {
        val url = currentUrl ?: return
        val player = player
        val position = player.currentPosition
        val playWhenReady = play ?: player.playWhenReady
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val info = YouTubeRepository.streamInfo(url, fresh = true)
                if (url != currentUrl) return@launch
                val (source, available) = pickStreams(info, chosenQuality)
                if (url != currentUrl) return@launch
                currentInfo = info
                qualities = available
                player.setMediaSource(source, position)
                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playbackError = e.readable()
            }
        }
    }

    fun playPause() {
        Util.handlePlayPauseButtonAction(player)
    }

    /** The app went to the background: no playing on behind the user's back. */
    fun onStop(changingConfigurations: Boolean) {
        val player = exoPlayer ?: return
        saveProgress()
        if (!changingConfigurations) player.pause()
        history.flush() // the app may be closed from here without another chance to save
    }

    override fun onCleared() {
        endSpan()
        saveProgress()
        history.flush()
        exoPlayer?.release()
        exoPlayer = null
    }

    // --- Loading ---------------------------------------------------------------------

    private fun show(url: String, addToBackStack: Boolean, autoplay: Boolean = true) {
        endSpan() // before the video's gone
        saveProgress()
        if (addToBackStack) currentUrl?.let { backStack.addLast(it) }
        currentUrl = url
        currentVideo = null
        currentInfo = null
        qualities = emptyList()
        chosenQuality = null // each video starts on Auto
        refreshJob?.cancel()
        refreshes = 0
        playbackError = null
        segments = emptyList()
        allowed.clear()
        saved[STATE_URL] = url
        saved[STATE_BACK_STACK] = ArrayList(backStack)
        openedAt = SystemClock.elapsedRealtime()
        firstFrameAt = 0L
        timingStart = autoplay

        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        uiState = PlayerUiState.Loading
        loadJob = viewModelScope.launch { load(url, autoplay) }
    }

    private suspend fun load(url: String, autoplay: Boolean) {
        try {
            coroutineScope<Unit> {
                // Look up sponsor segments in parallel with stream extraction.
                val videoId = videoIdOf(url)
                val segmentsJob = async {
                    if (videoId == null) emptyList() else sponsorBlock.getSegments(videoId, prefs.skipCategories)
                }

                val info = YouTubeRepository.streamInfo(url)
                val video = info.toVideoItem()
                val related = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideoItem() }
                currentVideo = video

                // Resume where you left off, unless you'd basically finished it.
                history.awaitLoaded()
                val previous = history.entryFor(url)
                val startAt = previous
                    ?.takeIf { !it.isWatched && it.positionMs > RESUME_MIN_MS }
                    ?.positionMs ?: 0L

                val (source, available) = pickStreams(info, chosen = null)
                currentInfo = info
                qualities = available
                player.setMediaSource(source, startAt)
                player.prepare()
                preparedAt = SystemClock.elapsedRealtime()
                player.playWhenReady = autoplay
                if (startAt > 0 && autoplay) toast("Resuming from ${formatDuration(startAt / 1000)}")

                // Add to history right away (like YouTube) and store the up-next list
                // so the For you tab can use it.
                history.record(
                    video,
                    positionMs = previous?.positionMs ?: 0L, // keep the old red bar until you move on
                    durationMs = previous?.durationMs?.takeIf { it > 0 } ?: (info.duration * 1000),
                    related = related,
                    category = info.category,
                    tags = info.tags.orEmpty(),
                )

                val ready = PlayerUiState.Ready(
                    title = info.name,
                    uploader = info.uploaderName.orEmpty(),
                    channel = channelOf(info),
                    subscribers = formatCount(info.uploaderSubscriberCount, "subscriber"),
                    segments = null,
                    durationMs = info.duration * 1000,
                    related = related,
                    stats = listOf(formatCount(info.viewCount, "view"), video.ageLabel())
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    description = info.description,
                )
                uiState = ready

                segments = segmentsJob.await()
                if (uiState is PlayerUiState.Ready) uiState = ready.copy(segments = segments)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = PlayerUiState.Error(e.readable())
        }
    }

    /**
     * Logs how long the open video took to start playing: fetching its details and links,
     * then buffering (its first frame shows before it plays). Once per video, and only when
     * it was opened to play straight away. The smoke test reports it.
     */
    private fun logStart() {
        if (!timingStart || preparedAt < openedAt) return // not yet this video's streams
        timingStart = false
        currentUrl?.let { prefetcher.onVideoStarted(it, detailsMs = preparedAt - openedAt) }
        val now = SystemClock.elapsedRealtime()
        val firstFrame = if (firstFrameAt > 0) "${firstFrameAt - openedAt} ms" else "none"
        val format = exoPlayer?.videoFormat
        Log.i(
            TAG,
            "Playing after ${now - openedAt} ms (details ${preparedAt - openedAt} ms, buffering " +
                "${now - preparedAt} ms; first frame at $firstFrame): " +
                "${format?.height}p ${format?.codecs ?: format?.sampleMimeType}",
        )
    }

    private fun saveProgress() {
        val player = exoPlayer ?: return
        val video = currentVideo ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return // not loaded yet, or a live stream
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        history.record(video, position, duration)
    }

    private fun channelOf(info: StreamInfo): Channel? =
        normalizeChannelUrl(info.uploaderUrl.orEmpty())?.let { url ->
            Channel(
                url = url,
                name = info.uploaderName.orEmpty(),
                avatarUrl = info.uploaderAvatars.pick(minHeight = 120), // 40dp at up to 3x
            )
        }

    // --- While the app is on screen ---------------------------------------------------

    /**
     * Skips sponsor segments and saves progress every few seconds. MainActivity runs it while
     * it's started, whether the player is full size or minimized.
     */
    suspend fun runWhileStarted(): Unit = coroutineScope {
        launch { skipLoop() }
        launch {
            while (true) {
                delay(5_000)
                if (isPlaying) saveProgress()
                // The stretch so far, kept in case the app is closed before it ends.
                span?.let { s -> exoPlayer?.let { watchLog.checkpoint(s.copy(toMs = it.currentPosition)) } }
            }
        }
    }

    /** Checks the playhead a few times a second and jumps over sponsor segments. */
    private suspend fun skipLoop() {
        while (true) {
            val player = exoPlayer
            if (player != null && segments.isNotEmpty() && player.playbackState == Player.STATE_READY) {
                val pos = player.currentPosition
                val seg = segments.firstOrNull {
                    it.uuid !in allowed && pos >= it.startMs && pos < it.endMs - 250
                }
                if (seg != null) {
                    val duration = player.duration
                    val to = if (duration != C.TIME_UNSET) minOf(seg.endMs, duration) else seg.endMs
                    player.seekTo(to)
                    toast("Skipped: ${SponsorCategory.labelFor(seg.category)}")
                    // For the Analytics tab: what was skipped, and how much.
                    currentVideo?.let { history.recordSkip(it, seg.category, seg.startMs, skippedMs = to - pos) }
                    if (prefs.reportSkips && reported.add(seg.uuid)) sponsorBlock.reportSkip(seg.uuid)
                }
            }
            delay(200)
        }
    }

    // --- Actions on the full-size player -------------------------------------------------

    fun toggleSubscribe() {
        val channel = (uiState as? PlayerUiState.Ready)?.channel ?: return
        if (subscriptions.isSubscribed(channel.url)) {
            subscriptions.unsubscribe(channel.url)
        } else {
            subscriptions.subscribe(channel)
            toast("Subscribed to ${channel.name}")
        }
    }

    /** Saves the video that's open to watch later, or takes it off. */
    fun toggleWatchLater() {
        val video = currentVideo ?: return
        toast(if (watchLater.toggle(video)) "Saved to Watch later" else "Removed from Watch later")
    }

    /** Tapping a segment in the list plays it instead of skipping it. */
    fun watchSegment(seg: Segment) {
        allowed += seg.uuid
        player.seekTo(seg.startMs)
        player.play()
    }

    /**
     * A link tapped in the description: a timestamp in this video seeks to it, another
     * YouTube video opens here (Back returns), and other web links go to [openElsewhere]
     * (the browser). Links that aren't http(s) are ignored.
     */
    fun openDescriptionLink(link: String, openElsewhere: (Uri) -> Unit) {
        val uri = link.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return
        val id = youtubeVideoId(link)
        when {
            id == null -> openElsewhere(uri)
            id != currentUrl?.let(::videoIdOf) -> openLink(watchUrl(id), OpenSource.Link)
            else -> startSeconds(uri)?.let { seconds ->
                player.seekTo(seconds * 1000)
                player.play()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
}

private fun String.sameVideoAs(other: String?): Boolean =
    other != null && videoIdOf(this)?.let { it == videoIdOf(other) } == true

/** Where a link's `t=` asks to start, in seconds: "90", "90s" or "1h2m3s". */
private fun startSeconds(uri: Uri): Long? {
    val t = uri.getQueryParameter("t") ?: uri.getQueryParameter("start") ?: return null
    t.toLongOrNull()?.let { return it }
    val (h, m, s) = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(t)?.destructured
        ?: return null
    if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
    return (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
}

/** "1 view", "532 views", "1.2M subscribers": [noun] is the singular. Empty if unknown (negative). */
internal fun formatCount(count: Long, noun: String): String = when {
    count < 0 -> ""
    count == 1L -> "1 $noun"
    count < 1_000 -> "$count ${noun}s"
    count < 1_000_000 -> "${compact(count / 1_000.0)}K ${noun}s"
    count < 1_000_000_000 -> "${compact(count / 1_000_000.0)}M ${noun}s"
    else -> "${compact(count / 1_000_000_000.0)}B ${noun}s"
}

/** 1.2 for small numbers, 12 / 120 once there are two or more digits, like YouTube. */
private fun compact(value: Double): String =
    if (value < 10) String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")
    else value.toLong().toString()
