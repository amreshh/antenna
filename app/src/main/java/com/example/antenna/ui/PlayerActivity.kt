package com.example.antenna.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import com.example.antenna.data.ageLabel
import org.schabi.newpipe.extractor.stream.Description
import com.example.antenna.App
import com.example.antenna.data.Channel
import com.example.antenna.data.Prefs
import com.example.antenna.data.SubscriptionStore
import com.example.antenna.data.VideoItem
import com.example.antenna.data.WatchHistoryStore
import com.example.antenna.data.YouTubeRepository
import com.example.antenna.data.formatDuration
import com.example.antenna.data.normalizeChannelUrl
import com.example.antenna.data.toVideoItem
import com.example.antenna.data.videoIdOf
import com.example.antenna.player.StreamPicker
import com.example.antenna.sponsorblock.Segment
import com.example.antenna.sponsorblock.SponsorBlockClient
import com.example.antenna.sponsorblock.SponsorCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Error(val message: String) : PlayerUiState
    data class Ready(
        val title: String,
        val uploader: String,
        /** null if the uploader's channel couldn't be identified */
        val channel: Channel?,
        /** null while the SponsorBlock lookup is still running */
        val segments: List<Segment>?,
        /** YouTube's "up next" recommendations for this video */
        val related: List<VideoItem>,
        /** "1.2M views · 3 days ago" */
        val stats: String = "",
        val description: AnnotatedString = AnnotatedString(""),
    ) : PlayerUiState
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private val YOUTUBE_URL = Regex("""https?://(www\.|m\.)?(youtube\.com|youtu\.be)/\S+""")

        /** Resume only if you got past this point and aren't basically finished. */
        private const val RESUME_MIN_MS = 10_000L

        fun intent(context: Context, url: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URL, url)
    }

    private lateinit var player: ExoPlayer
    private lateinit var prefs: Prefs
    private val subscriptions by lazy { SubscriptionStore.get(this) }
    private val history by lazy { WatchHistoryStore.get(this) }
    private val sponsorBlock by lazy { SponsorBlockClient(App.http) }
    private val uiState = mutableStateOf<PlayerUiState>(PlayerUiState.Loading)

    /** Fullscreen chosen with the player's button (a phone held sideways is fullscreen anyway). */
    private val userFullscreen = mutableStateOf(false)

    // current video
    private var currentUrl: String? = null
    private var currentVideo: VideoItem? = null
    private var currentRelated: List<VideoItem> = emptyList()
    private var segments: List<Segment> = emptyList()
    private val allowed = mutableSetOf<String>()       // segments the user chose to watch
    private val reported = mutableSetOf<String>()      // skips already reported to SponsorBlock
    private var loadJob: Job? = null

    /** Videos you came from via "Up next", so Back goes to the previous one like YouTube. */
    private val backStack = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = resolveUrl(intent)
        if (url == null) {
            Toast.makeText(this, "Not a YouTube link", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        prefs = Prefs(this)
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    uiState.value = PlayerUiState.Error(
                        "Playback failed (${error.errorCodeName}): ${error.cause?.message ?: error.message}",
                    )
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) saveProgress()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) saveProgress()
                }
            })
        }

        setContent {
            AntennaTheme {
                val subscribed by subscriptions.channels.collectAsState()
                val state = uiState.value
                val config = LocalConfiguration.current
                // A phone on its side has no room for anything but the video. A tablet held
                // sideways does, so only go fullscreen there when asked to.
                val autoFullscreen = config.orientation == Configuration.ORIENTATION_LANDSCAPE && isShortWindow()
                val fullscreen = userFullscreen.value || autoFullscreen
                LaunchedEffect(fullscreen) { applyImmersive(fullscreen) }
                PlayerScreen(
                    player = player,
                    state = state,
                    fullscreen = fullscreen,
                    // can't leave fullscreen while the window is too short for the normal layout
                    showFullscreenButton = userFullscreen.value || !autoFullscreen,
                    onToggleFullscreen = { setUserFullscreen(!userFullscreen.value) },
                    isSubscribed = (state as? PlayerUiState.Ready)?.channel?.url
                        ?.let { u -> subscribed.any { it.url == u } } == true,
                    onToggleSubscribe = ::toggleSubscribe,
                    onSegmentClick = ::watchSegment,
                    onRelatedClick = { openVideo(it.url, addToBackStack = true) },
                )
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (userFullscreen.value) {
                setUserFullscreen(false)
                return@addCallback
            }
            val previous = backStack.removeLastOrNull()
            if (previous != null) openVideo(previous, addToBackStack = false) else finish()
        }

        openVideo(url, addToBackStack = false)

        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { skipLoop() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(5_000)
                    if (player.isPlaying) saveProgress()
                }
            }
        }
    }

    /** A link shared to the app while the player is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveUrl(intent)?.let { openVideo(it, addToBackStack = true) }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    // ---------------------------------------------------------------------------------

    private fun openVideo(url: String, addToBackStack: Boolean) {
        saveProgress()
        if (addToBackStack) currentUrl?.let { backStack.addLast(it) }
        currentUrl = url
        currentVideo = null
        currentRelated = emptyList()
        segments = emptyList()
        allowed.clear()

        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        uiState.value = PlayerUiState.Loading
        loadJob = lifecycleScope.launch { load(url) }
    }

    private suspend fun load(url: String) {
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
                currentRelated = related

                // Resume where you left off, unless you'd basically finished it.
                val previous = history.entryFor(url)
                val startAt = previous
                    ?.takeIf { !it.isWatched && it.positionMs > RESUME_MIN_MS }
                    ?.positionMs ?: 0L

                player.setMediaSource(StreamPicker.build(info, prefs.maxHeight, App.http), startAt)
                player.prepare()
                player.playWhenReady = true
                if (startAt > 0) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Resuming from ${formatDuration(startAt / 1000)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                // Add to history right away (like YouTube) and store the up-next list
                // so the For you tab can use it.
                history.record(
                    video,
                    positionMs = previous?.positionMs ?: 0L, // keep the old red bar until you move on
                    durationMs = previous?.durationMs?.takeIf { it > 0 } ?: (info.duration * 1000),
                    related = related,
                )

                val ready = PlayerUiState.Ready(
                    title = info.name,
                    uploader = info.uploaderName.orEmpty(),
                    channel = channelOf(info),
                    segments = null,
                    related = related,
                    stats = listOf(formatViews(info.viewCount), video.ageLabel())
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    description = descriptionText(info.description),
                )
                uiState.value = ready

                segments = segmentsJob.await()
                if (uiState.value is PlayerUiState.Ready) uiState.value = ready.copy(segments = segments)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState.value = PlayerUiState.Error(e.readable())
        }
    }

    private fun saveProgress() {
        if (!::player.isInitialized) return
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
                avatarUrl = info.uploaderAvatars.sortedBy { it.height }
                    .firstOrNull { it.height >= 48 }?.url ?: info.uploaderAvatars.lastOrNull()?.url,
            )
        }

    /** Checks the playhead a few times a second and jumps over sponsor segments. */
    private suspend fun skipLoop() {
        while (true) {
            if (segments.isNotEmpty() && player.playbackState == Player.STATE_READY) {
                val pos = player.currentPosition
                val seg = segments.firstOrNull {
                    it.uuid !in allowed && pos >= it.startMs && pos < it.endMs - 250
                }
                if (seg != null) {
                    val duration = player.duration
                    player.seekTo(if (duration != C.TIME_UNSET) minOf(seg.endMs, duration) else seg.endMs)
                    Toast.makeText(
                        this,
                        "Skipped: ${SponsorCategory.labelFor(seg.category)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (reported.add(seg.uuid)) sponsorBlock.reportSkip(seg.uuid)
                }
            }
            delay(200)
        }
    }

    private fun toggleSubscribe() {
        val channel = (uiState.value as? PlayerUiState.Ready)?.channel ?: return
        if (subscriptions.isSubscribed(channel.url)) {
            subscriptions.unsubscribe(channel.url)
        } else {
            subscriptions.subscribe(channel)
            Toast.makeText(this, "Subscribed to ${channel.name}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Tapping a segment in the list plays it instead of skipping it. */
    private fun watchSegment(seg: Segment) {
        allowed += seg.uuid
        player.seekTo(seg.startMs)
        player.play()
    }

    private fun resolveUrl(intent: Intent): String? {
        intent.getStringExtra(EXTRA_URL)?.let { return it }
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { YOUTUBE_URL.find(it)?.value }
            else -> null
        }
    }

    private fun setUserFullscreen(on: Boolean) {
        userFullscreen.value = on
        // Phones turn sideways for fullscreen video; tablets already have the room, so leave them be.
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyImmersive(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    player: Player,
    state: PlayerUiState,
    fullscreen: Boolean,
    showFullscreenButton: Boolean,
    onToggleFullscreen: () -> Unit,
    isSubscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    onSegmentClick: (Segment) -> Unit,
    onRelatedClick: (VideoItem) -> Unit,
) {
    val video = @Composable { modifier: Modifier ->
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    keepScreenOn = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.setFullscreenButtonState(fullscreen)
                view.setFullscreenButtonClickListener(
                    if (showFullscreenButton) PlayerView.FullscreenButtonClickListener { onToggleFullscreen() }
                    else null,
                )
            },
            // the view is rebuilt when the layout changes; let go of the player so the new one gets the picture
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { video(Modifier.fillMaxSize()) }
        return
    }

    val related = (state as? PlayerUiState.Ready)?.related.orEmpty()
    val details = @Composable {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                PlayerUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is PlayerUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is PlayerUiState.Ready -> VideoDetails(state, isSubscribed, onToggleSubscribe, onSegmentClick)
            }
        }
    }
    val upNext = @Composable {
        if (related.isNotEmpty()) {
            Text(
                "Up next",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            related.forEach { item -> VideoRow(item, onClick = { onRelatedClick(item) }) }
        }
    }

    val mainScroll = rememberScrollState()
    val sideScroll = rememberScrollState()
    // jump back to the top when a new video opens
    LaunchedEffect(state is PlayerUiState.Loading) {
        if (state is PlayerUiState.Loading) {
            mainScroll.scrollTo(0)
            sideScroll.scrollTo(0)
        }
    }

    val surface = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding()

    if (isExpandedWindow()) {
        // Tablet / big screen: video and details on the left, Up next alongside it.
        Row(surface) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(mainScroll)) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                details()
            }
            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(sideScroll)) {
                upNext()
            }
        }
    } else {
        // Phone (and narrow tablet windows): video on top, everything else scrolls below it.
        Column(surface) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.fillMaxSize().verticalScroll(mainScroll)) {
                details()
                upNext()
            }
        }
    }
}

