package com.forever.dorolog

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
    tripId: String,
    private val repository: DepartureRepository = DepartureRepository(),
    private val previousConversationIds: List<String> = emptyList(),
    private val tripRepository: TripRepository = TripRepository(),
    initialDestination: Destination? = null,
) : ViewModel() {
    enum class Phase { Ready, Morphing, Talk, Arrived, Skipping, Summary }

    data class UiState(
        val tripId: String = "",
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
        val arrived: Boolean = false,
        val stories: List<ParkedStory> = emptyList(),
        val selectedStoryIds: Set<String> = emptySet(),
        val savedStoryIds: Set<String> = emptySet(),
        val storiesLoading: Boolean = false,
        val storiesError: String? = null,
        val savingStories: Boolean = false,
        val storiesSaved: Boolean = false,
        val skipUntilArrival: Boolean = false,
        val arrivalDialogPending: Boolean = false,
        val canSelectStories: Boolean = false,
        val finishingTrip: Boolean = false,
        val finishError: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState(tripId = tripId.also { require(it.isNotBlank()) }, destination = initialDestination))
    val uiState = _uiState.asStateFlow()
    private var active = false
    private var moving = false
    private var parked: Boolean? = null
    private var destinationSetPending = false
    private var storiesLoaded = false
    private var storiesRefreshPending = false
    private var countdownJob: Job? = null
    private var loadJob: Job? = null
    private var storiesJob: Job? = null
    private var requestedSaveIds: Set<String>? = null
    private var saveJob: Job? = null
    private var finishJob: Job? = null

    init { retry() }

    fun retry() {
        if (uiState.value.phase != Phase.Ready || uiState.value.finishingTrip) return
        loadJob?.cancel()
        cancelCountdown()
        val request = RecommendationRequestDto(uiState.value.destination?.name, previousConversationIds, uiState.value.tripId)
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, contentError = false) }
            try {
                val topics = repository.load(request)
                ensureActive()
                val selected = uiState.value.selectedTopicId?.takeIf { id -> topics.any { it.id == id } }
                    ?: topics.firstOrNull()?.id
                _uiState.update { it.copy(topics = topics, selectedTopicId = selected, loading = false, contentError = false) }
                startCountdownIfReady()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _uiState.update { it.copy(loading = false, contentError = true) } }
        }
    }

    fun selectTopic(id: String) {
        val state = uiState.value
        if (state.finishingTrip || state.phase != Phase.Ready || state.selectedTopicId == id || state.topics.none { it.id == id }) return
        _uiState.update { it.copy(selectedTopicId = id, hasBrowsedTopics = true) }
    }

    fun moveTopic(delta: Int) {
        val state = uiState.value
        state.topics.getOrNull(state.topics.indexOfFirst { it.id == state.selectedTopicId } + delta)?.let { selectTopic(it.id) }
    }

    fun setActive(active: Boolean) {
        this.active = active
        if (!active) { moving = false; parked = null; cancelCountdown() }
        updateSelectionAvailability()
    }

    fun onSpeedChanged(speed: Float?) {
        if (!active || uiState.value.finishingTrip) return
        val available = speed != null && speed.isFinite()
        moving = available && abs(speed!!) > 0f
        _uiState.update { it.copy(motionAvailable = available) }
        if (moving && uiState.value.phase == Phase.Arrived) beginRecording(null)
        if (!moving && parked == true && uiState.value.arrived && uiState.value.phase == Phase.Talk) onParkedChanged(true)
        updateSelectionAvailability()
        if (moving) startCountdownIfReady() else cancelCountdown()
    }

    fun onParkedChanged(value: Boolean?) {
        if (uiState.value.finishingTrip) return
        parked = value
        if (value == true && !moving && uiState.value.arrived && uiState.value.phase != Phase.Summary &&
            uiState.value.phase != Phase.Arrived) {
            cancelCountdown()
            _uiState.update { it.copy(phase = Phase.Arrived) }
            retryStories()
        }
        updateSelectionAvailability()
    }

    fun onDestinationSet() {
        if (uiState.value.phase == Phase.Summary || uiState.value.finishingTrip) return
        destinationSetPending = true
        updateSelectionAvailability()
    }

    fun onDestinationConfirmed(destination: Destination?) {
        if (!destinationSetPending || uiState.value.finishingTrip) return
        destinationSetPending = false
        if (destination != null && uiState.value.arrived) beginRecording(destination)
        updateSelectionAvailability()
    }

    fun onNavigationChanged(destination: Destination?, available: Boolean) {
        if (!active || uiState.value.phase == Phase.Summary || uiState.value.finishingTrip) return
        if (!available) { _uiState.update { it.copy(navigationAvailable = false) }; return }
        val state = uiState.value
        _uiState.update { it.copy(navigationAvailable = true) }
        if (destinationSetPending) return
        if (state.arrived) {
            // Ending navigation after arrival must not dismiss story selection.
            if (destination != null && state.destination?.name != destination.name) beginRecording(destination)
            return
        }
        val changed = state.destination?.name != destination?.name
        _uiState.update { it.copy(destination = destination) }
        if (changed && state.phase == Phase.Ready) retry()
    }

    private fun beginRecording(destination: Destination?) {
        val state = uiState.value
        val topic = state.topics.firstOrNull { it.id == state.selectedTopicId }
        storiesJob?.cancel()
        _uiState.update { it.copy(
            destination = destination,
            phase = if (state.skipUntilArrival) Phase.Skipping else if (topic != null) Phase.Talk else Phase.Ready,
            arrived = false, storiesLoading = false, storiesError = null, canSelectStories = false,
        ) }
        if (uiState.value.phase == Phase.Ready) retry()
    }

    private fun beginTransition() {
        val state = uiState.value
        if (state.phase != Phase.Ready || state.skipUntilArrival || state.arrived ||
            state.topics.none { it.id == state.selectedTopicId }) return
        cancelCountdown()
        _uiState.update { it.copy(phase = Phase.Morphing) }
    }

    fun onMorphFinished() {
        if (uiState.value.phase == Phase.Morphing && !uiState.value.finishingTrip) _uiState.update { it.copy(phase = Phase.Talk) }
    }

    fun onDestinationArrived(destination: Destination? = null) {
        val state = uiState.value
        if (state.arrived || state.phase == Phase.Summary || state.finishingTrip) return
        cancelCountdown()
        loadJob?.cancel()
        val skipped = state.phase == Phase.Skipping
        _uiState.update { it.copy(
            destination = destination ?: state.destination, arrived = true,
            phase = if (parked == true && !moving) Phase.Arrived else Phase.Talk,
            skipUntilArrival = if (skipped) false else state.skipUntilArrival,
            arrivalDialogPending = state.arrivalDialogPending || skipped,
        ) }
        updateSelectionAvailability()
        if (uiState.value.phase == Phase.Arrived) retryStories()
    }

    fun retryStories() {
        val state = uiState.value
        if (state.phase != Phase.Arrived || state.finishingTrip) return
        if (state.savingStories) { storiesRefreshPending = true; return }
        storiesRefreshPending = false
        storiesJob?.cancel()
        storiesJob = viewModelScope.launch {
            _uiState.update { it.copy(storiesLoading = true, storiesError = null) }
            try {
                val response = tripRepository.load(state.tripId)
                ensureActive()
                val availableIds = response.stories.map { it.id }.toSet()
                val knownIds = uiState.value.stories.map { it.id }.toSet()
                val selected = if (storiesLoaded) {
                    uiState.value.selectedStoryIds.intersect(availableIds) + (response.savedStoryIds - knownIds)
                } else response.savedStoryIds
                storiesLoaded = true
                _uiState.update { it.copy(stories = response.stories, selectedStoryIds = selected,
                    savedStoryIds = response.savedStoryIds, storiesLoading = false, storiesSaved = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(storiesLoading = false, storiesError = "이야기를 가져오지 못했어요. 다시 시도해 주세요") }
            }
        }
    }

    fun toggleStory(id: String) {
        val state = uiState.value
        if (!state.canSelectStories || state.savingStories || state.stories.none { it.id == id }) return
        val ids = if (id in state.selectedStoryIds) state.selectedStoryIds - id else state.selectedStoryIds + id
        _uiState.update { it.copy(selectedStoryIds = ids, storiesSaved = false) }
    }

    fun saveStories() {
        val state = uiState.value
        if (!state.canSelectStories || state.storiesLoading || state.storiesError != null || saveJob?.isActive == true) return
        val ids = state.selectedStoryIds.toSet()
        requestedSaveIds = ids
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(savingStories = true, storiesError = null) }
            try {
                tripRepository.save(state.tripId, ids)
                ensureActive()
                requestedSaveIds = null
                _uiState.update { it.copy(savedStoryIds = ids, savingStories = false, storiesSaved = it.selectedStoryIds == ids) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(savingStories = false, storiesError = "이야기를 저장하지 못했어요. 다시 시도해 주세요") }
            }
            if (storiesRefreshPending) {
                storiesRefreshPending = false
                if (uiState.value.phase == Phase.Arrived) {
                    val saveError = uiState.value.storiesError
                    retryStories()
                    if (saveError != null) _uiState.update { it.copy(storiesError = saveError) }
                }
            }
        }
    }

    fun toggleSkip() {
        val state = uiState.value
        if (state.phase == Phase.Summary || state.finishingTrip) return
        cancelCountdown()
        if (state.skipUntilArrival) {
            _uiState.update { it.copy(skipUntilArrival = false, phase = Phase.Ready, arrived = false, canSelectStories = false) }
            retry()
        } else if (state.phase == Phase.Arrived && state.canSelectStories) {
            _uiState.update { it.copy(skipUntilArrival = true) }
        }
    }

    fun dismissArrivalDialog() { _uiState.update { it.copy(arrivalDialogPending = false) } }

    fun finishTrip() {
        if (uiState.value.phase == Phase.Summary || finishJob?.isActive == true) return
        val retrying = uiState.value.finishingTrip && uiState.value.finishError != null
        cancelCountdown()
        storiesJob?.cancel(); loadJob?.cancel()
        _uiState.update { it.copy(finishingTrip = true, finishError = null,
            canSelectStories = false, storiesLoading = false, arrivalDialogPending = false) }
        finishJob = viewModelScope.launch {
            try {
                saveJob?.join()
                requestedSaveIds?.let { ids ->
                    check(retrying) { "선택한 이야기 저장이 완료되지 않았어요" }
                    tripRepository.save(uiState.value.tripId, ids)
                    ensureActive()
                    requestedSaveIds = null
                    _uiState.update { it.copy(savedStoryIds = ids, storiesError = null,
                        storiesSaved = it.selectedStoryIds == ids) }
                }
                tripRepository.finish(uiState.value.tripId)
                ensureActive()
                _uiState.update { it.copy(phase = Phase.Summary, finishingTrip = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(finishError = if (requestedSaveIds != null) {
                    "이야기 저장을 완료하지 못했어요. 다시 시도하면 저장 후 여행을 종료해요"
                } else "여행 종료를 완료하지 못했어요. 다시 시도해 주세요") }
            }
        }
    }

    fun backFromTalk() {
        if (uiState.value.phase == Phase.Talk && !uiState.value.arrived && !uiState.value.finishingTrip) {
            _uiState.update { it.copy(phase = Phase.Ready) }
            retry()
        }
    }

    fun leave() { setActive(false) }

    private fun updateSelectionAvailability() {
        _uiState.update { it.copy(canSelectStories = active && parked == true && !moving &&
            it.phase == Phase.Arrived && !destinationSetPending && !it.finishingTrip) }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel(); countdownJob = null
        _uiState.update { it.copy(countdownSeconds = null) }
    }

    private fun startCountdownIfReady() {
        val state = uiState.value
        if (!active || !moving || state.phase != Phase.Ready || state.loading || state.contentError || state.arrived ||
            state.skipUntilArrival || state.finishingTrip || state.selectedTopicId == null || countdownJob?.isActive == true) return
        _uiState.update { it.copy(countdownSeconds = 5) }
        countdownJob = viewModelScope.launch {
            for (remaining in 5 downTo 1) { _uiState.update { it.copy(countdownSeconds = remaining) }; delay(1_000) }
            beginTransition()
        }
    }
}
