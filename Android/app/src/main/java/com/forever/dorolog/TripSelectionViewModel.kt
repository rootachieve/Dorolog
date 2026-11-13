package com.forever.dorolog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var navigationAvailable: Boolean? = null
    private var destination: Destination? = null
    private var location = VehicleLocation()
    private val createRequestId = UUID.randomUUID().toString()
    private var draftId: String? = null
    private var pendingTripId: String? = null
    private var pendingNewTrip = false
    // An unacknowledged start must survive switching cards to avoid duplicating a drive.
    private val pendingRequests = mutableMapOf<String, StartTripRequestDto>()
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

    fun startNewTrip() = selectTrip(null)
    fun resumeTrip(id: String) {
        if (uiState.value.trips.none { it.id == id }) return
        selectTrip(id)
    }
    private fun selectTrip(id: String?) {
        if (!browsingAllowed || uiState.value.starting || uiState.value.startedTripId != null) return
        pendingNewTrip = id == null
        pendingTripId = id ?: draftId
        prepareStart()
    }

    fun retryStart() {
        if (!browsingAllowed || uiState.value.starting || uiState.value.startedTripId != null) return
        prepareStart()
    }

    private fun prepareStart() {
        _uiState.update { it.copy(starting = true, startError = null, showDestinationDialog = false) }
        viewModelScope.launch {
            try {
                if (pendingTripId == null && pendingNewTrip) {
                    val id = repository.create(createRequestId)
                    ensureActive()
                    draftId = id
                    pendingTripId = id
                }
                requireNotNull(pendingTripId)
                if (pendingRequests.containsKey(pendingTripId)) submit()
                else {
                    check(navigationAvailable == true) { "내비게이션 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요" }
                    if (destination == null) _uiState.update { it.copy(showDestinationDialog = true) }
                    else submit()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(startError = if (error is IllegalStateException) error.message
                    else "여행을 시작하지 못했어요. 다시 시도해 주세요") }
            } finally { _uiState.update { it.copy(starting = false) } }
        }
    }

    fun dismissDestinationDialog() {
        if (!uiState.value.starting) _uiState.update { it.copy(showDestinationDialog = false, startError = null) }
    }
    fun confirmWithoutDestination() {
        if (!browsingAllowed || !uiState.value.showDestinationDialog || uiState.value.starting) return
        _uiState.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                check(navigationAvailable == true) { "내비게이션 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요" }
                submit(requestedDestination = null)
                _uiState.update { it.copy(showDestinationDialog = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(startError = if (error is IllegalStateException) error.message
                    else "여행을 시작하지 못했어요. 다시 시도해 주세요") }
            } finally { _uiState.update { it.copy(starting = false) } }
        }
    }

    private suspend fun submit(requestedDestination: Destination? = destination) {
        check(browsingAllowed) { "안전한 곳에 정차한 후 여행을 시작해 주세요" }
        val tripId = checkNotNull(pendingTripId)
        val request = pendingRequests[tripId] ?: run {
            val latitude = location.latitude
            val longitude = location.longitude
            check(latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                "현재 위치를 확인하지 못했어요. 잠시 후 다시 시도해 주세요"
            }
            val point = requestedDestination?.let {
                val lat = it.latitude
                val lon = it.longitude
                check(lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
                    lat in -90.0..90.0 && lon in -180.0..180.0) {
                    "목적지 위치를 확인하지 못했어요. 내비게이션을 확인해 주세요"
                }
                TripPlaceDto(it.name, it.address.orEmpty(), lat, lon)
            }
            StartTripRequestDto(UUID.randomUUID().toString(),
                TripPlaceDto(location.address.orEmpty(), location.address.orEmpty(), latitude, longitude),
                point, Instant.now().toString()).also { pendingRequests[tripId] = it }
        }
        val id = repository.start(tripId, request)
        currentCoroutineContext().ensureActive()
        _uiState.update { it.copy(startedTripId = id, startedDestination = request.destination?.let { place ->
            Destination(place.name.orEmpty(), place.latitude, place.longitude, place.address)
        }) }
    }
}
