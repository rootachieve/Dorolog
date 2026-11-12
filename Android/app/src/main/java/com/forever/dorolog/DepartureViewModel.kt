package com.forever.dorolog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

class DepartureViewModel(
    private val repository: DepartureRepository = DepartureRepository(),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val previousConversationIds: List<String> = emptyList(),
) : ViewModel() {
    enum class Phase { Ready, Morphing, Talk, Arrived }

    data class UiState(
        val topics: List<RecommendedTopic> = emptyList(),
        val selectedTopicId: String? = null,
        val hasBrowsedTopics: Boolean = false,
        val loading: Boolean = true,
        val contentError: Boolean = false,
        val destination: Destination? = null,
        val navigationAvailable: Boolean? = null,
        val motionAvailable: Boolean? = null,
        val countdownSeconds: Int? = null,
        val phase: Phase = Phase.Ready,
    )

    private val restoredPhase = savedStateHandle.get<String>("phase").let { saved ->
        if (saved == "Fading") Phase.Morphing else Phase.entries.firstOrNull { it.name == saved } ?: Phase.Ready
    }
    private val committedTopic = if (restoredPhase == Phase.Ready) null else {
        savedStateHandle.get<String>("committedTopicId")?.let { id ->
            savedStateHandle.get<String>("committedQuestion")?.let { RecommendedTopic(id, it) }
        }
    }
    private val _uiState = MutableStateFlow(UiState(
        topics = committedTopic?.let { listOf(it) } ?: emptyList(),
        selectedTopicId = committedTopic?.id,
        hasBrowsedTopics = savedStateHandle["hasBrowsedTopics"] ?: false,
        loading = committedTopic == null,
        destination = savedStateHandle.get<String>("destinationName")?.let { Destination(it) },
        phase = restoredPhase,
    ))
    val uiState = _uiState.asStateFlow()
    private var active = false
    private var moving = false
    private var countdownJob: Job? = null
    private var loadJob: Job? = null

    init { if (committedTopic == null) retry() }

    fun retry() {
        loadJob?.cancel()
        cancelCountdown()
        val request = RecommendationRequestDto(uiState.value.destination?.name, previousConversationIds)
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, contentError = false) }
            try {
                val topics = repository.load(request)
                ensureActive()
                val selected = savedStateHandle.get<String>("selectedTopicId")
                    ?.takeIf { id -> topics.any { it.id == id } } ?: topics.firstOrNull()?.id
                savedStateHandle["selectedTopicId"] = selected
                _uiState.update { it.copy(
                    topics = topics, selectedTopicId = selected,
                    loading = false, contentError = false,
                ) }
                startCountdownIfReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, contentError = true) }
            }
        }
    }

    fun selectTopic(id: String) {
        if (uiState.value.phase != Phase.Ready || uiState.value.selectedTopicId == id ||
            uiState.value.topics.none { it.id == id }) return
        savedStateHandle["selectedTopicId"] = id
        savedStateHandle["hasBrowsedTopics"] = true
        _uiState.update { it.copy(selectedTopicId = id, hasBrowsedTopics = true) }
    }

    fun moveTopic(delta: Int) {
        val state = uiState.value
        val index = state.topics.indexOfFirst { it.id == state.selectedTopicId }
        state.topics.getOrNull(index + delta)?.let { selectTopic(it.id) }
    }

    fun setActive(active: Boolean) {
        this.active = active
        if (!active) { moving = false; cancelCountdown() }
    }

    fun onSpeedChanged(speed: Float?) {
        if (!active || uiState.value.phase != Phase.Ready) return
        val available = speed != null && speed.isFinite()
        _uiState.update { it.copy(motionAvailable = available) }
        moving = available && abs(speed!!) > 0f
        if (moving) startCountdownIfReady() else cancelCountdown()
    }

    fun onNavigationChanged(destination: Destination?, available: Boolean) {
        if (!active || uiState.value.phase != Phase.Ready) return
        if (!available) {
            _uiState.update { it.copy(navigationAvailable = false) }
            return
        }
        val changed = uiState.value.destination?.name != destination?.name
        savedStateHandle["destinationName"] = destination?.name
        _uiState.update { it.copy(destination = destination, navigationAvailable = available) }
        if (changed) retry()
    }

    private fun beginTransition() {
        if (uiState.value.phase != Phase.Ready) return
        val topic = uiState.value.topics.firstOrNull { it.id == uiState.value.selectedTopicId } ?: return
        cancelCountdown()
        savedStateHandle["committedTopicId"] = topic.id
        savedStateHandle["committedQuestion"] = topic.question
        setPhase(Phase.Morphing)
    }

    fun onMorphFinished() {
        if (uiState.value.phase == Phase.Morphing) setPhase(Phase.Talk)
    }

    fun onDestinationArrived(destination: Destination? = null) {
        if (uiState.value.phase != Phase.Talk && uiState.value.phase != Phase.Morphing) return
        cancelCountdown()
        destination?.let {
            savedStateHandle["destinationName"] = it.name
            _uiState.update { state -> state.copy(destination = it) }
        }
        setPhase(Phase.Arrived)
    }

    fun backFromTalk() {
        if (uiState.value.phase == Phase.Talk) {
            setPhase(Phase.Ready)
            retry()
        }
    }

    fun leave() {
        setActive(false)
        setPhase(Phase.Ready)
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.update { it.copy(countdownSeconds = null) }
    }

    private fun startCountdownIfReady() {
        val state = uiState.value
        if (!active || !moving || state.phase != Phase.Ready || state.loading || state.contentError ||
            state.selectedTopicId == null || countdownJob?.isActive == true) return
        _uiState.update { it.copy(countdownSeconds = 5) }
        countdownJob = viewModelScope.launch {
            for (remaining in 5 downTo 1) {
                _uiState.update { it.copy(countdownSeconds = remaining) }
                delay(1_000)
            }
            beginTransition()
        }
    }

    private fun setPhase(phase: Phase) {
        savedStateHandle["phase"] = phase.name
        _uiState.update { it.copy(phase = phase) }
    }
}
