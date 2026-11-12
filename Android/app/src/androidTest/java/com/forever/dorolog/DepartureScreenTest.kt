package com.forever.dorolog

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    @Test fun morphInterpolatesBackground_thenDrivingHidesBack_andPhysicalBackPauses() {
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
        assertTrue(middle > before + 0.02f && middle < 1f)
        compose.onNodeWithText("뒤로가기").assertDoesNotExist()
        compose.runOnUiThread { back.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("finish-conversation").assertIsDisplayed().performClick()
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

    @Test fun drivingControls_onlyOfferFinishWhilePaused_andBlockUnsafeResume() {
        val topics = sampleDepartureResponse.toModel()
        val departure = DepartureViewModel.UiState(topics = topics, selectedTopicId = topics.first().id,
            loading = false, phase = DepartureViewModel.Phase.Talk)
        var state by androidx.compose.runtime.mutableStateOf(DrivingViewModel.UiState(
            question = topics.first().question, paused = false))
        var pauses = 0
        var resumes = 0
        compose.setContent { TalkScreen(departure, state, { pauses++ }, { resumes++ }, {}) }
        compose.onNodeWithText("주행 중").assertIsDisplayed()
        compose.onNodeWithTag("conversation-wave").assertIsDisplayed()
        compose.onNodeWithText("뒤로가기").assertDoesNotExist()
        compose.onNodeWithText("종료하기").assertDoesNotExist()
        compose.onNodeWithTag("pause-conversation").performClick()
        compose.runOnIdle {
            assertEquals(1, pauses)
            state = state.copy(paused = true, resumeEnabled = false, status = "통화 중")
        }
        compose.onNodeWithTag("resume-conversation").assertIsNotEnabled()
        compose.onNodeWithTag("finish-conversation").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(resumeEnabled = true, status = null) }
        compose.onNodeWithTag("resume-conversation").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test fun arrivalShowsActualDestination_withoutInventedSavedStories() {
        compose.setContent { ArrivalScreen(Destination("부산 할머니댁"), {}) }
        compose.onNodeWithTag("arrival-screen").assertIsDisplayed()
        compose.onNodeWithText("목적지 도착").assertIsDisplayed()
        compose.onNodeWithText("부산 할머니댁").assertIsDisplayed()
        compose.onNodeWithText("확인").assertIsDisplayed()
        compose.onNodeWithText("5개의 이야기를 저장했어요", substring = true).assertDoesNotExist()
    }
}
