package com.forever.dorolog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TripSummaryViewModel(
    tripId: String,
    private val repository: TripSummaryRepository = TripSummaryRepository(),
) : ViewModel() {
    data class UiState(
        val tripId: String,
        val stories: List<SavedTripStory> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val selectedStoryKey: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState(tripId))
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init { retry() }

    fun retry() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val stories = repository.load(uiState.value.tripId)
                ensureActive()
                _uiState.update { state -> state.copy(
                    stories = stories, loading = false,
                    selectedStoryKey = state.selectedStoryKey?.takeIf { key -> stories.any { it.key == key } },
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                _uiState.update { it.copy(loading = false, error = "저장한 이야기를 불러오지 못했어요. 다시 시도해 주세요") }
            }
        }
    }

    fun selectStory(key: String) {
        _uiState.update { state ->
            if (state.stories.none { it.key == key }) state
            else state.copy(selectedStoryKey = key.takeUnless { it == state.selectedStoryKey })
        }
    }

    fun clearSelection(key: String) {
        _uiState.update { if (it.selectedStoryKey == key) it.copy(selectedStoryKey = null) else it }
    }
}
