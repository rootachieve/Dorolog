package com.forever.dorolog

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private val requests = mutableListOf<Pair<String, StartTripRequestDto>>()
    private fun repository(create: suspend (CreateTripRequestDto) -> TripIdResponseDto = { TripIdResponseDto("new-server-trip") }) =
        TripSelectionRepository(loadTrips = { OngoingTripsResponseDto(emptyList()) }, createTrip = create,
            startTrip = { id, request -> requests += id to request; TripIdResponseDto(id) })

    @Test fun newTripWaitsForNoDestinationConfirmation_thenSendsNullWithRealOrigin() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        assertTrue(vm.uiState.value.showDestinationDialog)
        assertNull(vm.uiState.value.startedTripId)
        assertTrue(requests.isEmpty())
        vm.confirmWithoutDestination(); runCurrent()
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
        assertEquals("new-server-trip", requests.single().first)
        assertNull(requests.single().second.destination)
        assertEquals(37.56, requests.single().second.origin.latitude!!, 0.0)
    }

    @Test fun explicitStartWithoutDestination_staysNullWhenNavigationChangesWhileDialogIsOpen() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        assertTrue(vm.uiState.value.showDestinationDialog)
        vm.onNavigationChanged(Destination("새 목적지", 35.16, 129.16, "부산시"), true)
        vm.confirmWithoutDestination(); runCurrent()
        assertNull(requests.single().second.destination)
        assertNull(vm.uiState.value.startedDestination)
    }

    @Test fun resumeSendsChosenIdAndCurrentDestination_andDuplicateTapsDoNotCreateTrips() = runTest(dispatcher) {
        var creates = 0
        val create = CompletableDeferred<TripIdResponseDto>()
        val vm = TripSelectionViewModel(repository { creates++; create.await() })
        runCurrent()
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); vm.startNewTrip(); runCurrent()
        assertEquals(1, creates)
        create.complete(TripIdResponseDto("server-id")); runCurrent()
        assertEquals("server-id", vm.uiState.value.startedTripId)
        assertEquals("부산", requests.single().second.destination?.name)
    }

    @Test fun unavailableNavigationDoesNotBecomeNoDestination_andMissingLocationDoesNotSubmit() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, false)
        vm.startNewTrip(); runCurrent()
        assertFalse(vm.uiState.value.showDestinationDialog)
        assertNotNull(vm.uiState.value.startError)
        vm.onNavigationChanged(null, true)
        vm.retryStart(); runCurrent()
        vm.confirmWithoutDestination(); runCurrent()
        assertTrue(requests.isEmpty())
        assertNotNull(vm.uiState.value.startError)
        assertNull(vm.uiState.value.startedTripId)
    }

    @Test fun cancelledDialogDoesNotStartTrip_andDraftIsReused() = runTest(dispatcher) {
        var creates = 0
        val vm = TripSelectionViewModel(repository { creates++; TripIdResponseDto("draft") })
        vm.onNavigationChanged(null, true)
        vm.startNewTrip(); runCurrent(); vm.dismissDestinationDialog()
        assertNull(vm.uiState.value.startedTripId)
        vm.startNewTrip(); runCurrent()
        assertEquals(1, creates)
        assertTrue(requests.isEmpty())
    }

    @Test fun failedStartRetainsSamePayloadForIdempotentRetry() = runTest(dispatcher) {
        val attempts = mutableListOf<StartTripRequestDto>()
        val vm = TripSelectionViewModel(TripSelectionRepository(loadTrips = { OngoingTripsResponseDto(emptyList()) },
            createTrip = { TripIdResponseDto("draft") }, startTrip = { id, request ->
                attempts += request
                if (attempts.size == 1) throw IOException("lost response")
                TripIdResponseDto(id)
            }))
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        assertNull(vm.uiState.value.startedTripId)
        vm.retryStart(); runCurrent()
        assertEquals(2, attempts.size)
        assertEquals(attempts.first(), attempts.last())
        assertEquals("draft", vm.uiState.value.startedTripId)
    }

    @Test fun resumeUsesSelectedServerIdAndFreshNavigation_insteadOfHistoricalDestination() = runTest(dispatcher) {
        val place = TripPlaceDto("서울", "서울시", 37.56, 126.98)
        var creates = 0
        val repository = TripSelectionRepository(loadTrips = { OngoingTripsResponseDto(listOf(
            OngoingTripDto("selected/old", place, TripPlaceDto("옛 목적지", "옛 주소", 36.0, 127.0), "2026-11-13T00:00:00Z", 2))) },
            createTrip = { creates++; TripIdResponseDto("wrong") },
            startTrip = { id, request -> requests += id to request; TripIdResponseDto(id) })
        val vm = TripSelectionViewModel(repository)
        runCurrent()
        vm.onNavigationChanged(Destination("새 목적지", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("현재 위치", 37.5, 127.0))
        vm.resumeTrip("unknown"); runCurrent()
        assertTrue(requests.isEmpty())
        vm.resumeTrip("selected/old"); runCurrent()
        assertEquals(0, creates)
        assertEquals("selected/old", vm.uiState.value.startedTripId)
        assertEquals("새 목적지", requests.single().second.destination?.name)
        assertEquals("현재 위치", requests.single().second.origin.address)
    }

    @Test fun returningToTripAfterAnotherFailedSelection_reusesUnacknowledgedStart() = runTest(dispatcher) {
        val attempts = mutableListOf<Pair<String, StartTripRequestDto>>()
        val place = TripPlaceDto("서울", "서울시", 37.56, 126.98)
        val vm = TripSelectionViewModel(TripSelectionRepository(loadTrips = { OngoingTripsResponseDto(
            listOf("a", "b").map { OngoingTripDto(it, place, null, "2026-11-13T00:00:00Z", 0) }) },
            startTrip = { id, request -> attempts += id to request; throw IOException("response lost") }))
        runCurrent()
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.resumeTrip("a"); runCurrent()
        vm.resumeTrip("b"); runCurrent()
        vm.resumeTrip("a"); runCurrent()
        assertEquals(listOf("a", "b", "a"), attempts.map { it.first })
        assertEquals(attempts.first().second, attempts.last().second)
    }
}
