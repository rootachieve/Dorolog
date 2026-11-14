package com.forever.dorolog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

class TripSelectionViewModel(private val repository: TripSelectionRepository = TripSelectionRepository()) : ViewModel() {
    data class UiState(
        val trips: List<OngoingTrip> = emptyList(),
        val loading: Boolean = true,
        val listError: String? = null,
        val starting: Boolean = false,
        val startError: String? = null,
        val showDestinationDialog: Boolean = false,
        val startedTripId: String? = null,
        val startedDestination: Destination? = null,
        val canStartNewTrip: Boolean = true,
        val canRetryStart: Boolean = true,
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var navigationAvailable: Boolean? = null
    private var destination: Destination? = null
    private var location = VehicleLocation()
    private var pendingTripId: String? = null
    private var pendingNewTrip = false
    private var browsingAllowed = true

    init { retry() }

    fun retry() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, listError = null) }
            try {
                val trips = repository.load()
                ensureActive()
                _uiState.update { it.copy(trips = trips, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, listError = "여행 목록을 가져오지 못했어요") }
            }
        }
    }

    fun onNavigationChanged(value: Destination?, available: Boolean) {
        navigationAvailable = available
        destination = value
    }
    fun onLocationChanged(value: VehicleLocation) { location = value }
    fun setBrowsingAllowed(value: Boolean) { browsingAllowed = value }
    fun clearVehicleContext() {
        navigationAvailable = null
        destination = null
        location = VehicleLocation()
    }

    fun startNewTrip() {
        if (uiState.value.canStartNewTrip) selectTrip(null)
    }
    fun resumeTrip(id: String) {
        if (uiState.value.trips.any { it.id == id }) selectTrip(id)
    }
    private fun selectTrip(id: String?) {
        if (!browsingAllowed || uiState.value.starting || uiState.value.startedTripId != null) return
        pendingNewTrip = id == null
        pendingTripId = id
        _uiState.update { it.copy(canRetryStart = true) }
        prepareStart()
    }

    fun retryStart() {
        if (!browsingAllowed || uiState.value.starting || uiState.value.startedTripId != null || !uiState.value.canRetryStart) return
        if (pendingTripId != null || pendingNewTrip) prepareStart()
    }

    private fun prepareStart() {
        _uiState.update { it.copy(starting = true, startError = null, showDestinationDialog = false) }
        viewModelScope.launch {
            try {
                val id = pendingTripId
                if (id != null) {
                    check(browsingAllowed) { "안전한 곳에 정차한 후 여행을 시작해 주세요" }
                    val selected = uiState.value.trips.first { it.id == id }
                    val trip = if (selected.status == "finished") repository.resume(id) else selected
                    ensureActive()
                    tripStarted(trip)
                } else {
                    check(navigationAvailable == true) { "내비게이션 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요" }
                    if (destination == null) _uiState.update { it.copy(showDestinationDialog = true) }
                    else submitNew(destination)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                showStartError(error)
            } finally { _uiState.update { it.copy(starting = false) } }
        }
    }

    fun dismissDestinationDialog() {
        if (!uiState.value.starting) _uiState.update { it.copy(showDestinationDialog = false, startError = null) }
    }
    fun confirmWithoutDestination() {
        if (!browsingAllowed || !uiState.value.showDestinationDialog || uiState.value.starting || !uiState.value.canStartNewTrip) return
        _uiState.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                check(navigationAvailable == true) { "내비게이션 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요" }
                submitNew(null)
                _uiState.update { it.copy(showDestinationDialog = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                showStartError(error)
            } finally { _uiState.update { it.copy(starting = false) } }
        }
    }

    private suspend fun submitNew(requestedDestination: Destination?) {
        check(browsingAllowed) { "안전한 곳에 정차한 후 여행을 시작해 주세요" }
        check(!location.address.isNullOrBlank() || location.latitude != null && location.longitude != null) {
            "현재 위치를 확인하지 못했어요. 잠시 후 다시 시도해 주세요"
        }
        val request = CreateTripRequestDto(location.address?.trim()?.takeIf { it.isNotEmpty() } ?: "위치 정보 없음",
            location.latitude, location.longitude, requestedDestination?.name,
            requestedDestination?.latitude, requestedDestination?.longitude, requestedDestination?.address)
        // A lost create response may already have created a trip; recover through the recent list.
        _uiState.update { it.copy(canStartNewTrip = false, canRetryStart = false) }
        val trip = try {
            repository.create(request)
        } catch (error: HttpException) {
            if (error.code() == 422) _uiState.update { it.copy(canStartNewTrip = true, canRetryStart = true) }
            throw error
        }
        currentCoroutineContext().ensureActive()
        tripStarted(trip)
    }

    private fun tripStarted(trip: OngoingTrip) {
        _uiState.update { it.copy(startedTripId = trip.id, startedDestination = trip.lastDestination?.let { place ->
            Destination(place.name, place.latitude, place.longitude, place.address)
        }) }
    }

    private fun showStartError(error: Exception) {
        _uiState.update { it.copy(showDestinationDialog = it.showDestinationDialog && it.canRetryStart,
            startError = if (!it.canRetryStart) "여행 생성 결과를 확인하지 못했어요. 최근 목록을 새로 불러와 이어가기를 선택해 주세요"
            else if (error is IllegalStateException) error.message else "여행을 시작하지 못했어요. 다시 시도해 주세요") }
    }
}