@Composable
private fun VideoDetails(
    state: PlayerUiState.Ready,
    isSubscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    onSegmentClick: (Segment) -> Unit,
) {
    Text(state.title, style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = state.channel?.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(CircleShape),
        )
        Text(state.uploader, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (state.channel != null) {
            if (isSubscribed) {
                OutlinedButton(onClick = onToggleSubscribe) { Text("Subscribed") }
            } else {
                Button(onClick = onToggleSubscribe) { Text("Subscribe") }
            }
        }
    }

    DescriptionBox(state.stats, state.description)

    Text(
        "SponsorBlock",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    val segments = state.segments
    when {
        segments == null -> Text("Looking up segments…")
        segments.isEmpty() -> Text("No segments to skip in this video.")
        else -> {
            segments.forEach { seg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSegmentClick(seg) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "${SponsorCategory.labelFor(seg.category)}  " +
                            "${formatDuration(seg.startMs / 1000).ifEmpty { "0:00" }} → " +
                            formatDuration(seg.endMs / 1000),
                    )
                }
            }
            Text(
                "Tap a segment to watch it instead of skipping.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** YouTube-style description: a tinted box with views and date, a few lines, tap for the rest. */
@Composable
private fun DescriptionBox(stats: String, description: AnnotatedString) {
    if (stats.isBlank() && description.isBlank()) return
    var expanded by rememberSaveable(description.text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        if (stats.isNotBlank()) Text(stats, style = MaterialTheme.typography.labelLarge)
        if (description.isNotBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            if (expanded) "Show less" else "…more",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun descriptionText(description: Description?): AnnotatedString {
    val content = description?.content.orEmpty().trim()
    if (content.isEmpty()) return AnnotatedString("")
    // YouTube sends HTML (links, line breaks); fromHtml keeps the links tappable.
    return if (description?.type == Description.HTML) AnnotatedString.fromHtml(content)
    else AnnotatedString(content)
}

private fun formatViews(count: Long): String = when {
    count < 0 -> ""
    count == 1L -> "1 view"
    count < 1_000 -> "$count views"
    count < 1_000_000 -> "${compact(count / 1_000.0)}K views"
    count < 1_000_000_000 -> "${compact(count / 1_000_000.0)}M views"
    else -> "${compact(count / 1_000_000_000.0)}B views"
}

/** 1.2 for small numbers, 12 / 120 once there are two or more digits, like YouTube. */
private fun compact(value: Double): String =
    if (value < 10) String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")
    else value.toLong().toString()
