package com.forever.dorolog

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripSelectionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardsScrollToLastTrip_andResumeUsesItsServerId() {
        var selected: String? = null
        compose.setContent {
            TripSelectionScreen(TripSelectionViewModel.UiState(trips = trips, loading = false),
                onBack = {}, onNewTrip = {}, onResumeTrip = { selected = it }, onRetry = {},
                onRetryStart = {}, onDismissDialog = {}, onStartWithoutDestination = {})
        }
        compose.onNodeWithText("새로 시작").assertIsDisplayed()
        compose.onNodeWithTag("trip-selection-list").performScrollToNode(hasTestTag("resume-trip-11"))
        compose.onNodeWithTag("resume-trip-11").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("trip-11", selected) }
    }

    @Test fun destinationDialogOffersExplicitStartAndDismissActions() {
        var starts = 0
        var dismissed = 0
        compose.setContent {
            TripSelectionScreen(TripSelectionViewModel.UiState(loading = false, showDestinationDialog = true),
                onBack = {}, onNewTrip = {}, onResumeTrip = {}, onRetry = {}, onRetryStart = {},
                onDismissDialog = { dismissed++ }, onStartWithoutDestination = { starts++ })
        }
        compose.onNodeWithText("내비게이션 목적지가 아직 없어요").assertIsDisplayed()
        compose.onNodeWithText("목적지 없이 시작").performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, dismissed) }
    }

    @Test fun drivingRestrictionKeepsBack_andHidesTripActions() {
        compose.setContent {
            TripSelectionScreen(TripSelectionViewModel.UiState(trips = trips, loading = false),
                onBack = {}, onNewTrip = {}, onResumeTrip = {}, onRetry = {}, onRetryStart = {},
                onDismissDialog = {}, onStartWithoutDestination = {}, browsingAllowed = false)
        }
        compose.onNodeWithText("뒤로가기").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("새로 시작").assertDoesNotExist()
        compose.onNodeWithTag("trip-selection-list").assertDoesNotExist()
    }

    @Test fun shortWindowAndLargeText_canScrollToAnExistingTrip() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                TripSelectionScreen(TripSelectionViewModel.UiState(trips = trips, loading = false),
                    onBack = {}, onNewTrip = {}, onResumeTrip = {}, onRetry = {}, onRetryStart = {},
                    onDismissDialog = {}, onStartWithoutDestination = {}, modifier = Modifier.requiredSize(600.dp, 240.dp))
            }
        }
        compose.onNodeWithTag("trip-selection-list").assertHeightIsAtLeast(1.dp)
        compose.onNodeWithTag("trip-selection-list").performScrollToNode(hasTestTag("resume-trip-11"))
        compose.onNodeWithTag("resume-trip-11").assertIsDisplayed().assertIsEnabled()
    }

    private val trips = (0..11).map { index -> OngoingTrip("trip-$index",
        TripPlace("서울", "서울시", 37.56, 126.98), TripPlace("부산 $index", "부산시", 35.16, 129.16),
        Instant.parse("2026-11-13T00:00:00Z"), index) }
}
