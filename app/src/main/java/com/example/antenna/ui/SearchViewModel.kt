package com.example.antenna.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.antenna.data.VideoItem
import com.example.antenna.data.YouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
)

class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var activeQuery = ""
    private var nextPage: Page? = null
    private var job: Job? = null

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

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
                    it.copy(items = page.items, loading = false, canLoadMore = page.nextPage != null)
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
                        items = (s.items + more.items).distinctBy { it.url },
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
