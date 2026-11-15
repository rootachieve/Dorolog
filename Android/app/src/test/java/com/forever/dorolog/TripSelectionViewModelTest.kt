package com.forever.dorolog

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TripSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private val requests = mutableListOf<CreateTripRequestDto>()
    private fun trip(id: String = "new-server-trip", destination: String? = null) = OngoingTripDto(
        id = id, originName = "서울", originLatitude = 37.56, originLongitude = 126.98,
        destinationName = destination, status = "active", startedAt = "2026-11-13T00:00:00Z", storyCount = 0)
    private fun repository(create: suspend (CreateTripRequestDto) -> OngoingTripDto = { trip(destination = it.destinationName) }) =
        TripSelectionRepository(loadTrips = { OngoingTripsResponseDto(emptyList()) },
            createTrip = { requests += it; create(it) })

    @Test fun newTripWaitsForNoDestinationConfirmation_thenCreatesWithRealOrigin() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        assertTrue(vm.uiState.value.showDestinationDialog)
        assertNull(vm.uiState.value.startedTripId)
        assertTrue(requests.isEmpty())
        vm.confirmWithoutDestination(); runCurrent()
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
        assertNull(requests.single().destinationName)
        assertEquals("서울시", requests.single().originName)
        assertEquals(37.56, requests.single().originLatitude!!, 0.0)
    }

    @Test fun destinationSetWhileDialogIsOpen_closesDialogAndStartsWithDestination() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        vm.onNavigationChanged(Destination("새 목적지", 35.16, 129.16, "부산시"), true)
        vm.onNavigationChanged(Destination("새 목적지", 35.16, 129.16, "부산시"), true)
        runCurrent()
        assertFalse(vm.uiState.value.showDestinationDialog)
        assertEquals("새 목적지", requests.single().destinationName)
        assertEquals("새 목적지", vm.uiState.value.startedDestination?.name)
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
    }

    @Test fun destinationSetBeforeLocationArrives_waitsThenStartsAutomatically() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.startNewTrip(); runCurrent()
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16), true)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertNull(vm.uiState.value.startError)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        runCurrent()
        assertEquals("부산", requests.single().destinationName)
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
        assertFalse(vm.uiState.value.showDestinationDialog)
    }

    @Test fun dismissedDialogDoesNotAutoStartWhenDestinationIsSet() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        vm.dismissDestinationDialog()
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16), true)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertNull(vm.uiState.value.startedTripId)
    }

    @Test fun navigationFailureToastIsConsumedAndCanBeShownOnAnotherAttempt() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, false)
        vm.startNewTrip(); runCurrent()
        assertNotNull(vm.uiState.value.toastMessage)
        vm.consumeToast()
        assertNull(vm.uiState.value.toastMessage)
        vm.startNewTrip(); runCurrent()
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(requests.isEmpty())
    }

    @Test fun failedListLoadShowsToast_andSuccessfulEmptyRetryClearsListError() = runTest(dispatcher) {
        var loads = 0
        val vm = TripSelectionViewModel(TripSelectionRepository(loadTrips = {
            if (loads++ == 0) throw IOException("connection failed")
            OngoingTripsResponseDto(emptyList())
        }))
        runCurrent()
        assertNotNull(vm.uiState.value.listError)
        assertNotNull(vm.uiState.value.toastMessage)
        vm.consumeToast()
        vm.retry(); runCurrent()
        assertNull(vm.uiState.value.listError)
        assertNull(vm.uiState.value.toastMessage)
        assertTrue(vm.uiState.value.trips.isEmpty())
    }

    @Test fun duplicateTapsCreateOnlyOnce_andUseResponseIdAndDestination() = runTest(dispatcher) {
        val created = CompletableDeferred<OngoingTripDto>()
        val vm = TripSelectionViewModel(repository { created.await() })
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); vm.startNewTrip(); runCurrent()
        assertEquals(1, requests.size)
        assertEquals("부산", requests.single().destinationName)
        val request = JsonParser.parseString(Gson().toJson(requests.single())).asJsonObject
        assertEquals("부산시", request["destinationAddress"]?.asString)
        created.complete(Gson().fromJson("""{
            "id":"server-id","originName":"서울","originLatitude":37.56,"originLongitude":126.98,
            "destinationName":"저장된 목적지","destinationAddress":"부산광역시 동구",
            "destinationLatitude":null,"destinationLongitude":null,"status":"active",
            "startedAt":"2026-11-13T00:00:00Z","endedAt":null,"storyCount":0
        }""", OngoingTripDto::class.java)); runCurrent()
        assertEquals("server-id", vm.uiState.value.startedTripId)
        assertEquals("저장된 목적지", vm.uiState.value.startedDestination?.name)
        assertEquals("부산광역시 동구", vm.uiState.value.startedDestination?.address)
    }

    @Test fun unavailableNavigationAndMissingLocationDoNotCreate_andCanRetryOnceKnown() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, false)
        vm.startNewTrip(); runCurrent()
        assertFalse(vm.uiState.value.showDestinationDialog)
        assertNotNull(vm.uiState.value.startError)
        assertTrue(requests.isEmpty())
        vm.onNavigationChanged(null, true)
        vm.retryStart(); runCurrent(); vm.confirmWithoutDestination(); runCurrent()
        assertTrue(requests.isEmpty())
        assertNotNull(vm.uiState.value.startError)
        vm.onLocationChanged(VehicleLocation("서울시"))
        vm.confirmWithoutDestination(); runCurrent()
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
        assertNull(requests.single().originLatitude)
        assertNull(requests.single().originLongitude)
    }

    @Test fun cancelledDialogDoesNotCreateAnyDraft() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(null, true)
        vm.startNewTrip(); runCurrent(); vm.dismissDestinationDialog()
        vm.startNewTrip(); runCurrent()
        assertNull(vm.uiState.value.startedTripId)
        assertTrue(requests.isEmpty())
    }

    @Test fun ambiguousCreateFailure_doesNotRepeatCreationOnRetryOrNewTrip_andCanRefresh() = runTest(dispatcher) {
        var loads = 0
        val vm = TripSelectionViewModel(TripSelectionRepository(
            loadTrips = { loads++; OngoingTripsResponseDto(listOf(trip("created-before-disconnect"))) },
            createTrip = { requests += it; throw IOException("lost response") }))
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        vm.retryStart(); vm.startNewTrip(); runCurrent()
        assertEquals(1, requests.size)
        assertFalse(vm.uiState.value.canStartNewTrip)
        assertFalse(vm.uiState.value.canRetryStart)
        assertNull(vm.uiState.value.startedTripId)
        assertNotNull(vm.uiState.value.startError)
        vm.retry(); runCurrent()
        assertEquals(2, loads)
        assertEquals("created-before-disconnect", vm.uiState.value.trips.single().id)
    }

    @Test fun definitiveCreateRejectionAllowsRetry() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository {
            if (requests.size == 1) throw HttpException(Response.error<OngoingTripDto>(422, "{}".toResponseBody()))
            trip()
        })
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        assertNull(vm.uiState.value.startedTripId)
        assertTrue(vm.uiState.value.canStartNewTrip)
        assertTrue(vm.uiState.value.canRetryStart)
        vm.retryStart(); runCurrent()
        assertEquals(2, requests.size)
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
    }

    @Test fun definitiveCreateRejectionAllowsAnotherNewTripSelection() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository {
            if (requests.size == 1) throw HttpException(Response.error<OngoingTripDto>(422, "{}".toResponseBody()))
            trip()
        })
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16, "부산시"), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        vm.startNewTrip(); runCurrent()
        assertEquals(2, requests.size)
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
    }

    @Test fun activeTripStartsWithExistingIdAndCurrentDestination_withoutResume() = runTest(dispatcher) {
        val resumes = mutableListOf<String>()
        val vm = TripSelectionViewModel(TripSelectionRepository(
            loadTrips = { OngoingTripsResponseDto(listOf(trip("selected/active", "저장된 목적지"))) },
            createTrip = { error("active selection must not create") },
            resumeTrip = { resumes += it; trip(it) }))
        runCurrent()
        vm.onNavigationChanged(Destination("새 목적지", 35.16, 129.16, "부산시"), true)
        vm.resumeTrip("selected/active"); runCurrent()
        assertTrue(resumes.isEmpty())
        assertEquals("selected/active", vm.uiState.value.startedTripId)
        assertEquals("새 목적지", vm.uiState.value.startedDestination?.name)
        assertFalse(vm.uiState.value.showDestinationDialog)
    }

    @Test fun finishedTripWaitsForDestinationConfirmation_thenResumesWithoutCreating() = runTest(dispatcher) {
        val resumes = mutableListOf<String>()
        val resumed = CompletableDeferred<OngoingTripDto>()
        val vm = TripSelectionViewModel(TripSelectionRepository(
            loadTrips = { OngoingTripsResponseDto(listOf(trip("selected/old", "이전 목적지").copy(status = "finished"))) },
            createTrip = { error("resume must not create") },
            resumeTrip = { resumes += it; resumed.await() }))
        runCurrent()
        vm.onNavigationChanged(null, true)
        vm.resumeTrip("unknown"); runCurrent()
        assertTrue(resumes.isEmpty())
        vm.resumeTrip("selected/old"); runCurrent()
        assertTrue(vm.uiState.value.showDestinationDialog)
        assertTrue(resumes.isEmpty())
        vm.confirmWithoutDestination(); runCurrent()
        assertEquals(listOf("selected/old"), resumes)
        assertNull(vm.uiState.value.startedTripId)
        assertTrue(vm.uiState.value.starting)
        resumed.complete(trip("selected/old", "저장된 목적지")); runCurrent()
        assertEquals("selected/old", vm.uiState.value.startedTripId)
        assertNull(vm.uiState.value.startedDestination)
        assertFalse(vm.uiState.value.showDestinationDialog)
    }

    @Test fun failedResumeCanRetryTheSameId_withoutCreating() = runTest(dispatcher) {
        val resumes = mutableListOf<String>()
        val vm = TripSelectionViewModel(TripSelectionRepository(
            loadTrips = { OngoingTripsResponseDto(listOf(trip("chosen").copy(status = "finished"))) },
            resumeTrip = { resumes += it; if (resumes.size == 1) throw IOException("lost response") else trip(it) }))
        runCurrent()
        vm.onNavigationChanged(Destination("부산"), true)
        vm.resumeTrip("chosen"); vm.resumeTrip("chosen"); runCurrent()
        assertNotNull(vm.uiState.value.startError)
        vm.retryStart(); runCurrent()
        assertEquals(listOf("chosen", "chosen"), resumes)
        assertEquals("chosen", vm.uiState.value.startedTripId)
    }

    @Test fun invalidCoordinatePairDoesNotSendCreate() = runTest(dispatcher) {
        val vm = TripSelectionViewModel(repository())
        vm.onNavigationChanged(Destination("부산", 35.16, null), true)
        vm.onLocationChanged(VehicleLocation("서울시", 37.56, 126.98))
        vm.startNewTrip(); runCurrent()
        assertTrue(requests.isEmpty())
        assertNotNull(vm.uiState.value.startError)
        assertTrue(vm.uiState.value.canStartNewTrip)
        assertTrue(vm.uiState.value.canRetryStart)
        vm.onNavigationChanged(Destination("부산", 35.16, 129.16), true)
        vm.retryStart(); runCurrent()
        assertEquals("new-server-trip", vm.uiState.value.startedTripId)
    }
}
