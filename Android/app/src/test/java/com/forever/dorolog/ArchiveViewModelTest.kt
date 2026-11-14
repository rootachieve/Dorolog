package com.forever.dorolog

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
class ArchiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun sortingBothTabs_isLocal_withoutReloading() = runTest(dispatcher) {
        var tripRequests = 0
        var mapRequests = 0
        val vm = ArchiveViewModel(ArchiveRepository(
            { tripRequests++; sampleArchiveResponse }, { mapRequests++; sampleArchiveMapResponse }))
        runCurrent()
        assertEquals("sample-busan-2", vm.uiState.value.visibleTrips.first().id)
        vm.selectSort(ArchiveSort.Oldest); runCurrent()
        assertEquals("sample-jeju", vm.uiState.value.visibleTrips.first().id)
        vm.selectTab(ArchiveTab.Map)
        assertEquals("sample-jeju", vm.uiState.value.visibleStories.first().tripId)
        vm.selectSort(ArchiveSort.Latest); runCurrent()
        assertEquals("sample-busan-2", vm.uiState.value.visibleStories.first().tripId)
        assertEquals(1, tripRequests)
        assertEquals(1, mapRequests)
    }

    @Test fun storySorting_usesFullTimestamp_andLeavesUnknownTimeLastInBothDirections() = runTest(dispatcher) {
        val stories = listOf(
            story("older", "trip", "서울", null, null).copy(recordedAt = "2026-11-14T09:00:00+09:00"),
            story("unknown", "trip", "서울", null, null).copy(recordedAt = null),
            story("newer", "trip", "서울", null, null).copy(recordedAt = "2026-11-14T10:00:00+09:00"),
        )
        val vm = ArchiveViewModel(ArchiveRepository({ ArchiveResponseDto(emptyList()) }, {
            ArchiveMapResponseDto(3, 1, listOf(ArchiveRegionDto("서울", 3, null, null)), stories)
        }))
        runCurrent()
        assertEquals(listOf("newer", "older", "unknown"), vm.uiState.value.visibleStories.map { it.id })
        vm.selectSort(ArchiveSort.Oldest)
        assertEquals(listOf("older", "newer", "unknown"), vm.uiState.value.visibleStories.map { it.id })
    }

    @Test fun regionSelection_usesSavedStoryLocations_includingTripsOutsideLatestFifty() = runTest(dispatcher) {
        val trips = ArchiveResponseDto(listOf(trip("recent-trip")))
        val map = ArchiveMapResponseDto(2, 2,
            listOf(ArchiveRegionDto("천안", 1, 36.8, 127.1), ArchiveRegionDto("미상", 1, null, null)),
            listOf(story("one", "old-trip", "천안", 36.8, 127.1), story("two", "recent-trip", "미상", null, null)))
        val vm = ArchiveViewModel(ArchiveRepository({ trips }, { map }))
        runCurrent()
        vm.selectTab(ArchiveTab.Map)
        vm.selectRegion("천안")
        assertEquals("old-trip", vm.uiState.value.visibleStories.single().tripId)
        assertEquals("recent-trip", vm.uiState.value.visibleTrips.single().id)
        vm.selectRegion("unknown")
        assertEquals("천안", vm.uiState.value.selectedRegionId)
        vm.selectRegion("미상")
        assertNull(vm.uiState.value.visibleStories.single().latitude)
        vm.selectRegion(null)
        assertEquals(2, vm.uiState.value.visibleStories.size)
    }

    @Test fun refreshClearsRemovedRegion_butKeepsTabAndSort() = runTest(dispatcher) {
        var map = sampleArchiveMapResponse
        val vm = ArchiveViewModel(ArchiveRepository({ sampleArchiveResponse }, { map }, true))
        runCurrent()
        vm.selectTab(ArchiveTab.Map)
        vm.selectRegion("부산")
        vm.selectSort(ArchiveSort.Oldest)
        map = ArchiveMapResponseDto(0, 0, emptyList(), emptyList())
        vm.retry(); runCurrent()
        assertNull(vm.uiState.value.selectedRegionId)
        assertEquals(ArchiveTab.Map, vm.uiState.value.tab)
        assertEquals(ArchiveSort.Oldest, vm.uiState.value.sort)
        assertTrue(vm.uiState.value.visibleStories.isEmpty())
    }

    @Test fun cancelledNonCooperativeRequest_cannotOverwriteNewResult() = runTest(dispatcher) {
        val old = CompletableDeferred<ArchiveResponseDto>()
        var requests = 0
        val vm = ArchiveViewModel(ArchiveRepository({
            if (requests++ == 0) withContext(NonCancellable) { old.await() }
            else ArchiveResponseDto(listOf(trip("new-trip")))
        }, { ArchiveMapResponseDto(0, 0, emptyList(), emptyList()) }))
        runCurrent()
        vm.retry(); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        old.complete(sampleArchiveResponse); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun cancelledNonCooperativeFailure_cannotReportErrorAfterSuccessfulRetry() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        var requests = 0
        val vm = ArchiveViewModel(ArchiveRepository({
            if (requests++ == 0) withContext(NonCancellable) { old.await(); error("old failed") }
            else ArchiveResponseDto(listOf(trip("new-trip")))
        }, { ArchiveMapResponseDto(0, 0, emptyList(), emptyList()) }))
        runCurrent()
        vm.retry(); runCurrent()
        old.complete(Unit); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun failureShowsError_andRetryRecovers_withoutSubstitutingSampleData() = runTest(dispatcher) {
        var fail = true
        val vm = ArchiveViewModel(ArchiveRepository({
            if (fail) error("network unavailable") else ArchiveResponseDto(listOf(trip("server-trip")))
        }, { ArchiveMapResponseDto(0, 0, emptyList(), emptyList()) }))
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.content.trips.isEmpty())
        fail = false; vm.retry(); runCurrent()
        assertNull(vm.uiState.value.error)
        assertEquals("server-trip", vm.uiState.value.content.trips.single().id)
        assertFalse(vm.uiState.value.content.isSample)
    }

    @Test fun unconfiguredDefaultRepository_showsErrorWithNoPreviewTrips() = runTest(dispatcher) {
        if (BuildConfig.TRIP_API_BASE_URL.isNotBlank()) return@runTest
        val vm = ArchiveViewModel()
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.content.trips.isEmpty())
        assertFalse(vm.uiState.value.content.isSample)
    }

    private fun trip(id: String) = ArchiveTripDto(id, "서울", null, null, "부산", null, null,
        "finished", "2026-11-14T05:30:00+00:00", "2026-11-14T08:10:00+00:00", 1)

    private fun story(id: String, tripId: String, region: String, latitude: Double?, longitude: Double?) =
        ArchiveMapStoryDto(id, tripId, "가족 이야기", region, latitude, longitude,
            "가족과 나눈 이야기", listOf("즐거웠어"), "2026-11-14T14:30:00+09:00")
}
