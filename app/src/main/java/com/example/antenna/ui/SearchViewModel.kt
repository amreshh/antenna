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

internal fun Throwable.readable(): String =
    "${javaClass.simpleName}: ${message ?: "unknown error"}"
