package com.forever.dorolog

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
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
class TripSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun failedLoad_canRetryWithoutInventingStories() = runTest(dispatcher) {
        var fail = true
        val vm = TripSummaryViewModel("trip-1", TripSummaryRepository {
            if (fail) throw IOException("Network unavailable") else response("one", "two")
        })
        assertTrue(vm.uiState.value.loading)
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.stories.isEmpty())

        fail = false
        vm.retry()
        runCurrent()
        assertEquals("trip-1", vm.uiState.value.tripId)
        assertEquals(listOf("one", "two"), vm.uiState.value.stories.map { it.key })
        assertNull(vm.uiState.value.error)
    }

    @Test fun selectionTogglesKnownStories_andStaleDialogClearDoesNotCloseNextStory() = runTest(dispatcher) {
        val vm = TripSummaryViewModel("trip-1", TripSummaryRepository { response("one", "two") })
        runCurrent()
        vm.selectStory("missing")
        assertNull(vm.uiState.value.selectedStoryKey)
        vm.selectStory("one")
        assertEquals("one", vm.uiState.value.selectedStoryKey)
        vm.selectStory("two")
        vm.clearSelection("one")
        assertEquals("two", vm.uiState.value.selectedStoryKey)
        vm.clearSelection("two")
        assertNull(vm.uiState.value.selectedStoryKey)
        vm.selectStory("one")
        vm.selectStory("one")
        assertNull(vm.uiState.value.selectedStoryKey)
    }

    @Test fun refresh_keepsExistingStoriesOnFailure_thenClearsRemovedSelection() = runTest(dispatcher) {
        var next = response("one", "two")
        var fail = false
        val vm = TripSummaryViewModel("trip-1", TripSummaryRepository {
            if (fail) throw IOException("Disconnected") else next
        })
        runCurrent()
        vm.selectStory("one")
        vm.retry(); runCurrent()
        assertEquals("one", vm.uiState.value.selectedStoryKey)
        fail = true
        vm.retry(); runCurrent()
        assertEquals(2, vm.uiState.value.stories.size)
        assertEquals("one", vm.uiState.value.selectedStoryKey)
        assertNotNull(vm.uiState.value.error)
        fail = false
        next = response("two")
        vm.retry(); runCurrent()
        assertEquals(listOf("two"), vm.uiState.value.stories.map { it.key })
        assertNull(vm.uiState.value.selectedStoryKey)
    }

    @Test fun cancelledOldLoad_cannotOverwriteLatestRetry() = runTest(dispatcher) {
        val old = CompletableDeferred<TripSummaryResponseDto>()
        var first = true
        val vm = TripSummaryViewModel("trip-1", TripSummaryRepository {
            if (first) {
                first = false
                withContext(NonCancellable) { old.await() }
            } else response("latest")
        })
        runCurrent()
        vm.retry(); runCurrent()
        old.complete(response("stale")); runCurrent()
        assertEquals(listOf("latest"), vm.uiState.value.stories.map { it.key })
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.error)
    }

    @Test fun cancelledOldFailure_cannotReplaceLatestSuccessWithError() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        var first = true
        val vm = TripSummaryViewModel("trip-1", TripSummaryRepository {
            if (first) {
                first = false
                withContext(NonCancellable) {
                    old.await()
                    throw IOException("Old request failed")
                }
            } else response("latest")
        })
        runCurrent()
        vm.retry(); runCurrent()
        old.complete(Unit); runCurrent()
        assertEquals(listOf("latest"), vm.uiState.value.stories.map { it.key })
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
    }

    private fun response(vararg ids: String) = TripSummaryResponseDto(
        tripId = "trip-1", stories = ids.map { TripSummaryStoryDto(it, "이야기 $it", "서울") },
        savedStoryIds = ids.toList(),
    )
}
