package com.forever.dorolog

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepartureScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recommendationsSwipe_andArrowsChangeSelection_withoutMapOrStartButton() {
        lateinit var vm: DepartureViewModel
        compose.runOnUiThread { vm = DepartureViewModel() }
        compose.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            DepartureScreen(state, {}, vm::selectTopic, { vm.moveTopic(-1) }, { vm.moveTopic(1) }, vm::retry)
        }
        compose.onNodeWithText("우리 가족 이야기 지도").assertDoesNotExist()
        compose.onNodeWithText("지금 시작하기").assertDoesNotExist()
        compose.onNodeWithText("Tip!", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("이전 추천 질문").assertIsNotEnabled()
        compose.onNodeWithTag("recommended-topics").performTouchInput { swipeLeft() }
        compose.onNodeWithText("지난 여행에서 가장 기억에 남는 순간은 언제였나요?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Tip!", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("다음 추천 질문").performClick()
        compose.onNodeWithText("도착하면 가족과 가장 먼저 무엇을 해보고 싶나요?", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 추천 질문").assertIsNotEnabled()
        compose.onNodeWithContentDescription("이전 추천 질문").performClick()
        compose.onNodeWithText("Tip!", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals("memories", vm.uiState.value.selectedTopicId) }
    }

    @Test fun narrowWindowAndLargeFont_keepNavigationControlsInsideViewport() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 2f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp).testTag("viewport")) {
                    val topics = sampleDepartureResponse.toModel()
                    DepartureScreen(DepartureViewModel.UiState(topics = topics, selectedTopicId = topics.first().id, loading = false),
                        {}, {}, {}, {}, {})
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        for (node in listOf(compose.onNodeWithText("뒤로가기"),
            compose.onNodeWithContentDescription("다음 추천 질문"),
            compose.onNodeWithContentDescription("이전 추천 질문"))) {
            val bounds = node.assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }

    @Test fun morphInterpolatesBackground_thenShowsConversation_andBackRestoresRecommendations() {
        lateinit var vm: DepartureViewModel
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.runOnUiThread { vm = DepartureViewModel(savedStateHandle = SavedStateHandle(mapOf("phase" to "Morphing"))) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DepartureRoute(onBack = {}, viewModel = vm)
        }
        compose.mainClock.advanceTimeByFrame()
        fun backgroundRed(tag: String): Float {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return image[image.width / 2, (image.height * 0.17f).toInt()].red
        }
        val before = backgroundRed("departure-screen")
        compose.mainClock.advanceTimeBy(300)
        val middle = backgroundRed("departure-screen")
        compose.onNodeWithTag("topic-character").assertIsDisplayed()
        compose.onNodeWithTag("talk-screen").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("talk-screen").assertIsDisplayed()
        compose.onNodeWithText("주행 중").assertIsDisplayed()
        compose.onNodeWithTag("conversation-wave").assertIsDisplayed()
        val after = backgroundRed("talk-screen")
        assertTrue(middle > before + 0.02f && middle < after - 0.02f)
        compose.onNodeWithText("뒤로가기").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("departure-screen").assertIsDisplayed()
        compose.onNodeWithText("출발 전").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
    }

    @Test fun disabledAnimations_completeMorphWithoutWaiting() {
        lateinit var vm: DepartureViewModel
        compose.runOnUiThread { vm = DepartureViewModel(savedStateHandle = SavedStateHandle(mapOf("phase" to "Morphing"))) }
        compose.setContent { DepartureRoute(onBack = {}, animationsEnabled = false, viewModel = vm) }
        compose.onNodeWithTag("talk-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 추천 질문").assertDoesNotExist()
    }

    @Test fun backgroundImmediately_cancelsTimerBeforeRecomposition() {
        lateinit var vm: DepartureViewModel
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            vm = DepartureViewModel()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { DepartureRoute(onBack = {}, viewModel = vm) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            vm.onSpeedChanged(1f)
            assertEquals(5, vm.uiState.value.countdownSeconds)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            assertNull(vm.uiState.value.countdownSeconds)
            vm.onSpeedChanged(1f)
            assertNull(vm.uiState.value.countdownSeconds)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("departure-screen").assertIsDisplayed()
        assertNull(vm.uiState.value.countdownSeconds)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
    }
}
