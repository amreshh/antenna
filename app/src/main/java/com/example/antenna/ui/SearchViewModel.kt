package com.example.antenna.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.antenna.data.VideoItem
import com.example.antenna.data.YouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class SearchUiState(
    val query: String = "",
    val items: List<VideoItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val canLoadMore: Boolean = false,
    /** What YouTube suggests for the query being typed. */
    val suggestions: List<String> = emptyList(),
    /** Whether the search box has the focus: the suggestions show instead of the results. */
    val editing: Boolean = false,
)

private const val SUGGEST_DELAY_MS = 150L
private const val MAX_SUGGESTIONS = 10

class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var activeQuery = ""
    private var nextPage: Page? = null
    private var job: Job? = null
    private var suggestJob: Job? = null

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        suggest(q.trim())
    }

    fun onEditingChange(editing: Boolean) = _state.update { it.copy(editing = editing) }

    /** A suggestion was picked: search for it. */
    fun searchFor(q: String) {
        _state.update { it.copy(query = q) }
        search()
    }

    /**
     * Fetches suggestions for what's typed, once typing pauses for a moment. They're only
     * nice to have, so a failure just leaves the last ones.
     */
    private fun suggest(q: String) {
        suggestJob?.cancel()
        if (q.isEmpty()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            val list = try {
                YouTubeRepository.suggestions(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            _state.update { it.copy(suggestions = list.take(MAX_SUGGESTIONS)) }
        }
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        activeQuery = q
        nextPage = null
        _state.update { it.copy(items = emptyList(), loading = true, error = null, canLoadMore = false) }
        job = viewModelScope.launch {
            try {
                val page = YouTubeRepository.search(q)
                nextPage = page.nextPage
                _state.update {
                    it.copy(items = page.items.newestFirst(), loading = false, canLoadMore = page.nextPage != null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    fun loadMore() {
        val page = nextPage ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            try {
                val more = YouTubeRepository.searchMore(activeQuery, page)
                nextPage = more.nextPage
                _state.update { s ->
                    s.copy(
                        items = (s.items + more.items).distinctBy { it.url }.newestFirst(),
                        loading = false,
                        canLoadMore = more.nextPage != null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, canLoadMore = false, error = e.readable()) }
            }
        }
    }
}

/**
 * Results newest first. YouTube no longer sorts search results by upload date (it ignores
 * that filter), so the app does, for the results loaded so far: a page that loads while
 * scrolling is merged in by date. Ties and unknown dates (last) keep YouTube's order.
 */
private fun List<VideoItem>.newestFirst(): List<VideoItem> = sortedByDescending { it.uploadedAtMs }

/** An error message for people, for the errors YouTube and the network commonly cause. */
internal fun Throwable.readable(): String = when (this) {
    is ReCaptchaException, is SignInConfirmNotBotException ->
        "YouTube is limiting requests from this network. Wait a while, or switch networks."
    is AgeRestrictedContentException -> "This video is age-restricted, which needs a signed-in account."
    is GeographicRestrictionException -> "This video isn't available in your country."
    is PaidContentException -> "This video is only for paying members."
    is PrivateContentException -> "This video is private."
    // YouTube's own reason is quoted in the message: "This video is unavailable", "…removed by the uploader"
    is ContentNotAvailableException ->
        message?.let { Regex("\"(.+)\"").find(it)?.groupValues?.get(1) } ?: "This video isn't available."
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        "Can't reach YouTube. Check your internet connection."
    else -> "${javaClass.simpleName}: ${message ?: "unknown error"}"
}
