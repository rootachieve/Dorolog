package com.forever.dorolog

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TripSummaryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val stories = (1..8).map {
        SavedTripStory("story-$it", "story-$it", "이야기 $it", "서울",
            Instant.parse("2026-11-13T01:00:00Z"), "함께 나눈 이야기 $it", listOf("기억나?", "응!"))
    }

    @Test fun restrictedSummaryHidesStoriesAndDoesNotLoadUntilBrowsingIsAllowed() {
        var browsingAllowed by mutableStateOf(false)
        var loads = 0
        val repository = TripSummaryRepository {
            loads++
            TripSummaryResponseDto("trip", listOf(TripSummaryStoryDto("story", "숨겨야 하는 이야기", "서울")), listOf("story"))
        }
        compose.setContent {
            TripSummaryScreen("trip", {}, animationsEnabled = false, browsingAllowed = browsingAllowed,
                repository = repository)
        }
        compose.onNodeWithText("안전한 곳에 정차한 후 여행 결산을 확인해 주세요").assertIsDisplayed()
        compose.onNodeWithTag("summary-rail").assertDoesNotExist()
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
        compose.onNodeWithText("숨겨야 하는 이야기").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, loads); browsingAllowed = true }
        compose.waitUntil { loads == 1 }
        compose.onNodeWithContentDescription("1번 이야기, 서울, 숨겨야 하는 이야기").assertIsDisplayed()
        compose.onNodeWithContentDescription("1번 이야기, 서울, 숨겨야 하는 이야기").performClick()
        compose.onNodeWithTag("summary-detail").assertIsDisplayed()
        compose.runOnIdle { browsingAllowed = false }
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
        compose.onNodeWithTag("summary-rail").assertDoesNotExist()
        compose.runOnIdle { browsingAllowed = true }
        compose.waitUntil { loads == 2 }
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
    }

    @Test fun restrictedSummaryKeepsHomeAndArchiveBackTouchable() {
        var home = false
        var archive = false
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            TripSummaryScreen("trip", { home = true }, browsingAllowed = false, onBack = { archive = true })
        }
        compose.onNodeWithText("‹ 뒤로").performClick()
        compose.runOnIdle { assertTrue(archive); assertFalse(home); archive = false }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(archive); assertFalse(home) }
        compose.onNodeWithText("확인").performClick()
        compose.runOnIdle { assertTrue(home) }
    }

    @Test fun archiveBackIsTouchable_andConfirmationStillReturnsHome() {
        var wentBack = false
        var confirmed = false
        compose.setContent {
            TripSummaryContent(TripSummaryViewModel.UiState("trip", stories.take(1), loading = false),
                {}, {}, {}, { confirmed = true }, animationsEnabled = false, onBack = { wentBack = true })
        }
        compose.onNodeWithText("‹ 뒤로").performClick()
        compose.runOnIdle { assertTrue(wentBack); assertFalse(confirmed) }
        compose.onNodeWithText("확인").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun systemBackUsesArchiveCallback_whenProvided() {
        var wentBack = false
        var confirmed = false
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            TripSummaryScreen("trip", { confirmed = true }, animationsEnabled = false,
                onBack = { wentBack = true }, repository = TripSummaryRepository {
                    TripSummaryResponseDto("trip", emptyList(), emptyList())
                })
        }
        compose.onNodeWithText("‹ 뒤로").assertIsDisplayed()
        compose.onNodeWithTag("settlement-screen").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.runOnUiThread { view.isShown && view.hasWindowFocus() } }
        pressBack()
        compose.waitUntil(5_000) { compose.runOnUiThread { wentBack } }
        compose.runOnIdle { assertFalse(confirmed) }
    }

    @Test fun reenteringSummaryReloads_andNewTripNeverReusesSelectedStory() {
        var shown by mutableStateOf(true)
        var tripId by mutableStateOf("trip-1")
        var loads = 0
        val repository = TripSummaryRepository { id ->
            loads++
            TripSummaryResponseDto(id, listOf(TripSummaryStoryDto("story", "여행 $id", "서울")), listOf("story"))
        }
        compose.setContent {
            if (shown) TripSummaryScreen(tripId, {}, animationsEnabled = false, repository = repository)
        }
        compose.waitUntil { loads == 1 }
        compose.onNodeWithContentDescription("1번 이야기, 서울, 여행 trip-1").performClick()
        compose.onNodeWithTag("summary-detail").assertIsDisplayed()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitUntil { loads == 2 }
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
        compose.onNodeWithContentDescription("1번 이야기, 서울, 여행 trip-1").performClick()
        compose.runOnIdle { tripId = "trip-2" }
        compose.waitUntil { loads == 3 }
        compose.onNodeWithContentDescription("1번 이야기, 서울, 여행 trip-2").assertIsDisplayed()
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
    }

    @Test fun missingMetadataShowsGaps_withoutInventingDetails() {
        val story = stories.first().copy(occurredAt = null, content = null, conversationExcerpts = emptyList())
        compose.setContent {
            TripSummaryContent(TripSummaryViewModel.UiState("trip", listOf(story), loading = false,
                selectedStoryKey = story.key), {}, {}, {}, {}, animationsEnabled = false)
        }
        compose.onNodeWithText("시간 정보 없음 · 서울").assertIsDisplayed()
        compose.onNodeWithText("이야기 내용이 아직 준비되지 않았어요.").assertIsDisplayed()
        compose.onNodeWithText("핵심 대화 내용이 아직 준비되지 않았어요.").assertIsDisplayed()
    }

    @Test fun detailsStartHidden_toggle_andConfirmationReturnsHome() {
        var state by mutableStateOf(TripSummaryViewModel.UiState(tripId = "trip", stories = stories.take(4), loading = false))
        var confirmed = false
        compose.setContent {
            TripSummaryContent(state, { key -> state = state.copy(selectedStoryKey = key.takeUnless { it == state.selectedStoryKey }) },
                { key -> if (state.selectedStoryKey == key) state = state.copy(selectedStoryKey = null) },
                {}, { confirmed = true }, destinationName = "서울", animationsEnabled = false)
        }
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
        compose.onNodeWithContentDescription("1번 이야기, 서울, 이야기 1").performClick()
        compose.onNodeWithTag("summary-detail").assertIsDisplayed()
        compose.onNodeWithText("함께 나눈 이야기 1").assertIsDisplayed()
        compose.onNodeWithText("“기억나?”").assertIsDisplayed()
        compose.onNodeWithContentDescription("1번 이야기, 서울, 이야기 1").performClick()
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
        compose.onNodeWithText("확인").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun scrollingSelectedStoryPastPanel_dismissesDetails() {
        var state by mutableStateOf(TripSummaryViewModel.UiState(tripId = "trip", stories = stories, loading = false))
        compose.setContent {
            TripSummaryContent(state, { state = state.copy(selectedStoryKey = it) },
                { key -> if (state.selectedStoryKey == key) state = state.copy(selectedStoryKey = null) },
                {}, {}, animationsEnabled = false)
        }
        compose.onNodeWithContentDescription("1번 이야기, 서울, 이야기 1").performClick()
        compose.onNodeWithTag("summary-detail").assertIsDisplayed()
        compose.onNodeWithTag("summary-rail").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
    }

    @Test fun refreshedOrder_movingSelectionOutsidePanel_clearsSelection() {
        var state by mutableStateOf(TripSummaryViewModel.UiState(tripId = "trip", stories = stories,
            loading = false, selectedStoryKey = stories.first().key))
        compose.setContent {
            TripSummaryContent(state, { state = state.copy(selectedStoryKey = it) },
                { key -> if (state.selectedStoryKey == key) state = state.copy(selectedStoryKey = null) },
                {}, {}, animationsEnabled = false)
        }
        compose.onNodeWithTag("summary-detail").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(stories = stories.drop(1) + stories.first()) }
        compose.runOnIdle { assertNull(state.selectedStoryKey) }
        compose.onNodeWithTag("summary-detail").assertDoesNotExist()
    }
}
