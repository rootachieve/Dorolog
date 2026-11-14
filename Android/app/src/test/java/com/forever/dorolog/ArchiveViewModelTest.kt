package com.forever.dorolog

import com.google.gson.Gson
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

    @Test fun finalDestination_isLastStop_andRegionsCountTripsRatherThanStories() {
        val busan = ArchiveDestinationDto("부산 해운대", 35.16, 129.16, "26000", "부산")
        val first = trip("trip-1").copy(destinations = listOf(
            ArchiveDestinationDto("전주", 35.81, 127.15, "45110", "전주시"), busan,
        ), storyCount = 5)
        val second = trip("trip-2").copy(destinations = listOf(busan.copy(latitude = 35.14)), storyCount = 0)
        val content = ArchiveResponseDto(listOf(first, second)).toModel()

        assertEquals("부산 해운대", content.trips.first().destinationName)
        assertEquals(129.16, content.trips.first().destinationLongitude, 0.0)
        assertEquals(2, content.tripCount)
        assertEquals(5, content.storyCount)
        assertEquals(1, content.regions.size)
        assertEquals(2, content.regions.single().tripCount)
        assertEquals(35.15, content.regions.single().latitude, 0.00001)
        assertEquals(content.regions, ArchiveResponseDto(listOf(second, first)).toModel().regions)
    }

    @Test fun emptyResponse_hasZeroTotals_andNoRegions() {
        val content = ArchiveResponseDto(emptyList()).toModel()
        assertTrue(content.trips.isEmpty())
        assertTrue(content.regions.isEmpty())
        assertEquals(0, content.tripCount)
        assertEquals(0, content.storyCount)
    }

    @Test fun invalidData_isRejectedBeforeItReachesTheMapOrTripRoute() {
        val valid = trip("trip-1")
        val destination = requireNotNull(valid.destinations?.single())
        val invalidTrips = listOf(
            valid.copy(id = " "),
            valid.copy(departureName = " "),
            valid.copy(destinations = emptyList()),
            valid.copy(destinations = listOf(destination, null)),
            valid.copy(destinations = listOf(destination.copy(latitude = 90.1))),
            valid.copy(destinations = listOf(destination.copy(longitude = -180.1))),
            valid.copy(destinations = listOf(destination.copy(latitude = Double.NaN))),
            valid.copy(destinations = listOf(destination.copy(longitude = Double.POSITIVE_INFINITY))),
            valid.copy(destinations = listOf(destination.copy(regionId = ""))),
            valid.copy(destinations = listOf(destination.copy(regionName = ""))),
            valid.copy(destinations = listOf(destination.copy(name = ""))),
            valid.copy(date = "2026-02-30"),
            valid.copy(storyCount = -1),
            valid.copy(storyCount = null),
        )
        invalidTrips.forEach { item ->
            assertThrows("Invalid DTO must fail: $item", Exception::class.java) {
                ArchiveResponseDto(listOf(item)).toModel()
            }
        }
        assertThrows(Exception::class.java) { ArchiveResponseDto(listOf(null)).toModel() }
        assertThrows(Exception::class.java) { ArchiveResponseDto(listOf(valid, valid)).toModel() }
        assertThrows(Exception::class.java) {
            Gson().fromJson("{}", ArchiveResponseDto::class.java).toModel()
        }
    }

    @Test fun contradictoryRegionNames_andOverflowingStoryTotals_areRejected() {
        val valid = trip("trip-1")
        val destination = requireNotNull(valid.destinations?.single())
        assertThrows(Exception::class.java) {
            ArchiveResponseDto(listOf(valid, trip("trip-2").copy(
                destinations = listOf(destination.copy(regionName = "다른 지역")),
            ))).toModel()
        }
        assertThrows(Exception::class.java) {
            ArchiveResponseDto(listOf(valid.copy(storyCount = Int.MAX_VALUE), trip("trip-2"))).toModel()
        }
    }

    @Test fun sortIsSentToTheRepository_fromEitherTab_andDoesNotReloadForSameSort() = runTest(dispatcher) {
        val requests = mutableListOf<ArchiveSort>()
        val vm = ArchiveViewModel(ArchiveRepository({ requests.add(it); sampleArchiveResponse }, false))
        runCurrent()
        vm.selectSort(ArchiveSort.Latest)
        vm.selectSort(ArchiveSort.Oldest); runCurrent()
        vm.selectTab(ArchiveTab.Map)
        vm.selectSort(ArchiveSort.Latest); runCurrent()
        assertEquals(listOf(ArchiveSort.Latest, ArchiveSort.Oldest, ArchiveSort.Latest), requests)
        assertEquals("latest", ArchiveSort.Latest.queryValue)
        assertEquals("oldest", ArchiveSort.Oldest.queryValue)
    }

    @Test fun regionSelection_filtersOnlyMapTab_andCanReturnToAllTrips() = runTest(dispatcher) {
        val vm = ArchiveViewModel(sampleRepository())
        runCurrent()
        vm.selectTab(ArchiveTab.Map)
        val region = vm.uiState.value.content.regions.first { it.tripCount == 2 }
        vm.selectRegion(region.id)
        assertEquals(2, vm.uiState.value.visibleTrips.size)
        assertTrue(vm.uiState.value.visibleTrips.all { it.regionId == region.id })
        vm.selectRegion("unknown-region")
        assertEquals(region.id, vm.uiState.value.selectedRegionId)
        vm.selectTab(ArchiveTab.Trips)
        assertEquals(13, vm.uiState.value.visibleTrips.size)
        vm.selectTab(ArchiveTab.Map); vm.selectRegion(null)
        assertEquals(13, vm.uiState.value.visibleTrips.size)
        assertNull(vm.uiState.value.selectedRegionId)
    }

    @Test fun tabSortAndRegion_stayInCurrentViewModel_onlyAndMissingRegionIsClearedOnRefresh() = runTest(dispatcher) {
        var response = sampleArchiveResponse
        val vm = ArchiveViewModel(ArchiveRepository({ response }, true))
        runCurrent()
        vm.selectTab(ArchiveTab.Map)
        vm.selectRegion(vm.uiState.value.content.regions.first().id)
        vm.selectSort(ArchiveSort.Oldest); runCurrent()
        assertEquals(ArchiveTab.Map, vm.uiState.value.tab)
        assertEquals(ArchiveSort.Oldest, vm.uiState.value.sort)
        assertNotNull(vm.uiState.value.selectedRegionId)
        val fresh = ArchiveViewModel(sampleRepository())
        runCurrent()
        assertEquals(ArchiveTab.Trips, fresh.uiState.value.tab)
        assertEquals(ArchiveSort.Latest, fresh.uiState.value.sort)
        assertNull(fresh.uiState.value.selectedRegionId)
        response = ArchiveResponseDto(emptyList())
        vm.retry(); runCurrent()
        assertNull(vm.uiState.value.selectedRegionId)
        assertEquals(ArchiveTab.Map, vm.uiState.value.tab)
        assertEquals(ArchiveSort.Oldest, vm.uiState.value.sort)
    }

    @Test fun tripIds_areOpaqueNonblankStrings_andAreNotRewritten() {
        val id = "여행: 2026/가족 + " + "a".repeat(160)
        val content = ArchiveResponseDto(listOf(trip(id))).toModel()
        assertEquals(id, content.trips.single().id)
    }

    @Test fun remoteUrl_requiresHttpsHostTrailingSlash_andRejectsCredentialsQueryOrFragment() {
        listOf("http://example.com/", "https:///", "https://user:pass@example.com/", "https://example.com/api",
            "https://example.com/?sort=latest", "https://example.com/#archive").forEach { url ->
            assertThrows("Invalid base URL must fail: $url", Exception::class.java) { ArchiveRepository.remote(url) }
        }
        assertNotNull(ArchiveRepository.remote("https://example.com/api/"))
    }

    @Test fun oldNonCooperativeRequest_cannotOverwriteNewSortOrReportAnError() = runTest(dispatcher) {
        val old = CompletableDeferred<ArchiveResponseDto>()
        val newer = ArchiveResponseDto(listOf(trip("new-trip")))
        val vm = ArchiveViewModel(ArchiveRepository({ sort ->
            if (sort == ArchiveSort.Latest) withContext(NonCancellable) { old.await() } else newer
        }, false))
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        vm.selectSort(ArchiveSort.Oldest); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        old.complete(sampleArchiveResponse); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        assertEquals(ArchiveSort.Oldest, vm.uiState.value.sort)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun oldNonCooperativeRequest_throwsAfterCancellation_cannotOverwriteCurrentResult() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        val newer = ArchiveResponseDto(listOf(trip("new-trip")))
        val vm = ArchiveViewModel(ArchiveRepository({ sort ->
            if (sort == ArchiveSort.Latest) withContext(NonCancellable) {
                old.await()
                error("old request failed after cancellation")
            } else newer
        }, false))
        runCurrent()
        vm.selectSort(ArchiveSort.Oldest); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        assertNull(vm.uiState.value.error)
        old.complete(Unit); runCurrent()
        assertEquals("new-trip", vm.uiState.value.content.trips.single().id)
        assertEquals(ArchiveSort.Oldest, vm.uiState.value.sort)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun failureShowsError_andRetryRecovers_withoutSubstitutingSampleData() = runTest(dispatcher) {
        var fail = true
        val vm = ArchiveViewModel(ArchiveRepository({
            if (fail) error("network unavailable") else ArchiveResponseDto(listOf(trip("server-trip")))
        }, false))
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.content.trips.isEmpty())
        fail = false; vm.retry(); runCurrent()
        assertNull(vm.uiState.value.error)
        assertEquals("server-trip", vm.uiState.value.content.trips.single().id)
        assertFalse(vm.uiState.value.content.isSample)
    }

    @Test fun sampleFlagIsExplicit_andSampleTotalsMatchTheDesign() = runTest(dispatcher) {
        val repository = ArchiveRepository({ sampleArchiveResponse }, true)
        val content = repository.load(ArchiveSort.Latest)
        assertTrue(content.isSample)
        assertEquals(13, content.tripCount)
        assertEquals(51, content.storyCount)
        assertEquals(content.tripCount, content.regions.sumOf { it.tripCount })
    }

    private fun sampleRepository() = ArchiveRepository({ sampleArchiveResponse }, true)

    private fun trip(id: String) = ArchiveTripDto(
        id = id,
        title = "가족 여행",
        departureName = "광주 용봉동",
        destinations = listOf(ArchiveDestinationDto("부산 해운대", 35.16, 129.16, "26000", "부산")),
        date = "2026-11-11",
        storyCount = 1,
    )
}
