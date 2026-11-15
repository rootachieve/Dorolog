package com.forever.dorolog

import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DepartureViewModelTest {
    @Test fun unknownNavigation_defersTopicsEvenWithStoredDestination() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String, TripTopicsRequestDto>>()
        val vm = DepartureViewModel("trip", DepartureRepository { id, request -> requests += id to request; sampleDepartureResponse },
            initialDestination = Destination("이전 목적지"))
        runCurrent()
        assertTrue(requests.isEmpty())
        vm.setActive(true)
        vm.onNavigationChanged(null, false); vm.retry(); runCurrent()
        assertTrue(requests.isEmpty())
        vm.onNavigationChanged(Destination("현재 목적지"), true); runCurrent()
        assertEquals(listOf("trip"), requests.map { it.first })
        assertEquals("현재 목적지", requests.single().second.destination?.name)
        assertEquals("현재 목적지", vm.uiState.value.destination?.name)
    }

    @Test fun selectedTripAndFreshNavigation_areUsedForFirstQuestionRequest() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String, TripTopicsRequestDto>>()
        val vm = DepartureViewModel("chosen/server id", DepartureRepository { id, request -> requests += id to request; sampleDepartureResponse },
            initialDestination = Destination("부산", 35.16, 129.16, "부산시"))
        vm.navigationReady(Destination("서울역", 37.55, 126.97, "서울시 용산구"))
        runCurrent()
        assertEquals("chosen/server id", requests.single().first)
        assertEquals(TopicDestinationDto("서울역", "서울시 용산구", 37.55, 126.97), requests.single().second.destination)
        assertEquals("서울역", vm.uiState.value.destination?.name)
    }
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun DepartureViewModel.navigationReady(destination: Destination? = null) = apply {
        setActive(true)
        onParkedChanged(false)
        onNavigationChanged(destination, true)
    }

    @Test fun apiResponse_becomesQuestions_andSelectionStaysInBounds() = runTest(dispatcher) {
        val response = Gson().fromJson(
            """{"topics":[{"id":"one","question":"첫 질문"},{"id":"two","question":"다음 질문"}]}""",
            DepartureResponseDto::class.java,
        )
        val vm = DepartureViewModel("trip", DepartureRepository { _, _ -> response }).navigationReady()
        runCurrent()
        assertEquals("one", vm.uiState.value.selectedTopicId)
        vm.moveTopic(-1)
        assertEquals("one", vm.uiState.value.selectedTopicId)
        vm.moveTopic(1); vm.moveTopic(1); vm.selectTopic("unknown")
        assertEquals("two", vm.uiState.value.selectedTopicId)
        assertEquals(listOf("첫 질문", "다음 질문"), vm.uiState.value.topics.map { it.question })
    }

    @Test fun malformedResponse_showsError_thenRetryLoadsQuestions() = runTest(dispatcher) {
        var fail = true
        val vm = DepartureViewModel("trip", DepartureRepository { _, _ ->
            if (fail) Gson().fromJson("{}", DepartureResponseDto::class.java) else sampleDepartureResponse
        }).navigationReady()
        runCurrent()
        assertTrue(vm.uiState.value.contentError)
        fail = false; vm.retry(); runCurrent()
        assertFalse(vm.uiState.value.contentError)
        assertEquals(3, vm.uiState.value.topics.size)
    }

    @Test fun browsingHidesTip_onlyAfterQuestionChanges_andSurvivesReloadInSameViewModel() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip").navigationReady()
        runCurrent()
        assertFalse(vm.uiState.value.hasBrowsedTopics)
        vm.selectTopic("together"); vm.moveTopic(-1); vm.selectTopic("unknown")
        assertFalse(vm.uiState.value.hasBrowsedTopics)
        vm.moveTopic(1)
        assertTrue(vm.uiState.value.hasBrowsedTopics)
        vm.moveTopic(-1); vm.retry(); runCurrent()
        assertTrue(vm.uiState.value.hasBrowsedTopics)
    }

    @Test fun navigationChanges_reloadCurrentDestination_andRepeatedSignalsDoNotReload() = runTest(dispatcher) {
        val requests = mutableListOf<TripTopicsRequestDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { _, request -> requests += request; sampleDepartureResponse })
        vm.navigationReady(Destination("부산 할머니댁", address = "부산시")); runCurrent()
        vm.onNavigationChanged(Destination("부산 할머니댁", address = "부산시"), true); runCurrent()
        vm.onNavigationChanged(Destination("서울", address = "서울시"), true); runCurrent()
        vm.onNavigationChanged(Destination("서울", address = "서울시 용산구"), true); runCurrent()
        vm.onNavigationChanged(Destination("서울", address = "서울시 용산구"), true); runCurrent()
        assertEquals(listOf("부산 할머니댁", "서울", "서울"), requests.map { it.destination?.name })
        assertEquals("서울시 용산구", requests.last().destination?.address)
        assertEquals("서울", vm.uiState.value.destination?.name)
    }

    @Test fun retry_cancelsOldRequest_andUsesLatestQuestions() = runTest(dispatcher) {
        val old = CompletableDeferred<DepartureResponseDto>()
        var requests = 0
        val vm = DepartureViewModel("trip", DepartureRepository { _, _ ->
            if (++requests == 1) old.await()
            else DepartureResponseDto(listOf(RecommendedTopicDto("latest", "새 질문")))
        }).navigationReady()
        runCurrent(); vm.retry(); runCurrent()
        old.complete(DepartureResponseDto(listOf(RecommendedTopicDto("old", "이전 질문")))); runCurrent()
        assertEquals(listOf("새 질문"), vm.uiState.value.topics.map { it.question })
    }

    @Test fun movement_countsFiveSeconds_withoutRestarting_thenMorphsOnlyOnce() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip").navigationReady()
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f); runCurrent()
        assertEquals(5, vm.uiState.value.countdownSeconds)
        advanceTimeBy(2_000); runCurrent(); vm.onSpeedChanged(2f)
        assertEquals(3, vm.uiState.value.countdownSeconds)
        advanceTimeBy(2_999); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(DepartureViewModel.Phase.Morphing, vm.uiState.value.phase)
        vm.onMorphFinished(); vm.onMorphFinished(); vm.selectTopic("memories")
        assertEquals(DepartureViewModel.Phase.Talk, vm.uiState.value.phase)
        assertEquals("together", vm.uiState.value.selectedTopicId)
        assertEquals("trip", vm.uiState.value.tripId)
    }

    @Test fun stoppingAndInvalidSignals_cancelCountdown_reverseRestartsFromFive() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip").navigationReady()
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(3_000); runCurrent(); vm.onSpeedChanged(0f)
        advanceTimeBy(6_000); runCurrent()
        assertNull(vm.uiState.value.countdownSeconds)
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        vm.onSpeedChanged(-1f); runCurrent()
        assertEquals(5, vm.uiState.value.countdownSeconds)
        vm.onSpeedChanged(Float.NaN)
        assertNull(vm.uiState.value.countdownSeconds)
        assertEquals(false, vm.uiState.value.motionAvailable)
    }

    @Test fun backgroundAndReturn_requireFreshSpeed() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip").navigationReady()
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(2_000); runCurrent(); vm.setActive(false)
        advanceTimeBy(10_000); runCurrent(); vm.setActive(true)
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertNull(vm.uiState.value.countdownSeconds)
        vm.onParkedChanged(false)
        vm.onSpeedChanged(1f); runCurrent()
        assertEquals(5, vm.uiState.value.countdownSeconds)
        vm.setActive(false)
    }

    @Test fun questionsMustBeReady_beforeAutomaticCountdownStarts() = runTest(dispatcher) {
        val response = CompletableDeferred<DepartureResponseDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { _, _ -> response.await() }).navigationReady()
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertNull(vm.uiState.value.countdownSeconds)
        response.complete(sampleDepartureResponse); runCurrent()
        assertEquals(5, vm.uiState.value.countdownSeconds)
        vm.setActive(false)
    }

    @Test fun emptyRecommendations_doNotStartConversation() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip", DepartureRepository { _, _ -> DepartureResponseDto(emptyList()) }).navigationReady()
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertNull(vm.uiState.value.countdownSeconds)
    }

    @Test fun temporaryNavigationError_doesNotClearDestination_untilNoRouteIsConfirmed() = runTest(dispatcher) {
        val requests = mutableListOf<TripTopicsRequestDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { _, request -> requests += request; sampleDepartureResponse })
        vm.navigationReady(Destination("부산")); runCurrent()
        vm.onNavigationChanged(null, false); runCurrent()
        assertEquals("부산", vm.uiState.value.destination?.name)
        vm.retry(); runCurrent()
        assertEquals(1, requests.size)
        assertTrue(vm.uiState.value.contentError)
        assertNull(vm.uiState.value.countdownSeconds)
        vm.onNavigationChanged(null, true); runCurrent()
        assertNull(vm.uiState.value.destination)
        assertEquals(2, requests.size)
        assertNull(requests.last().destination)
        assertFalse(vm.uiState.value.contentError)
    }

    @Test fun changedNavigation_cancelsOldTopicsBeforeTheyCanReplaceCurrentQuestions() = runTest(dispatcher) {
        val old = CompletableDeferred<DepartureResponseDto>()
        val requests = mutableListOf<String?>()
        val vm = DepartureViewModel("trip", DepartureRepository { _, request ->
            requests += request.destination?.name
            if (request.destination?.name == "부산") old.await()
            else DepartureResponseDto(listOf(RecommendedTopicDto("current", "서울 질문")))
        })
        vm.navigationReady(Destination("부산")); runCurrent()
        vm.onNavigationChanged(Destination("서울"), true); runCurrent()
        old.complete(DepartureResponseDto(listOf(RecommendedTopicDto("old", "부산 질문")))); runCurrent()
        assertEquals(listOf("부산", "서울"), requests)
        assertEquals(listOf("서울 질문"), vm.uiState.value.topics.map { it.question })
    }

    @Test fun unavailableNavigation_cancelsPendingTopicsAndAutomaticCountdown() = runTest(dispatcher) {
        val pending = CompletableDeferred<DepartureResponseDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { _, _ -> pending.await() })
        vm.navigationReady(Destination("부산")); runCurrent(); vm.onSpeedChanged(1f)
        vm.onNavigationChanged(null, false)
        pending.complete(sampleDepartureResponse); runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertTrue(vm.uiState.value.topics.isEmpty())
        assertTrue(vm.uiState.value.contentError)
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.countdownSeconds)
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
    }

    @Test fun noRouteAfterArrival_reopenedTopicsClearTheCurrentDestination() = runTest(dispatcher) {
        val requests = mutableListOf<TripTopicsRequestDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { _, request -> requests += request; sampleDepartureResponse })
        vm.navigationReady(Destination("부산")); runCurrent()
        vm.onSpeedChanged(1f); advanceTimeBy(5_000); runCurrent(); vm.onMorphFinished()
        vm.onSpeedChanged(0f); vm.onParkedChanged(true); vm.onDestinationArrived(); runCurrent()
        vm.onNavigationChanged(null, true)
        assertEquals("부산", vm.uiState.value.destination?.name)
        vm.toggleSkip(); vm.toggleSkip(); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertEquals(2, requests.size)
        assertNull(requests.last().destination)
    }

    @Test fun blankTripIds_areRejectedBeforeStartingWork() {
        for (id in listOf("", " ", "\n\t")) {
            assertThrows(IllegalArgumentException::class.java) { DepartureViewModel(id) }
        }
    }
}
