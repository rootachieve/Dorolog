package com.forever.dorolog

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveScreenTest {
    @get:Rule val compose = createComposeRule()
    private val originalTimeZone = TimeZone.getDefault()

    @Before fun useKoreanTime() { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul")) }
    @After fun restoreTimeZone() { TimeZone.setDefault(originalTimeZone) }

    @Test fun tripsScrollToTheLastCard_andPassTheActualTripId() {
        val selected = AtomicReference<String>()
        setArchive(onTripClick = { id, _ -> selected.set(id) })
        compose.onNodeWithText("미리보기 데이터 · 서버 미연결").assertIsDisplayed()
        compose.onNodeWithTag("archive-trip-grid").performScrollToNode(hasTestTag("archive-trip-sample-jeju"))
        compose.onNodeWithTag("archive-trip-sample-jeju").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("sample-jeju", selected.get()) }
    }

    @Test fun bothTabsSortLocally_withoutReloadingTheRepository() {
        val requests = AtomicInteger()
        setArchive(ArchiveRepository({ requests.incrementAndGet(); sampleArchiveResponse }, { sampleArchiveMapResponse }, true))
        compose.onNodeWithText("최신순").performClick()
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("부산 10").performClick()
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithText("최신순").performClick()
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun initialMapHasNoStoryList_andRegionSelectionOpensDetailsInline() {
        val openedTrips = AtomicInteger()
        setArchive(onTripClick = { _, _ -> openedTrips.incrementAndGet() })
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("함께한 이야기 51").assertIsDisplayed()
        compose.onNodeWithText("함께한 여행 13").assertIsDisplayed()
        compose.onNodeWithTag("archive-region-trips").assertDoesNotExist()
        compose.onNodeWithText("최신순").assertDoesNotExist()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("부산 10").performClick()
        compose.onNodeWithText("지도로").assertIsDisplayed()
        compose.onNodeWithText("최신순").assertIsDisplayed()
        compose.onAllNodesWithText("가족과 함께 나눈 이야기").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("archive-map-story-sample-gangneung-2-story-0").assertDoesNotExist()
        compose.onNodeWithTag("archive-region-trips").performScrollToNode(hasTestTag("archive-map-story-sample-busan-1-story-4"))
        compose.onNodeWithTag("archive-map-story-sample-busan-1-story-4").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, openedTrips.get()) }
        compose.onNodeWithText("지도로").performClick()
        compose.onNodeWithTag("archive-region-trips").assertDoesNotExist()
        compose.onNodeWithText("최신순").assertDoesNotExist()
    }

    @Test fun selectedRegionShowsApiSummaryTimeAndEveryHighlight_withoutOpeningAnotherScreen() {
        val openedTrips = AtomicInteger()
        val map = ArchiveMapResponseDto(1, 1, listOf(ArchiveRegionDto("위치 미상", 1, null, null)),
            listOf(ArchiveMapStoryDto("saved-story", "old-trip", "가족의 추억", "위치 미상", null, null,
                "창문 너머 바다를 보며 어린 시절을 떠올렸어요.",
                listOf("저 바다 기억나?", "우리 어릴 때도 왔었지.", "다음에는 다 같이 오자.", "네 번째 대화도 남겨 둬."),
                "2026-11-14T14:30:00+09:00")))
        setArchive(ArchiveRepository({ ArchiveResponseDto(emptyList()) }, { map }),
            onTripClick = { _, _ -> openedTrips.incrementAndGet() })
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("위치 미상 1").performClick()
        compose.onNodeWithText("가족의 추억").assertIsDisplayed()
        compose.onAllNodesWithText("위치 미상", useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithText("11.14 오후 2:30").assertIsDisplayed()
        compose.onNodeWithText("창문 너머 바다를 보며 어린 시절을 떠올렸어요.").assertIsDisplayed()
        listOf("저 바다 기억나?", "우리 어릴 때도 왔었지.", "다음에는 다 같이 오자.", "네 번째 대화도 남겨 둬.").forEach { quote ->
            compose.onNodeWithTag("archive-region-trips").performScrollToNode(hasText(quote))
            compose.onNodeWithText(quote).assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(0, openedTrips.get()) }
    }

    @Test fun missingStoryMetadataShowsGaps_insteadOfInventedDetails() {
        val map = ArchiveMapResponseDto(1, 1, listOf(ArchiveRegionDto("서울", 1, null, null)),
            listOf(ArchiveMapStoryDto("empty-story", "old-trip", "남겨 둔 이야기", "서울", null, null,
                "", emptyList(), null)))
        setArchive(ArchiveRepository({ ArchiveResponseDto(emptyList()) }, { map }))
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("서울 1").performClick()
        compose.onNodeWithText("시간 정보 없음").assertIsDisplayed()
        compose.onNodeWithText("이야기 내용이 아직 준비되지 않았어요.").assertIsDisplayed()
        compose.onNodeWithTag("archive-region-trips")
            .performScrollToNode(hasText("핵심 대화 내용이 아직 준비되지 않았어요."))
        compose.onNodeWithText("핵심 대화 내용이 아직 준비되지 않았어요.").assertIsDisplayed()
    }

    @Test fun narrowDetailKeepsListTouchable_andSystemBackReturnsToMap() {
        var state by mutableStateOf(ArchiveViewModel.UiState(tab = ArchiveTab.Map,
            content = ArchiveContent(regions = listOf(ArchiveRegion("부산", null, null, 1)),
                stories = listOf(ArchiveStory("story", "trip", "부산에서 나눈 이야기", "부산", null, null,
                    "가족과 바다를 보았어요.", emptyList(), null)), tripCount = 1, storyCount = 1),
            selectedRegionId = "부산", isLoading = false))
        var homeVisits = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(560.dp, 760.dp)) {
                    ArchiveScreen(state, { homeVisits++ }, { _, _ -> }, {}, {},
                        { region -> state = state.copy(selectedRegionId = region) }, {})
                }
            }
        }
        compose.onNodeWithText("지도로").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(state.selectedRegionId); assertEquals(0, homeVisits) }
        compose.onNodeWithTag("archive-region-trips").assertDoesNotExist()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("부산 1").performClick()
        compose.onNodeWithText("지도로").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertNull(state.selectedRegionId); assertEquals(0, homeVisits) }
        compose.onNodeWithText("지도로").assertDoesNotExist()
        compose.onNodeWithTag("archive-region-trips").assertDoesNotExist()
    }

    @Test fun highlightsAnimateOnFirstViewportEntry_andDoNotReplayWhenScrollingBack() {
        val stories = (0..8).map { index ->
            ArchiveStory("story-$index", "trip-$index", "이야기 $index", "부산", null, null,
                "바다를 보며 나눈 이야기 $index", listOf("다시 기억하고 싶은 대화 $index"),
                Instant.parse("2026-11-14T05:30:00Z"))
        }
        var state by mutableStateOf(ArchiveViewModel.UiState(tab = ArchiveTab.Map,
            content = ArchiveContent(regions = listOf(ArchiveRegion("부산", null, null, 9)),
                stories = stories, tripCount = 9, storyCount = 9), isLoading = false))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(1100.dp, 760.dp)) {
                    ArchiveScreen(state, {}, { _, _ -> }, {}, {}, { region -> state = state.copy(selectedRegionId = region) }, {})
                }
            }
        }
        compose.runOnIdle { state = state.copy(selectedRegionId = "부산") }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val firstBefore = quotePixels("archive-map-quote-trip-0-story-0-0")
        compose.mainClock.advanceTimeBy(1_500)
        val firstAfter = quotePixels("archive-map-quote-trip-0-story-0-0")
        assertFalse("The first visible quote should animate into the card", firstBefore.contentEquals(firstAfter))

        compose.onNodeWithTag("archive-region-trips").performScrollToIndex(8)
        compose.mainClock.advanceTimeByFrame()
        val lastBefore = quotePixels("archive-map-quote-trip-8-story-8-0")
        compose.mainClock.advanceTimeBy(1_500)
        val lastAfter = quotePixels("archive-map-quote-trip-8-story-8-0")
        assertFalse("An offscreen quote should animate when it first enters the viewport", lastBefore.contentEquals(lastAfter))

        compose.onNodeWithTag("archive-region-trips").performScrollToIndex(0)
        compose.mainClock.advanceTimeByFrame()
        val revisited = quotePixels("archive-map-quote-trip-0-story-0-0")
        assertTrue("A quote already shown should remain visible when scrolling back", firstAfter.contentEquals(revisited))
        compose.mainClock.advanceTimeBy(1_500)
        assertTrue("Revisiting should not restart the quote animation",
            revisited.contentEquals(quotePixels("archive-map-quote-trip-0-story-0-0")))
    }

    @Test fun quoteBelowLongSummaryWaitsForItsOwnViewportEntry_beforeAnimating() {
        val state = ArchiveViewModel.UiState(tab = ArchiveTab.Map,
            content = ArchiveContent(regions = listOf(ArchiveRegion("부산", null, null, 1)),
                stories = listOf(ArchiveStory("long-story", "trip", "오래 나눈 이야기", "부산", null, null,
                    (1..85).joinToString("\n") { "바다를 보며 나눈 오래된 기억 $it" },
                    listOf("요약 아래에 남겨 둔 마지막 대화"), null)), tripCount = 1, storyCount = 1),
            selectedRegionId = "부산", isLoading = false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(1100.dp, 760.dp)) {
                    ArchiveScreen(state, {}, { _, _ -> }, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("archive-map-story-long-story").assertIsDisplayed()
        compose.onNodeWithTag("archive-map-quote-trip-long-story-0").assertIsNotDisplayed()
        compose.mainClock.advanceTimeBy(1_500)
        // ScrollBy returns immediately so manual frames preserve the quote's initial appearance.
        compose.onNodeWithTag("archive-region-trips")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { scroll ->
                assertTrue(scroll(0f, 100_000f))
            }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("archive-map-quote-trip-long-story-0").assertIsDisplayed()
        val entering = quotePixels("archive-map-quote-trip-long-story-0")
        compose.mainClock.advanceTimeBy(1_500)
        val shown = quotePixels("archive-map-quote-trip-long-story-0")
        assertFalse("Time spent below the viewport must not consume the quote's first appearance",
            entering.contentEquals(shown))
    }

    @Test fun serverFailureShowsRetry_andRecovers() {
        val fail = AtomicBoolean(true)
        setArchive(ArchiveRepository({ if (fail.get()) error("offline") else sampleArchiveResponse }, { sampleArchiveMapResponse }))
        compose.onNodeWithText("여행을 불러오지 못했어요. 다시 시도해 주세요.").assertIsDisplayed()
        fail.set(false)
        compose.onNodeWithText("다시 시도").performClick()
        compose.onNodeWithTag("archive-trip-grid").assertIsDisplayed()
        compose.onNodeWithText("미리보기 데이터 · 서버 미연결").assertDoesNotExist()
    }

    @Test fun drivingRestrictionHidesBrowsing_andKeepsHomeAction() {
        setArchive(browsingAllowed = false)
        compose.onNodeWithText("안전한 곳에 정차한 후\n이야기 지도를 확인해 주세요").assertIsDisplayed()
        compose.onNodeWithTag("archive-trip-grid").assertDoesNotExist()
        compose.onNodeWithText("지도").assertDoesNotExist()
        compose.onNodeWithText("메인 화면으로").assertIsDisplayed()
    }

    private fun setArchive(
        repository: ArchiveRepository = ArchiveRepository({ sampleArchiveResponse }, { sampleArchiveMapResponse }, true),
        onTripClick: (String, String?) -> Unit = { _, _ -> },
        browsingAllowed: Boolean = true,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                val viewModel = remember { ArchiveViewModel(repository) }
                ArchiveRoute(onBack = {}, onTripClick = onTripClick, viewModel = viewModel, browsingAllowed = browsingAllowed)
            }
        }
    }

    private fun quotePixels(tag: String): IntArray {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return IntArray(pixels.width * pixels.height) { index -> pixels[index % pixels.width, index / pixels.width].toArgb() }
    }
}
