package com.studio1950.melroad.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio1950.melroad.MelroadApp
import com.studio1950.melroad.R
import com.studio1950.melroad.domain.SessionState
import com.studio1950.melroad.domain.TripSummary
import com.studio1950.melroad.integration.DrivingUxState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { HOME, PREPARE, MEMORY, INFO }

class MelroadViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as MelroadApp).container
    private val session = container.sessionController

    val state: StateFlow<SessionState> = session.state
    val driving: StateFlow<DrivingUxState> = container.drivingMonitor.state
    val trips: StateFlow<List<TripSummary>> =
        container.repository.trips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _localMessage = MutableStateFlow<Int?>(null)
    val localMessage: StateFlow<Int?> = _localMessage.asStateFlow()

    fun onStart() {
        container.drivingMonitor.start()
    }

    fun go(target: Screen) {
        _screen.value = target
        if (target == Screen.PREPARE) session.checkLiveCapability()
    }

    fun visibleEvents(fromMs: Long, toMs: Long) = session.visibleEvents(fromMs, toMs)
    fun position(): Long = session.positionMs

    fun recheckCapability() = session.checkLiveCapability()
    fun startDemo(id: String) = session.startDemo(id)
    fun startLive() = session.startLive()
    fun pause() = session.pause()
    fun resume() = session.resume()
    fun finishTrip() = session.finishTrip()
    fun replayDraft() = session.replayDraft()
    fun saveDraft() = session.saveDraft()

    fun discardDraft() {
        session.discardDraft()
        _screen.value = Screen.HOME
    }

    fun closeFinished() {
        session.closeFinished()
        _screen.value = Screen.HOME
    }

    fun playMemory(id: Long) = session.playMemory(id)
    fun restartPlayback() = session.restartPlayback()
    fun stopPlayback() = session.stopPlayback()

    fun deleteTrip(id: Long) {
        if (driving.value.restricted) return
        if (state.value.memoryTripId == id) session.stopPlayback()
        viewModelScope.launch { runCatching { container.repository.delete(id) } }
    }

    fun deleteAll() {
        if (driving.value.restricted) return
        session.stopPlayback()
        viewModelScope.launch {
            runCatching { container.repository.deleteAll() }
            _localMessage.value = R.string.msg_all_deleted
        }
    }

    fun consumeMessages() {
        session.consumeMessage()
        _localMessage.value = null
    }
}
