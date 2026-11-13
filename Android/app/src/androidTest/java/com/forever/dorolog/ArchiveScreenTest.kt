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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tripsScrollToTheLastCard_andPassTheActualTripId() {
        val selected = AtomicReference<ArchiveTrip>()
        setArchive(onTripClick = selected::set)
        compose.onNodeWithText("미리보기 데이터 · 서버 미연결").assertIsDisplayed()
        compose.onNodeWithText("가보고 싶은 곳").assertDoesNotExist()
        compose.onNodeWithTag("archive-trip-grid").performScrollToNode(hasTestTag("archive-trip-sample-jeju"))
        compose.onNodeWithTag("archive-trip-sample-jeju").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("sample-jeju", selected.get().id) }
    }

    @Test fun bothTabsSendTheChosenSortToTheRepository() {
        val requests = CopyOnWriteArrayList<ArchiveSort>()
        setArchive(ArchiveRepository({ sort -> requests.add(sort); sampleArchiveResponse }, true))
        compose.onNodeWithText("최신순").performClick()
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("오래된순").performClick()
        compose.onNodeWithText("최신순").performClick()
        compose.runOnIdle { assertEquals(listOf(ArchiveSort.Latest, ArchiveSort.Oldest, ArchiveSort.Latest), requests.toList()) }
    }

    @Test fun regionMenuFiltersTrips_andTheListPassesTheSameTripId() {
        val selected = AtomicReference<ArchiveTrip>()
        setArchive(onTripClick = selected::set)
        compose.onNodeWithText("지도").performClick()
        compose.onNodeWithText("함께한 이야기 51").assertIsDisplayed()
        compose.onNodeWithText("함께한 여행 13").assertIsDisplayed()
        compose.onNodeWithText("지역 선택").performClick()
        compose.onNodeWithText("부산 2").performClick()
        compose.onNodeWithText("전체 여행").assertIsDisplayed()
        compose.onNodeWithTag("archive-region-trip-sample-busan-2").performClick()
        compose.runOnIdle { assertEquals("sample-busan-2", selected.get().id) }
        compose.onNodeWithTag("archive-region-trip-sample-gangneung-2").assertDoesNotExist()
        compose.onNodeWithText("전체 여행").performClick()
        compose.onNodeWithTag("archive-region-trips").performScrollToNode(hasTestTag("archive-region-trip-sample-jeju"))
        compose.onNodeWithTag("archive-region-trip-sample-jeju").assertIsDisplayed()
    }

    @Test fun serverFailureShowsRetry_andRecovers() {
        val fail = AtomicBoolean(true)
        setArchive(ArchiveRepository({ if (fail.get()) error("offline") else sampleArchiveResponse }, false))
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
        repository: ArchiveRepository = ArchiveRepository({ sampleArchiveResponse }, true),
        onTripClick: (ArchiveTrip) -> Unit = {},
        browsingAllowed: Boolean = true,
    ) {
        compose.setContent {
            val viewModel = remember { ArchiveViewModel(repository) }
            ArchiveRoute(onBack = {}, onTripClick = onTripClick, viewModel = viewModel, browsingAllowed = browsingAllowed)
        }
    }
}
