package com.example.antenna.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
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
import com.example.antenna.App
import com.example.antenna.data.Channel
import com.example.antenna.data.Prefs
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.VideoItem
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.WatchLaterStore
import com.example.antenna.data.YouTubeRepository
import com.example.antenna.data.ageLabel
import com.example.antenna.data.formatDuration
import com.example.antenna.data.normalizeChannelUrl
import com.example.antenna.data.pick
import com.example.antenna.data.toVideoItem
import com.example.antenna.data.videoIdOf
import com.example.antenna.data.watchUrl
import com.example.antenna.data.youtubeVideoId
import com.example.antenna.player.StreamPicker
import com.example.antenna.sponsorblock.Segment
import com.example.antenna.sponsorblock.SponsorBlockClient
import com.example.antenna.sponsorblock.SponsorCategory
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

        /** Fresh links per video before giving up and showing the error. */
        const val MAX_REFRESHES = 3
    }

    private val app get() = getApplication<Application>()
    private val prefs = Prefs(app)
    private val subscriptions = SubscriptionStore.get(app)
    private val history = WatchHistoryStore.get(app)
    private val watchLater = WatchLaterStore.get(app)
    private val sponsorBlock = SponsorBlockClient(App.http)

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
        // No wake lock: the screen stays on while a video plays (keepScreenOn) and leaving
        // the app pauses it, so the CPU never needs keeping awake. (Media3 now asks for one
        // by default; the manifest removes its WAKE_LOCK permission.)
        .setWakeMode(C.WAKE_MODE_NONE)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = onPlaybackError(error)

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) saveProgress()
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    this@PlayerViewModel.isPlaying = playing
                    if (!playing) saveProgress()
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

    /** A video tapped in a list: it replaces whatever is playing, and Back minimizes it. */
    fun open(url: String) {
        if (url.sameVideoAs(currentUrl)) {
            expand()
            return
        }
        backStack.clear()
        show(url, addToBackStack = false)
        expand()
    }

    /**
     * A link from another app, or another video linked in the description: the video that's
     * playing goes on the back stack, so Back returns to it.
     */
    fun openLink(url: String) {
        if (!url.sameVideoAs(currentUrl)) show(url, addToBackStack = true)
        expand()
    }

    /** Stops the video and removes the player from the screen. */
    fun close() {
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
        val player = player
        val position = player.currentPosition
        val play = player.playWhenReady
        player.setMediaSource(
            StreamPicker.build(info, maxHeight = quality ?: prefs.maxHeight, http = App.http, preferred = quality),
            position,
        )
        player.prepare()
        player.playWhenReady = play
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
                val info = YouTubeRepository.streamInfo(url)
                if (url != currentUrl) return@launch
                currentInfo = info
                qualities = StreamPicker.qualities(info)
                val quality = chosenQuality?.takeIf { it in qualities }
                player.setMediaSource(
                    StreamPicker.build(info, maxHeight = quality ?: prefs.maxHeight, http = App.http, preferred = quality),
                    position,
                )
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
        saveProgress()
        history.flush()
        exoPlayer?.release()
        exoPlayer = null
    }

    // --- Loading ---------------------------------------------------------------------

    private fun show(url: String, addToBackStack: Boolean, autoplay: Boolean = true) {
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
                val previous = history.entryFor(url)
                val startAt = previous
                    ?.takeIf { !it.isWatched && it.positionMs > RESUME_MIN_MS }
                    ?.positionMs ?: 0L

                currentInfo = info
                qualities = StreamPicker.qualities(info)
                player.setMediaSource(StreamPicker.build(info, prefs.maxHeight, App.http), startAt)
                player.prepare()
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
                    player.seekTo(if (duration != C.TIME_UNSET) minOf(seg.endMs, duration) else seg.endMs)
                    toast("Skipped: ${SponsorCategory.labelFor(seg.category)}")
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
            id != currentUrl?.let(::videoIdOf) -> openLink(watchUrl(id))
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
