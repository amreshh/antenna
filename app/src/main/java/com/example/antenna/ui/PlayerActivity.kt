package com.example.antenna.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
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
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Test tags for the player's state, visible to UI Automator as resource ids: the smoke test
 * (.github/scripts/smoke-test.sh) reads them to tell a loaded video from an error.
 */
internal const val TAG_TITLE = "player_title"
internal const val TAG_ERROR = "player_error"

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
        /** As YouTube sends it (HTML with links); shown by [DescriptionBox]. */
        val description: Description? = null,
    ) : PlayerUiState
}

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private val YOUTUBE_URL = Regex("""https?://(www\.|m\.)?(youtube\.com|youtu\.be)/\S+""")

        /** Resume only if you got past this point and aren't basically finished. */
        private const val RESUME_MIN_MS = 10_000L

        // Kept when Android recreates the screen, so it comes back on the same video.
        private const val STATE_URL = "url"
        private const val STATE_BACK_STACK = "back_stack"
        private const val STATE_FULLSCREEN = "fullscreen"

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

        val url = savedInstanceState?.getString(STATE_URL) ?: resolveUrl(intent)
        if (url == null) {
            Toast.makeText(this, "Not a YouTube video link", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        savedInstanceState?.getStringArrayList(STATE_BACK_STACK)?.let(backStack::addAll)
        userFullscreen.value = savedInstanceState?.getBoolean(STATE_FULLSCREEN) == true

        prefs = Prefs(this)
        player = ExoPlayer.Builder(this)
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
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                uiState.value = PlayerUiState.Error(
                    "Playback failed (${error.errorCodeName}): ${error.cause?.message ?: error.message}",
                )
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) saveProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Keep the screen on while a video plays, not while it's paused.
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    saveProgress()
                }
            }
        })

        setContent {
            AntennaTheme {
                val subscribed by subscriptions.channels.collectAsStateWithLifecycle()
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
                    onLink = ::openLink,
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, currentUrl)
        outState.putStringArrayList(STATE_BACK_STACK, ArrayList(backStack))
        outState.putBoolean(STATE_FULLSCREEN, userFullscreen.value)
    }

    /** Rotation, resizing and dark mode are handled here (see the manifest) rather than by restarting. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge() // the status bar icons follow dark mode
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
            history.flush() // the app may be closed from here without another chance to save
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
                    description = info.description,
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
                avatarUrl = info.uploaderAvatars.pick(minHeight = 120), // 40dp at up to 3x
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
                    if (prefs.reportSkips && reported.add(seg.uuid)) sponsorBlock.reportSkip(seg.uuid)
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

    /**
     * The video to play. Other apps can start this screen with anything, so only the video
     * id is taken from what they send, and the link is rebuilt from it.
     */
    private fun resolveUrl(intent: Intent): String? {
        val link = intent.getStringExtra(EXTRA_URL) ?: when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { YOUTUBE_URL.find(it)?.value }
            else -> null
        }
        return link?.let(::videoIdOf)?.let(::watchUrl)
    }

    /**
     * A link tapped in the description: a timestamp in this video seeks to it, another
     * YouTube video opens here (Back returns), and other web links open in the browser.
     * Links that aren't http(s) are ignored.
     */
    private fun openLink(link: String) {
        val uri = link.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return
        val id = youtubeVideoId(link)
        when {
            id == null -> try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
            id != currentUrl?.let(::videoIdOf) -> openVideo(watchUrl(id), addToBackStack = true)
            else -> startSeconds(uri)?.let { seconds ->
                player.seekTo(seconds * 1000)
                player.play()
            }
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
@kotlin.OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId
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
    onLink: (String) -> Unit,
) {
    val video = @Composable { modifier: Modifier ->
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
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
                is PlayerUiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_ERROR),
                )
                is PlayerUiState.Ready -> VideoDetails(state, isSubscribed, onToggleSubscribe, onSegmentClick, onLink)
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
        .semantics { testTagsAsResourceId = true }

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
    onLink: (String) -> Unit,
) {
    Text(state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(TAG_TITLE))
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

    DescriptionBox(state.stats, state.description, onLink)

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
private fun DescriptionBox(stats: String, description: Description?, onLink: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(description, linkColor) { descriptionText(description, linkColor) }
    if (stats.isBlank() && text.isBlank()) return
    var expanded by rememberSaveable(text.text) { mutableStateOf(false) }
    // Links in the description go to the player (timestamps, other videos) instead of
    // straight to another app.
    val links = remember(onLink) {
        object : UriHandler {
            override fun openUri(uri: String) = onLink(uri)
        }
    }
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
        if (text.isNotBlank()) {
            CompositionLocalProvider(LocalUriHandler provides links) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(
            if (expanded) "Show less" else "…more",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun descriptionText(description: Description?, linkColor: Color): AnnotatedString {
    val content = description?.content.orEmpty().trim()
    if (content.isEmpty()) return AnnotatedString("")
    // YouTube sends HTML (links, line breaks); fromHtml keeps the links tappable, and the
    // color shows which parts are links.
    return if (description?.type == Description.HTML) {
        AnnotatedString.fromHtml(content, linkStyles = TextLinkStyles(SpanStyle(color = linkColor)))
    } else {
        AnnotatedString(content)
    }
}

/** Where a link's `t=` asks to start, in seconds: "90", "90s" or "1h2m3s". */
private fun startSeconds(uri: Uri): Long? {
    val t = uri.getQueryParameter("t") ?: uri.getQueryParameter("start") ?: return null
    t.toLongOrNull()?.let { return it }
    val (h, m, s) = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(t)?.destructured
        ?: return null
    if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
    return (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
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
