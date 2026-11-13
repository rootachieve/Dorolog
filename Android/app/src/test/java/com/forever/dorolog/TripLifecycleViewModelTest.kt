package com.forever.dorolog

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripLifecycleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stories = listOf(ParkedStory("1", "바다 이야기", "부산"), ParkedStory("2", "가족 이야기", "양산"),
        ParkedStory("3", "추억 이야기", "울산"))
    private val repository = TripRepository(loadStories = { TripStories(stories, emptySet()) }, saveStories = { _, _ -> })

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.drivingTrip(trips: TripRepository = repository): DepartureViewModel {
        val vm = DepartureViewModel("trip", tripRepository = trips)
        runCurrent(); vm.setActive(true)
        vm.onNavigationChanged(Destination("부산"), true); runCurrent()
        vm.onSpeedChanged(1f); advanceTimeBy(5_000); runCurrent(); vm.onMorphFinished()
        vm.onSpeedChanged(0f)
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        return vm
    }

    @Test fun newViewModelStartsFresh_evenForSameTripChosenAgainFromHome() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleStory("2"); vm.toggleSkip()
        val fresh = DepartureViewModel("trip", tripRepository = repository)
        runCurrent()
        assertEquals("trip", fresh.uiState.value.tripId)
        assertEquals(DepartureViewModel.Phase.Ready, fresh.uiState.value.phase)
        assertNull(fresh.uiState.value.destination)
        assertFalse(fresh.uiState.value.arrived)
        assertFalse(fresh.uiState.value.skipUntilArrival)
        assertFalse(fresh.uiState.value.arrivalDialogPending)
        assertFalse(fresh.uiState.value.hasBrowsedTopics)
        assertTrue(fresh.uiState.value.stories.isEmpty())
        assertTrue(fresh.uiState.value.selectedStoryIds.isEmpty())
        assertFalse(fresh.uiState.value.canSelectStories)
    }

    @Test fun arrivalBeforePark_waitsForP_andOnlyThenLoadsStoriesForChosenTrip() = runTest(dispatcher) {
        val requests = mutableListOf<String>()
        val vm = drivingTrip(TripRepository(loadStories = { trip -> requests += trip; TripStories(stories, emptySet()) }))
        vm.onDestinationArrived(Destination("부산")); runCurrent()
        assertTrue(vm.uiState.value.arrived)
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canSelectStories); assertTrue(requests.isEmpty())
        vm.onParkedChanged(false); runCurrent()
        assertTrue(requests.isEmpty())
        vm.onParkedChanged(true); runCurrent()
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.canSelectStories)
        assertEquals(listOf("trip"), requests)
        assertEquals(stories, vm.uiState.value.stories)
    }

    @Test fun parkBeforeArrival_doesNotOpenSelectionUntilArrivalEvent() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true)
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canSelectStories)
        vm.onDestinationArrived(); runCurrent()
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.canSelectStories)
    }

    @Test fun arrivalWithPAndPositiveSpeed_waitsForZeroSpeed() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onSpeedChanged(5f); vm.onParkedChanged(true)
        vm.onDestinationArrived(); vm.onParkedChanged(true); runCurrent()
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canSelectStories)
        vm.onSpeedChanged(0f); runCurrent()
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.canSelectStories)
    }

    @Test fun sameNameDestination_requiresConfirmation_andImmediatelyLocksSelection() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleStory("1")
        vm.onNavigationChanged(Destination("부산"), true)
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.canSelectStories)
        vm.onDestinationSet(); vm.onSpeedChanged(0f); vm.onParkedChanged(true)
        assertFalse(vm.uiState.value.canSelectStories)
        vm.toggleStory("2")
        assertEquals(setOf("1"), vm.uiState.value.selectedStoryIds)
        // A queued update for the old route cannot confirm the next destination.
        vm.onNavigationChanged(Destination("부산"), true)
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        vm.onDestinationConfirmed(Destination("부산"))
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.arrived)
        assertEquals("trip", vm.uiState.value.tripId)
        assertEquals(setOf("1"), vm.uiState.value.selectedStoryIds)
        assertEquals(stories, vm.uiState.value.stories)
    }

    @Test fun cancelledDestinationChange_unlocksSameParkedChoices() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent(); vm.toggleStory("2")
        vm.onDestinationSet()
        assertFalse(vm.uiState.value.canSelectStories)
        vm.onDestinationConfirmed(null); vm.onNavigationChanged(null, true)
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.canSelectStories)
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
    }

    @Test fun stationaryGearChanges_keepChoices_andMovementKeepsSameTrip() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent(); vm.toggleStory("2")
        vm.onParkedChanged(false)
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canSelectStories)
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
        vm.onParkedChanged(true)
        assertTrue(vm.uiState.value.canSelectStories)
        vm.onSpeedChanged(1f)
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        assertEquals("trip", vm.uiState.value.tripId)
        assertEquals(stories, vm.uiState.value.stories)
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
        vm.onSpeedChanged(0f); vm.onParkedChanged(true)
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
    }

    @Test fun repeatedStopsKeepSameTripAndWholeTripStoriesAndUnsavedChoices() = runTest(dispatcher) {
        var response = TripStories(stories.take(2), setOf("1"))
        val requests = mutableListOf<String>()
        val vm = drivingTrip(TripRepository(loadStories = { trip -> requests += trip; response }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        assertEquals(setOf("1"), vm.uiState.value.selectedStoryIds)
        vm.toggleStory("1"); vm.toggleStory("2")
        vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("울산"))
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
        response = TripStories(stories, setOf("1", "3"))
        vm.onDestinationArrived(Destination("울산")); runCurrent()
        assertEquals("trip", vm.uiState.value.tripId)
        assertEquals(stories, vm.uiState.value.stories)
        assertEquals(setOf("2", "3"), vm.uiState.value.selectedStoryIds)
        assertEquals(listOf("trip", "trip"), requests)
    }

    @Test fun skipAppliesToActualNextDrive_andArrivalClearsItAndShowsDialog() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleSkip()
        assertTrue(vm.uiState.value.skipUntilArrival)
        vm.onDestinationArrived(Destination("부산"))
        assertTrue(vm.uiState.value.skipUntilArrival)
        assertFalse(vm.uiState.value.arrivalDialogPending)
        vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("울산"))
        assertEquals(DepartureViewModel.Phase.Skipping, vm.uiState.value.phase)
        vm.onSpeedChanged(10f); advanceTimeBy(10_000); runCurrent()
        assertEquals(DepartureViewModel.Phase.Skipping, vm.uiState.value.phase)
        vm.onSpeedChanged(0f); vm.onParkedChanged(true); vm.onDestinationArrived(Destination("울산")); runCurrent()
        assertEquals("trip", vm.uiState.value.tripId)
        assertFalse(vm.uiState.value.skipUntilArrival)
        assertTrue(vm.uiState.value.arrivalDialogPending)
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        vm.dismissArrivalDialog()
        assertFalse(vm.uiState.value.arrivalDialogPending)
        vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("서울"))
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
    }

    @Test fun cancellingSkipWhileParked_immediatelyOpensTopicsWithoutDroppingStories() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent(); vm.toggleStory("2")
        vm.toggleSkip(); vm.toggleSkip(); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertEquals("trip", vm.uiState.value.tripId)
        assertFalse(vm.uiState.value.skipUntilArrival)
        assertFalse(vm.uiState.value.arrived)
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.topics.isNotEmpty())
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
    }

    @Test fun cancellingSkipDuringNextDrive_opensTopicsAndDoesNotShowArrivalDialog() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleSkip(); vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("울산"))
        vm.toggleSkip(); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertEquals("trip", vm.uiState.value.tripId)
        assertFalse(vm.uiState.value.skipUntilArrival)
        assertFalse(vm.uiState.value.arrivalDialogPending)
        vm.onDestinationArrived(Destination("울산")); runCurrent()
        assertFalse(vm.uiState.value.arrivalDialogPending)
    }

    @Test fun eachSaveSendsWholeTripReplacement_includingDeselectionAndEmptySelection() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String, Set<String>>>()
        val vm = drivingTrip(TripRepository(loadStories = { TripStories(stories, emptySet()) },
            saveStories = { trip, ids -> requests += trip to ids }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleStory("1"); vm.toggleStory("2"); vm.saveStories(); runCurrent()
        assertTrue(vm.uiState.value.storiesSaved)
        vm.toggleStory("1"); vm.toggleStory("3")
        assertFalse(vm.uiState.value.storiesSaved)
        vm.saveStories(); runCurrent()
        vm.toggleStory("2"); vm.toggleStory("3"); vm.saveStories(); runCurrent()
        assertEquals(listOf(setOf("1", "2"), setOf("2", "3"), emptySet<String>()), requests.map { it.second })
        assertTrue(requests.all { it.first == "trip" })
        assertTrue(vm.uiState.value.savedStoryIds.isEmpty())
        assertTrue(vm.uiState.value.storiesSaved)
    }

    @Test fun firstLoadUsesServerSelection_refreshPreservesLocalEditsAndAddsNewSavedStories() = runTest(dispatcher) {
        var response = TripStories(stories.take(2), setOf("1"))
        val vm = drivingTrip(TripRepository(loadStories = { response }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        assertEquals(setOf("1"), vm.uiState.value.selectedStoryIds)
        assertEquals(setOf("1"), vm.uiState.value.savedStoryIds)
        vm.toggleStory("1"); vm.toggleStory("2")
        response = TripStories(stories, setOf("1", "3")); vm.retryStories(); runCurrent()
        assertEquals(setOf("2", "3"), vm.uiState.value.selectedStoryIds)
        assertEquals(setOf("1", "3"), vm.uiState.value.savedStoryIds)
        response = TripStories(stories.drop(1), setOf("3")); vm.retryStories(); runCurrent()
        assertEquals(setOf("2", "3"), vm.uiState.value.selectedStoryIds)
        assertEquals(stories.drop(1), vm.uiState.value.stories)
    }

    @Test fun loadIgnoringCancellation_cannotOverwriteMoreRecentWholeTripResult() = runTest(dispatcher) {
        val old = CompletableDeferred<TripStories>()
        var loads = 0
        val vm = drivingTrip(TripRepository(loadStories = {
            if (++loads == 1) withContext(NonCancellable) { old.await() } else TripStories(stories.drop(1), setOf("2"))
        }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        assertTrue(vm.uiState.value.storiesLoading)
        vm.retryStories(); runCurrent()
        assertEquals(stories.drop(1), vm.uiState.value.stories)
        old.complete(TripStories(stories, setOf("1"))); runCurrent()
        assertEquals(stories.drop(1), vm.uiState.value.stories)
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
        assertFalse(vm.uiState.value.storiesLoading)
    }

    @Test fun failedSaveKeepsLocalChoicesAndServerSelection_thenRetrySendsCompleteSet() = runTest(dispatcher) {
        var fail = true
        val sent = mutableListOf<Set<String>>()
        val vm = drivingTrip(TripRepository(loadStories = { TripStories(stories, setOf("1")) }, saveStories = { _, ids ->
            sent += ids
            if (fail) throw IOException("offline")
        }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleStory("2"); vm.saveStories(); runCurrent()
        assertEquals(setOf("1", "2"), vm.uiState.value.selectedStoryIds)
        assertEquals(setOf("1"), vm.uiState.value.savedStoryIds)
        assertFalse(vm.uiState.value.storiesSaved)
        assertFalse(vm.uiState.value.savingStories)
        assertNotNull(vm.uiState.value.storiesError)
        fail = false; vm.retryStories(); runCurrent(); vm.saveStories(); runCurrent()
        assertEquals(listOf(setOf("1", "2"), setOf("1", "2")), sent)
        assertEquals(setOf("1", "2"), vm.uiState.value.savedStoryIds)
        assertTrue(vm.uiState.value.storiesSaved)
    }

    @Test fun arrivalDuringSave_refreshesSameTripOnceAfterSaveCompletes() = runTest(dispatcher) {
        val saved = CompletableDeferred<Unit>()
        val loads = mutableListOf<String>()
        var serverSaved = emptySet<String>()
        var saving = false
        val vm = drivingTrip(TripRepository(loadStories = { trip ->
            assertFalse("A refresh must wait for the in-flight save", saving)
            loads += trip
            TripStories(if (loads.size == 1) stories.take(2) else stories, serverSaved)
        }, saveStories = { trip, ids ->
            assertEquals("trip", trip)
            saving = true
            saved.await()
            serverSaved = ids
            saving = false
        }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleStory("1"); vm.saveStories(); runCurrent()
        assertTrue(vm.uiState.value.savingStories)
        vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("울산"))
        vm.onDestinationArrived(Destination("울산")); runCurrent()
        vm.retryStories(); runCurrent()
        assertEquals(listOf("trip"), loads)
        assertEquals(stories.take(2), vm.uiState.value.stories)
        saved.complete(Unit); runCurrent()
        assertEquals(listOf("trip", "trip"), loads)
        assertEquals(stories, vm.uiState.value.stories)
        assertEquals(setOf("1"), vm.uiState.value.savedStoryIds)
        assertEquals(setOf("1"), vm.uiState.value.selectedStoryIds)
        assertFalse(vm.uiState.value.savingStories)
        assertFalse(vm.uiState.value.storiesLoading)
        assertEquals(DepartureViewModel.Phase.Arrived, vm.uiState.value.phase)
        assertEquals("trip", vm.uiState.value.tripId)
    }

    @Test fun summaryCancelsDeferredRefresh_whenEarlierSaveCompletes() = runTest(dispatcher) {
        val saved = CompletableDeferred<Unit>()
        var loads = 0
        val vm = drivingTrip(TripRepository(loadStories = {
            loads++
            TripStories(stories, emptySet())
        }, saveStories = { _, _ -> saved.await() }))
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.toggleStory("1"); vm.saveStories(); runCurrent()
        vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("울산"))
        vm.onDestinationArrived(Destination("울산")); runCurrent()
        assertEquals(1, loads)
        vm.finishTrip()
        saved.complete(Unit); runCurrent()
        assertEquals(1, loads)
        assertEquals(DepartureViewModel.Phase.Summary, vm.uiState.value.phase)
        assertEquals("trip", vm.uiState.value.tripId)
    }

    @Test fun finishTripKeepsChosenIdForSummaryAndIgnoresFurtherNavigation() = runTest(dispatcher) {
        val vm = drivingTrip()
        vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent(); vm.toggleStory("2")
        vm.finishTrip()
        assertEquals(DepartureViewModel.Phase.Summary, vm.uiState.value.phase)
        assertEquals("trip", vm.uiState.value.tripId)
        assertFalse(vm.uiState.value.canSelectStories)
        vm.onDestinationSet(); vm.onDestinationConfirmed(Destination("서울"))
        vm.onNavigationChanged(Destination("서울"), true); vm.onDestinationArrived()
        assertEquals(DepartureViewModel.Phase.Summary, vm.uiState.value.phase)
        assertEquals(setOf("2"), vm.uiState.value.selectedStoryIds)
    }
}
