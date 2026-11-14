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
    @Test fun selectedTripAndInitialDestination_areUsedForFirstQuestionRequest() = runTest(dispatcher) {
        val requests = mutableListOf<RecommendationRequestDto>()
        val vm = DepartureViewModel("chosen/server id", DepartureRepository { requests += it; sampleDepartureResponse },
            initialDestination = Destination("부산", 35.16, 129.16, "부산시"))
        runCurrent()
        assertEquals("chosen/server id", requests.single().tripId)
        assertEquals("부산", requests.single().destinationName)
        assertEquals("부산", vm.uiState.value.destination?.name)
    }
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun apiResponse_becomesQuestions_andSelectionStaysInBounds() = runTest(dispatcher) {
        val response = Gson().fromJson(
            """{"topics":[{"id":"one","question":"첫 질문"},{"id":"two","question":"다음 질문"}]}""",
            DepartureResponseDto::class.java,
        )
        val vm = DepartureViewModel("trip", DepartureRepository { response })
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
        val vm = DepartureViewModel("trip", DepartureRepository {
            if (fail) Gson().fromJson("{}", DepartureResponseDto::class.java) else sampleDepartureResponse
        })
        runCurrent()
        assertTrue(vm.uiState.value.contentError)
        fail = false; vm.retry(); runCurrent()
        assertFalse(vm.uiState.value.contentError)
        assertEquals(3, vm.uiState.value.topics.size)
    }

    @Test fun browsingHidesTip_onlyAfterQuestionChanges_andSurvivesReloadInSameViewModel() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip")
        runCurrent()
        assertFalse(vm.uiState.value.hasBrowsedTopics)
        vm.selectTopic("together"); vm.moveTopic(-1); vm.selectTopic("unknown")
        assertFalse(vm.uiState.value.hasBrowsedTopics)
        vm.moveTopic(1)
        assertTrue(vm.uiState.value.hasBrowsedTopics)
        vm.moveTopic(-1); vm.retry(); runCurrent()
        assertTrue(vm.uiState.value.hasBrowsedTopics)
    }

    @Test fun destinationAndHistory_areForwarded_withoutRepeatingSameDestination() = runTest(dispatcher) {
        val requests = mutableListOf<RecommendationRequestDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { requests.add(it); sampleDepartureResponse },
            previousConversationIds = listOf("conversation-1", "conversation-2"))
        runCurrent(); vm.setActive(true)
        vm.onNavigationChanged(Destination("부산 할머니댁"), true); runCurrent()
        assertEquals(2, requests.size)
        assertEquals("부산 할머니댁", requests.last().destinationName)
        assertEquals(listOf("conversation-1", "conversation-2"), requests.last().previousConversationIds)
        vm.onNavigationChanged(Destination("부산 할머니댁"), true); runCurrent()
        assertEquals(2, requests.size)
    }

    @Test fun changedDestination_cancelsOldRequest_andUsesLatestQuestions() = runTest(dispatcher) {
        val old = CompletableDeferred<DepartureResponseDto>()
        val vm = DepartureViewModel("trip", DepartureRepository {
            if (it.destinationName == "부산") old.await()
            else DepartureResponseDto(listOf(RecommendedTopicDto("latest", it.destinationName ?: "기본 질문")))
        })
        runCurrent(); vm.setActive(true)
        vm.onNavigationChanged(Destination("부산"), true); runCurrent()
        vm.onNavigationChanged(Destination("서울"), true); runCurrent()
        old.complete(DepartureResponseDto(listOf(RecommendedTopicDto("old", "이전 질문")))); runCurrent()
        assertEquals(listOf("서울"), vm.uiState.value.topics.map { it.question })
    }

    @Test fun movement_countsFiveSeconds_withoutRestarting_thenMorphsOnlyOnce() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip")
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
        val vm = DepartureViewModel("trip")
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
        val vm = DepartureViewModel("trip")
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(2_000); runCurrent(); vm.setActive(false)
        advanceTimeBy(10_000); runCurrent(); vm.setActive(true)
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertNull(vm.uiState.value.countdownSeconds)
        vm.onSpeedChanged(1f); runCurrent()
        assertEquals(5, vm.uiState.value.countdownSeconds)
        vm.setActive(false)
    }

    @Test fun questionsMustBeReady_beforeAutomaticCountdownStarts() = runTest(dispatcher) {
        val response = CompletableDeferred<DepartureResponseDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { response.await() })
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertNull(vm.uiState.value.countdownSeconds)
        response.complete(sampleDepartureResponse); runCurrent()
        assertEquals(5, vm.uiState.value.countdownSeconds)
        vm.setActive(false)
    }

    @Test fun emptyRecommendations_doNotStartConversation() = runTest(dispatcher) {
        val vm = DepartureViewModel("trip", DepartureRepository { DepartureResponseDto(emptyList()) })
        runCurrent(); vm.setActive(true); vm.onSpeedChanged(1f)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(DepartureViewModel.Phase.Ready, vm.uiState.value.phase)
        assertNull(vm.uiState.value.countdownSeconds)
    }

    @Test fun temporaryNavigationError_thenNoRoute_refreshesGenericRecommendations() = runTest(dispatcher) {
        val requests = mutableListOf<RecommendationRequestDto>()
        val vm = DepartureViewModel("trip", DepartureRepository { requests.add(it); sampleDepartureResponse })
        runCurrent(); vm.setActive(true)
        vm.onNavigationChanged(Destination("부산"), true); runCurrent()
        vm.onNavigationChanged(null, false); runCurrent()
        assertEquals("부산", vm.uiState.value.destination?.name)
        vm.onNavigationChanged(null, true); runCurrent()
        assertNull(requests.last().destinationName)
        assertEquals(3, requests.size)
    }

    @Test fun blankTripIds_areRejectedBeforeStartingWork() {
        for (id in listOf("", " ", "\n\t")) {
            assertThrows(IllegalArgumentException::class.java) { DepartureViewModel(id) }
        }
    }
}
