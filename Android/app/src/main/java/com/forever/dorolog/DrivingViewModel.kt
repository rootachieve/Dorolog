package com.forever.dorolog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface DrivingRuntime {
    interface Listener {
        fun onCallState(blocked: Boolean, available: Boolean)
        fun onSessionReady(sessionId: String)
        fun onAmplitude(amplitude: Int, speech: Boolean)
        fun onSpeechChanged(speaking: Boolean) = Unit
        fun onCaptureChanged(capturing: Boolean)
        fun onQuestion(question: String)
        fun onResponseStarted()
        fun onPlaybackChanged(playing: Boolean)
        fun onResponseFinished()
        fun onWaitingForAudio(waiting: Boolean)
        fun onServerUnavailable(message: String)
        fun onUnavailable(message: String)
    }

    fun observe(listener: Listener)
    fun refreshCallState()
    fun setForeground(foreground: Boolean) = Unit
    fun start(topic: RecommendedTopic, destination: Destination?, sessionId: String?,
        tripId: String)
    fun updateLocation(location: VehicleLocation) = Unit
    fun requestFollowUp(): Boolean
    fun stop()
    fun close()
}

internal class DrivingViewModel(
    private val runtime: DrivingRuntime,
) : ViewModel() {
    data class UiState(
        val question: String = "",
        val amplitudes: List<Int> = List(WAVEFORM_SIZE) { 0 },
        val paused: Boolean = true,
        val resumeEnabled: Boolean = false,
        val waitingForAudio: Boolean = false,
        val status: String? = null,
        val capturing: Boolean = false,
        val playing: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private var topic: RecommendedTopic? = null
    private var destination: Destination? = null
    private var tripId: String? = null
    private var sessionId: String? = null
    private var foreground = false
    private var manuallyPaused = false
    private var running = false
    private var safetyAvailable = false
    private var safetyBlocked = false
    private var callAvailable = false
    private var callBlocked = false
    private var serverReady = false
    private var serverSpeaking = false
    private var awaitingResponse = false
    private var silenceJob: Job? = null

    init {
        runtime.observe(object : DrivingRuntime.Listener {
            override fun onCallState(blocked: Boolean, available: Boolean) {
                callBlocked = blocked
                callAvailable = available
                if (running && (blocked || !available)) stopForPause()
                publishAvailability()
            }

            override fun onSessionReady(sessionId: String) {
                if (!running) return
                this@DrivingViewModel.sessionId = sessionId
                serverReady = true
                serverSpeaking = false
                _uiState.update { it.copy(status = null) }
                scheduleSilence()
            }

            override fun onAmplitude(amplitude: Int, speech: Boolean) {
                if (!running || !uiState.value.capturing || uiState.value.waitingForAudio) return
                _uiState.update { it.copy(amplitudes = it.amplitudes.drop(1) + amplitude.coerceIn(0, 100)) }
            }

            override fun onSpeechChanged(speaking: Boolean) {
                val state = uiState.value
                if (!running || !serverReady || !state.capturing || state.waitingForAudio ||
                    state.playing) return
                serverSpeaking = speaking
                if (speaking) {
                    awaitingResponse = false
                    cancelSilence()
                    _uiState.update { it.copy(status = "말소리를 듣고 있어요") }
                } else scheduleSilence(reset = true)
            }

            override fun onCaptureChanged(capturing: Boolean) {
                if (!running) return
                serverSpeaking = false
                _uiState.update { it.copy(capturing = capturing) }
                if (capturing) scheduleSilence() else cancelSilence()
            }

            override fun onQuestion(question: String) {
                if (!running || question.isBlank()) return
                _uiState.update { it.copy(question = question) }
            }

            override fun onResponseStarted() {
                if (!running) return
                awaitingResponse = true
                serverSpeaking = false
                cancelSilence()
                _uiState.update { it.copy(status = "질문을 준비하고 있어요") }
            }

            override fun onPlaybackChanged(playing: Boolean) {
                if (!running) return
                _uiState.update { it.copy(playing = playing, status = if (playing) "질문을 읽고 있어요" else null) }
                if (playing) cancelSilence()
            }

            override fun onResponseFinished() {
                if (!running) return
                awaitingResponse = false
                _uiState.update { it.copy(playing = false) }
                scheduleSilence()
            }

            override fun onWaitingForAudio(waiting: Boolean) {
                if (!running) return
                _uiState.update { it.copy(waitingForAudio = waiting, playing = false, status = null) }
                if (waiting) {
                    awaitingResponse = false
                    serverReady = false
                    serverSpeaking = false
                    cancelSilence()
                } else scheduleSilence()
            }

            override fun onServerUnavailable(message: String) {
                if (!running) return
                serverReady = false
                serverSpeaking = false
                awaitingResponse = false
                cancelSilence()
                _uiState.update { it.copy(status = message, playing = false) }
            }

            override fun onUnavailable(message: String) {
                if (!running) return
                stopForPause()
                publishAvailability()
                _uiState.update { it.copy(status = message) }
            }
        })
    }

    fun start(topic: RecommendedTopic, destination: Destination?, tripId: String) {
        if (this.topic != null) {
            if (this.tripId == tripId) return
            finish()
        }
        this.topic = topic
        this.destination = destination
        this.tripId = tripId
        _uiState.update { it.copy(question = topic.question) }
        runtime.setForeground(foreground)
        runtime.refreshCallState()
        if (!manuallyPaused && canResume()) begin() else {
            manuallyPaused = true
            publishAvailability()
        }
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        runtime.setForeground(foreground && topic != null)
        if (!foreground && topic != null) stopForPause()
        if (foreground) runtime.refreshCallState()
        publishAvailability()
    }

    fun onSafetyChanged(blocked: Boolean, available: Boolean) {
        safetyBlocked = blocked
        safetyAvailable = available
        if (running && (blocked || !available)) stopForPause()
        publishAvailability()
    }

    fun onLocationChanged(location: VehicleLocation) = runtime.updateLocation(location)

    fun pause() {
        stopForPause()
        publishAvailability()
    }

    fun resume() {
        runtime.refreshCallState()
        if (!canResume() || topic == null || running) {
            publishAvailability()
            return
        }
        manuallyPaused = false
        begin()
    }

    fun finish() {
        stopForPause()
        topic = null
        tripId = null
        sessionId = null
        manuallyPaused = false
        runtime.setForeground(false)
        publishAvailability()
    }

    private fun begin() {
        val selected = topic ?: return
        running = true
        serverReady = false
        serverSpeaking = false
        awaitingResponse = false
        _uiState.update { it.copy(paused = false, resumeEnabled = false, status = null) }
        runtime.start(selected, destination, sessionId, requireNotNull(tripId))
    }

    private fun stopForPause() {
        manuallyPaused = true
        running = false
        serverReady = false
        serverSpeaking = false
        awaitingResponse = false
        cancelSilence()
        runtime.stop()
        _uiState.update { it.copy(
            paused = true, waitingForAudio = false, capturing = false, playing = false, status = null,
            amplitudes = List(WAVEFORM_SIZE) { 0 },
        ) }
    }

    private fun canResume() = foreground && safetyAvailable && !safetyBlocked && callAvailable && !callBlocked

    private fun publishAvailability() {
        val blockedReason = when {
            !safetyAvailable -> "차량 안전 상태를 확인할 수 없어요"
            safetyBlocked -> "차량 안전 경고가 해제되면 재개할 수 있어요"
            !callAvailable -> "통화 상태를 확인할 수 없어요"
            callBlocked -> "통화가 끝나면 재개할 수 있어요"
            else -> null
        }
        _uiState.update { it.copy(
            paused = !running,
            resumeEnabled = topic != null && !running && canResume(),
            status = blockedReason ?: it.status.takeUnless {
                it == "차량 안전 상태를 확인할 수 없어요" ||
                    it == "차량 안전 경고가 해제되면 재개할 수 있어요" ||
                    it == "통화 상태를 확인할 수 없어요" || it == "통화가 끝나면 재개할 수 있어요"
            },
        ) }
    }

    private fun scheduleSilence(reset: Boolean = false) {
        if (reset) cancelSilence()
        val state = uiState.value
        if (!running || !serverReady || serverSpeaking || !state.capturing || state.waitingForAudio ||
            state.playing || awaitingResponse || silenceJob?.isActive == true) return
        silenceJob = viewModelScope.launch {
            for (remaining in (SILENCE_MILLIS / 1_000).toInt() downTo 1) {
                _uiState.update { it.copy(status = "무음 확인 중 · 다음 질문 요청까지 ${remaining}초") }
                delay(1_000)
            }
            if (running && serverReady && !serverSpeaking && uiState.value.capturing && !uiState.value.waitingForAudio &&
                !uiState.value.playing && !awaitingResponse) {
                if (runtime.requestFollowUp()) {
                    awaitingResponse = true
                    _uiState.update { it.copy(status = "다음 질문을 요청했어요 · 응답 대기 중") }
                } else if (running && serverReady) {
                    _uiState.update { it.copy(status = "다음 질문을 요청하지 못했어요. 일시정지 후 재개해 주세요") }
                }
            }
            silenceJob = null
        }
    }

    private fun cancelSilence() {
        silenceJob?.cancel()
        silenceJob = null
    }

    override fun onCleared() {
        cancelSilence()
        runtime.close()
    }

    companion object {
        const val WAVEFORM_SIZE = 96
        const val SILENCE_MILLIS = 5_000L
    }
}
