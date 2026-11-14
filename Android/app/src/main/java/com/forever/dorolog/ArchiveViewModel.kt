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

class ArchiveViewModel(
    private val repository: ArchiveRepository = ArchiveRepository(),
) : ViewModel() {
    data class UiState(
        val tab: ArchiveTab = ArchiveTab.Trips,
        val sort: ArchiveSort = ArchiveSort.Latest,
        val content: ArchiveContent = ArchiveContent(),
        val selectedRegionId: String? = null,
        val isLoading: Boolean = true,
        val error: String? = null,
    ) {
        val visibleTrips: List<ArchiveTrip>
            get() = if (sort == ArchiveSort.Latest) content.trips.sortedByDescending { it.startedAt }
                else content.trips.sortedBy { it.startedAt }

        val visibleStories: List<ArchiveStory>
            get() {
                val stories = content.stories.filter { selectedRegionId == null || it.region == selectedRegionId }
                val byTime = compareBy<ArchiveStory> { it.recordedAt }
                return stories.sortedWith(compareBy<ArchiveStory> { it.recordedAt == null }
                    .then(if (sort == ArchiveSort.Latest) byTime.reversed() else byTime))
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init { retry() }

    fun selectTab(tab: ArchiveTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun selectSort(sort: ArchiveSort) {
        if (sort == uiState.value.sort) return
        _uiState.update { it.copy(sort = sort) }
    }

    fun selectRegion(id: String?) {
        if (id != null && uiState.value.content.regions.none { it.id == id }) return
        _uiState.update { it.copy(selectedRegionId = id) }
    }

    fun retry() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val content = repository.load()
                ensureActive()
                val selectedRegion = uiState.value.selectedRegionId?.takeIf { id -> content.regions.any { it.id == id } }
                _uiState.update { it.copy(content = content, selectedRegionId = selectedRegion, isLoading = false, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                _uiState.update { it.copy(isLoading = false, error = "여행을 불러오지 못했어요. 다시 시도해 주세요.") }
            }
        }
    }
}
