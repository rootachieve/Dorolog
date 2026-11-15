package com.forever.dorolog

import android.graphics.Bitmap
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepartureScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recommendationsSwipe_andArrowsChangeSelection_withoutMapOrStartButton() {
        lateinit var vm: DepartureViewModel
        compose.runOnUiThread {
            vm = DepartureViewModel(tripId = "selected-trip")
            vm.setActive(true)
            vm.onNavigationChanged(null, true)
        }
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
        compose.runOnUiThread {
            vm = DepartureViewModel(tripId = "selected-trip", tripRepository = TripRepository(finishTrip = {}))
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            DepartureRoute(tripId = "selected-trip", onBack = {}, viewModel = vm,
                summaryAnimationsEnabled = false, summaryRepository = TripSummaryRepository { trip ->
                    TripSummaryResponseDto(trip, emptyList(), emptyList())
                })
        }
        startDriving(vm)
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
        compose.onNodeWithTag("settlement-screen").assertIsDisplayed()
        compose.onNodeWithText("이번 여행에서 저장한 이야기가 없어요.").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
    }

    @Test fun disabledAnimations_completeMorphWithoutWaiting() {
        lateinit var vm: DepartureViewModel
        compose.runOnUiThread { vm = DepartureViewModel(tripId = "selected-trip") }
        compose.setContent { DepartureRoute(tripId = "selected-trip", onBack = {}, animationsEnabled = false, viewModel = vm) }
        startDriving(vm)
        compose.onNodeWithTag("talk-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 추천 질문").assertDoesNotExist()
    }

    @Test fun backgroundImmediately_cancelsTimerBeforeRecomposition() {
        lateinit var vm: DepartureViewModel
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            vm = DepartureViewModel(tripId = "selected-trip")
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { DepartureRoute(tripId = "selected-trip", onBack = {}, viewModel = vm) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            vm.onNavigationChanged(null, true)
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

    private fun startDriving(vm: DepartureViewModel) {
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 15_000) {
            // Vehicle speed keeps arriving after a temporary Activity pause/resume on the emulator.
            compose.runOnUiThread { vm.onNavigationChanged(null, true); vm.onSpeedChanged(1f) }
            vm.uiState.value.phase != DepartureViewModel.Phase.Ready
        }
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
        compose.onNodeWithText("지금은 기록하지 않아요. 준비되면 다시 들을게요.").assertDoesNotExist()
        // Pleos can reposition the embedded test window while dispatching touch events.
        compose.onNodeWithTag("pause-conversation").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, pauses)
            state = state.copy(paused = true, resumeEnabled = false, status = "통화 중")
        }
        compose.onNodeWithTag("resume-conversation").assertIsNotEnabled()
        compose.onNodeWithTag("finish-conversation").assertIsDisplayed()
        compose.onNodeWithText("지금은 기록하지 않아요. 준비되면 다시 들을게요.").assertIsDisplayed()
        compose.onNodeWithText("통화 중").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(status = "차량 안전 경고가 해제되면 재개할 수 있어요") }
        compose.onNodeWithText("지금은 기록하지 않아요. 준비되면 다시 들을게요.").assertIsDisplayed()
        compose.onNodeWithText("차량 안전 경고가 해제되면 재개할 수 있어요").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(resumeEnabled = true, status = null) }
        compose.onNodeWithTag("resume-conversation").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, resumes) }
        compose.runOnIdle { state = state.copy(paused = false) }
        compose.onNodeWithText("지금은 기록하지 않아요. 준비되면 다시 들을게요.").assertDoesNotExist()
    }

    @Test fun longFollowUpQuestion_fitsAboveWave_inNarrowAndShortWindowsWithLargeText() {
        var viewport by androidx.compose.runtime.mutableStateOf(850 to 592)
        var fontScale by androidx.compose.runtime.mutableStateOf(1f)
        var question by androidx.compose.runtime.mutableStateOf("이번 여행은 어떤가요?")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, fontScale)) {
                Box(Modifier.requiredSize(viewport.first.dp, viewport.second.dp).testTag("question-viewport")) {
                    TalkScreen(DepartureViewModel.UiState(phase = DepartureViewModel.Phase.Talk),
                        DrivingViewModel.UiState(question = question, paused = false), {}, {}, {})
                }
            }
        }
        compose.runOnIdle {
            question = "그때 가족들과 함께 여행하며 가장 기억에 남았던 순간은 무엇이었나요? 그 순간에 어떤 마음이 들었는지도 이야기해 주세요."
        }
        for ((width, height, scale) in listOf(Triple(850, 592, 1f), Triple(360, 640, 2f), Triple(640, 360, 2f))) {
            compose.runOnIdle { viewport = width to height; fontScale = scale }
            val node = compose.onNodeWithText("“$question”").assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Question is clipped at $viewport, font scale $scale", layouts.single().hasVisualOverflow)
            val bounds = node.getUnclippedBoundsInRoot()
            val frame = compose.onNodeWithTag("question-viewport").getUnclippedBoundsInRoot()
            val wave = compose.onNodeWithTag("conversation-wave").getUnclippedBoundsInRoot()
            assertTrue("Question leaves viewport at $viewport", bounds.left >= frame.left && bounds.right <= frame.right &&
                bounds.top >= frame.top && bounds.bottom <= frame.bottom)
            assertTrue("Question overlaps waveform at $viewport", bounds.bottom <= wave.top)
        }
    }

    @Test fun pausedTooltipAndControls_fitNarrowAndShortWindows_withLargeText() {
        var viewport by androidx.compose.runtime.mutableStateOf(850 to 592)
        var fontScale by androidx.compose.runtime.mutableStateOf(1f)
        val topics = sampleDepartureResponse.toModel()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(viewport.first.dp, viewport.second.dp).testTag("pause-viewport")) {
                    TalkScreen(DepartureViewModel.UiState(topics = topics, selectedTopicId = topics.first().id,
                        loading = false, phase = DepartureViewModel.Phase.Talk),
                        DrivingViewModel.UiState(question = topics.first().question, paused = true,
                            status = "통화가 끝나면 재개할 수 있어요"), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("지금은 기록하지 않아요. 준비되면 다시 들을게요.").assertIsDisplayed()
        val screenshot = compose.onNodeWithTag("talk-screen").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "paused-recording-tooltip.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (size in listOf(360 to 640, 640 to 360)) {
            compose.runOnIdle { viewport = size; fontScale = 2f }
            val frame = compose.onNodeWithTag("pause-viewport").getUnclippedBoundsInRoot()
            val tooltip = compose.onNodeWithText("지금은 기록하지 않아요. 준비되면 다시 들을게요.")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val finish = compose.onNodeWithTag("finish-conversation").assertIsDisplayed().getUnclippedBoundsInRoot()
            val pause = compose.onNodeWithTag("resume-conversation").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Pause touch target too small at $size", pause.right - pause.left >= 48.dp && pause.bottom - pause.top >= 48.dp)
            val label = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("일시정지", useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(label) }
            val detail = label.single()
            val scaledImage = compose.onNodeWithTag("talk-screen").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "paused-recording-tooltip-large.png")
                .outputStream().use { scaledImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Paragraph width includes empty space; compare actual text lines, allowing pixel rounding.
            assertTrue("Pause label is clipped at $size", (0 until detail.lineCount).all { line ->
                detail.getLineLeft(line) >= -1f && detail.getLineRight(line) <= detail.size.width + 1f &&
                    detail.getLineBottom(line) <= detail.size.height + 1f
            })
            assertTrue("Tooltip overlaps finish at $size", tooltip.bottom <= finish.top)
            assertTrue("Tooltip overlaps pause at $size", pause.bottom <= tooltip.top)
            for (bounds in listOf(tooltip, finish, pause)) {
                assertTrue("Controls leave viewport at $size", bounds.left >= frame.left && bounds.right <= frame.right &&
                    bounds.top >= frame.top && bounds.bottom <= frame.bottom)
            }
        }
    }

    @Test fun parkedShowsEmptyTrip_withoutInventedSavedStories() {
        compose.setContent { ParkedScreen(DepartureViewModel.UiState(destination = Destination("부산 할머니댁")), {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("parked-screen").assertIsDisplayed()
        compose.onNodeWithText("주차 중").assertIsDisplayed()
        compose.onNodeWithText("남길 이야기 고르기").assertIsDisplayed()
        compose.onNodeWithText("이야기 저장").assertIsDisplayed()
        compose.onNodeWithText("5개의 이야기를 저장했어요", substring = true).assertDoesNotExist()
    }

    @Test fun finishFailureRetriesSameTrip_andOnlyAcknowledgmentOpensRealSummary_thenConfirmationReturnsHome() {
        val tripId = "chosen-trip"
        val firstFinish = CompletableDeferred<Unit>()
        val retryFinish = CompletableDeferred<Unit>()
        val finishRequests = mutableListOf<String>()
        val summaryRequests = mutableListOf<String>()
        val completedTrips = mutableListOf<String>()
        var home by androidx.compose.runtime.mutableStateOf(false)
        lateinit var vm: DepartureViewModel
        compose.runOnUiThread {
            vm = DepartureViewModel(tripId, tripRepository = TripRepository(
                loadStories = { TripStories(emptyList(), emptySet()) },
                finishTrip = { requestedTrip ->
                    finishRequests += requestedTrip
                    if (finishRequests.size == 1) firstFinish.await() else retryFinish.await()
                },
            ))
        }
        val summaryRepository = TripSummaryRepository { requestedTrip ->
            summaryRequests += requestedTrip
            TripSummaryResponseDto(requestedTrip,
                listOf(TripSummaryStoryDto("saved-story", "가족의 바다 이야기", "부산")), listOf("saved-story"))
        }
        compose.setContent {
            if (!home) DepartureRoute(tripId, onBack = { home = true }, animationsEnabled = false,
                summaryRepository = summaryRepository, onTripFinished = { completedTrips += it }, viewModel = vm)
        }
        compose.waitForIdle()
        compose.runOnIdle { vm.onDestinationArrived(); vm.onParkedChanged(true) }
        compose.onNodeWithTag("parked-screen").assertIsDisplayed()
        compose.onNodeWithText("여행 종료하기").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil { finishRequests.size == 1 }
        compose.onNodeWithTag("trip-finishing-screen").assertIsDisplayed()
        compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(summaryRequests.isEmpty())
            assertTrue(completedTrips.isEmpty())
            firstFinish.completeExceptionally(IOException("offline"))
        }
        compose.waitUntil { vm.uiState.value.finishError != null }
        compose.onNodeWithText("여행 종료를 완료하지 못했어요. 다시 시도해 주세요").assertIsDisplayed()
        compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
        compose.onNodeWithText("다시 시도").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil { finishRequests.size == 2 }
        compose.onNodeWithText("여행을 마무리하고 있어요").assertIsDisplayed()
        compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(tripId, tripId), finishRequests)
            assertTrue(summaryRequests.isEmpty())
            assertTrue(completedTrips.isEmpty())
            retryFinish.complete(Unit)
        }
        compose.waitUntil { summaryRequests.size == 1 }
        compose.onNodeWithTag("settlement-screen").assertIsDisplayed()
        compose.onNodeWithText("1개의 이야기를 저장했어요.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("1번 이야기, 부산, 가족의 바다 이야기").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(tripId), summaryRequests)
            assertEquals(listOf(tripId), completedTrips)
            assertFalse(home)
        }
        compose.onNodeWithText("확인").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertTrue(home) }
        compose.onNodeWithTag("settlement-screen").assertDoesNotExist()
    }
}
