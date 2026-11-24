package com.antenna.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antenna.data.Channel
import com.antenna.data.ChannelCheck
import com.antenna.data.SubscriptionCleanup
import com.antenna.data.SubscriptionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the subscription cleanup is: not started, checking channels, or checked. */
sealed interface CleanupState {
    data object Idle : CleanupState
    data class Checking(val done: Int, val total: Int) : CleanupState

    /** What checking found, for the confirmation ([SubscriptionCleanup.candidates] picks from it). */
    data class Checked(val channels: List<Channel>, val checks: Map<String, ChannelCheck>, val checkedAt: Long) : CleanupState
}

/** The Manage subscriptions screen's Clean up: checks every channel, then removes the ones you confirm. */
class CleanupViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SubscriptionStore.get(app)

    private val _state = MutableStateFlow<CleanupState>(CleanupState.Idle)
    val state: StateFlow<CleanupState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val channels = store.channels.value
        if (channels.isEmpty()) return
        _state.value = CleanupState.Checking(0, channels.size)
        job = viewModelScope.launch {
            val checks = SubscriptionCleanup.check(channels) { done, total ->
                // From several threads at once: never let the count go back.
                _state.update { if (it is CleanupState.Checking && it.done >= done) it else CleanupState.Checking(done, total) }
            }
            _state.value = CleanupState.Checked(channels, checks, System.currentTimeMillis())
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = CleanupState.Idle
    }

    /** Unsubscribes from these channels (by url). */
    fun remove(urls: Set<String>) {
        store.removeAll(urls)
        _state.value = CleanupState.Idle
    }
}
