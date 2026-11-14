package com.forever.dorolog

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveScreenTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithText("최신순").performClick()
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun regionMenuUsesSavedStoryCount_andStoryRowOpensItsTrip() {
        val selected = AtomicReference<String>()
        setArchive(onTripClick = { id, _ -> selected.set(id) })
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("함께한 이야기 51").assertIsDisplayed()
        compose.onNodeWithText("함께한 여행 13").assertIsDisplayed()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("부산 10").performClick()
        compose.onNodeWithText("전체 이야기").assertIsDisplayed()
        compose.onNodeWithTag("archive-map-story-sample-busan-2-story-0").performClick()
        compose.runOnIdle { assertEquals("sample-busan-2", selected.get()) }
        compose.onNodeWithTag("archive-map-story-sample-gangneung-2-story-0").assertDoesNotExist()
        compose.onNodeWithText("전체 이야기").performClick()
        compose.onNodeWithTag("archive-region-trips").performScrollToNode(hasTestTag("archive-map-story-sample-jeju-story-3"))
        compose.onNodeWithTag("archive-map-story-sample-jeju-story-3").assertIsDisplayed()
    }

    @Test fun storyOutsideLatestTrips_withUnknownLocation_stillOpensItsActualTrip() {
        val selected = AtomicReference<String>()
        val destination = AtomicReference<String?>()
        val map = ArchiveMapResponseDto(1, 1, listOf(ArchiveRegionDto("위치 미상", 1, null, null)),
            listOf(ArchiveMapStoryDto("saved-story", "old-trip", "가족의 추억", "위치 미상", null, null,
                "함께 나눈 대화", emptyList(), "2026-11-14T14:30:00+09:00")))
        setArchive(ArchiveRepository({ ArchiveResponseDto(emptyList()) }, { map }), onTripClick = { id, name ->
            selected.set(id); destination.set(name)
        })
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("위치 미상 1").performClick()
        compose.onNodeWithText("가족의 추억").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("old-trip", selected.get()); assertNull(destination.get()) }
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
            val viewModel = remember { ArchiveViewModel(repository) }
            ArchiveRoute(onBack = {}, onTripClick = onTripClick, viewModel = viewModel, browsingAllowed = browsingAllowed)
        }
    }
}
