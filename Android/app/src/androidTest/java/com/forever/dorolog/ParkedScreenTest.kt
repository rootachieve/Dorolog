package com.forever.dorolog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParkedScreenTest {
    @get:Rule val compose = createComposeRule()
    private val stories = (1..10).map { ParkedStory("$it", "여행 이야기 $it", "서울 종로구") }

    @Test fun selectionAndThreeActions_dispatchCallbacks_andShowParkingCopy() {
        var selected: String? = null
        var saves = 0
        var skips = 0
        var finishes = 0
        compose.setContent {
            ParkedScreen(DepartureViewModel.UiState(stories = stories.take(2), selectedStoryIds = setOf("1"),
                canSelectStories = true), { selected = it }, { saves++ }, { skips++ }, { finishes++ }, {})
        }
        compose.onNodeWithText("주차 중").assertIsDisplayed()
        compose.onNodeWithText("정차 중").assertDoesNotExist()
        compose.onNodeWithTag("parked-story-1").assertIsOn().performClick()
        compose.onNodeWithText("이야기 저장").performClick()
        compose.onNodeWithText("다음 목적지까지", substring = true).performClick()
        compose.onNodeWithText("여행 종료하기").performClick()
        compose.runOnIdle {
            assertEquals("1", selected)
            assertEquals(1, saves)
            assertEquals(1, skips)
            assertEquals(1, finishes)
        }
    }

    @Test fun scrollbarCanDrag_andLastStoryScrollsAboveGradient() {
        compose.setContent {
            ParkedScreen(DepartureViewModel.UiState(stories = stories, canSelectStories = true), {}, {}, {}, {}, {})
        }
        compose.onNodeWithTag("parked-scrollbar").performTouchInput { swipeDown() }
        compose.onNodeWithTag("parked-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        val listBounds = compose.onNodeWithTag("parked-stories").getUnclippedBoundsInRoot()
        val lastBounds = compose.onNodeWithTag("parked-story-10").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(lastBounds.top >= listBounds.top)
        assertTrue(lastBounds.bottom <= listBounds.bottom - 64.dp)
    }

    @Test fun unavailableStories_showRetry_andDoNotPretendToSave() {
        var retries = 0
        compose.setContent {
            ParkedScreen(DepartureViewModel.UiState(storiesError = "서버에 연결할 수 없어요", canSelectStories = true),
                {}, {}, {}, {}, { retries++ })
        }
        compose.onNodeWithText("서버에 연결할 수 없어요").assertIsDisplayed()
        compose.onNodeWithText("이야기 저장").assertIsNotEnabled()
        compose.onNodeWithText("다시 불러오기").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun compactWindow_keepsAllActionsTouchable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp).testTag("parked-viewport")) {
                    ParkedScreen(DepartureViewModel.UiState(stories = stories, canSelectStories = true), {}, {}, {}, {}, {})
                }
            }
        }
        val viewport = compose.onNodeWithTag("parked-viewport").getUnclippedBoundsInRoot()
        listOf("이야기 저장", "다음 목적지까지 기록 멈추기", "여행 종료하기").forEach { label ->
            val bounds = compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }
}
