package com.forever.dorolog

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrivingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun waveform_keepsNewestMicrophoneAmplitudeOnRight_inFixedHistory() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        assertEquals(List(DrivingViewModel.WAVEFORM_SIZE) { 0 }, vm.uiState.value.amplitudes)
        runtime.listener.onAmplitude(20, false)
        runtime.listener.onAmplitude(60, false)
        assertEquals(listOf(0, 20, 60), vm.uiState.value.amplitudes.takeLast(3))
        repeat(120) { runtime.listener.onAmplitude(120, false) }
        assertEquals(List(DrivingViewModel.WAVEFORM_SIZE) { 100 }, vm.uiState.value.amplitudes)
        vm.pause()
        runtime.listener.onAmplitude(80, true)
        assertEquals(List(DrivingViewModel.WAVEFORM_SIZE) { 0 }, vm.uiState.value.amplitudes)
        vm.finish()
    }

    @Test fun followUp_occursOnlyAtThirtySecondsOfSilence_andSpeechRestartsDeadline() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, runtime.followUps)
        runtime.listener.onSpeechChanged(true)
        runtime.listener.onSpeechChanged(false)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, runtime.followUps)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runtime.followUps)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(1, runtime.followUps)
        vm.finish()
    }

    @Test fun continuousServerSpeech_suppressesFollowUp_untilThirtySecondsAfterSpeechStops() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        runtime.listener.onSpeechChanged(true)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(0, runtime.followUps)
        runtime.listener.onSpeechChanged(false)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, runtime.followUps)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runtime.followUps)
        vm.finish()
    }

    @Test fun loudMicrophoneMeter_doesNotReplaceAuthoritativeSpeechDetection() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        advanceTimeBy(29_999)
        runtime.listener.onAmplitude(100, true)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runtime.followUps)
        vm.finish()
    }

    @Test fun pause_ignoresLateSpeechEvents_andResetsVadForNextListeningSession() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        runtime.listener.onSpeechChanged(true)
        vm.pause()
        runtime.listener.onSpeechChanged(false)
        runtime.listener.onSpeechChanged(true)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(0, runtime.followUps)
        vm.resume()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, runtime.followUps)
        vm.finish()
    }

    @Test fun pendingFollowUp_keepsCapture_andInFlightSpeechRestartsSilenceDeadline() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, runtime.followUps)
        assertTrue(vm.uiState.value.capturing)
        runtime.listener.onAmplitude(45, false)
        assertEquals(45, vm.uiState.value.amplitudes.last())
        runtime.listener.onSpeechChanged(true)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, runtime.followUps)
        runtime.listener.onSpeechChanged(false)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, runtime.followUps)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, runtime.followUps)
        vm.finish()
    }

    @Test fun responseAndSpeakerDrain_suspendSilenceTimer_untilMicrophoneReturns() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        advanceTimeBy(20_000)
        runtime.listener.onResponseStarted()
        runtime.listener.onCaptureChanged(false)
        runtime.listener.onQuestion("서버의 다음 질문")
        runtime.listener.onPlaybackChanged(true)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(0, runtime.followUps)
        assertEquals("서버의 다음 질문", vm.uiState.value.question)
        runtime.listener.onPlaybackChanged(false)
        runtime.listener.onResponseFinished()
        runtime.listener.onCaptureChanged(true)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, runtime.followUps)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runtime.followUps)
        vm.finish()
    }

    @Test fun transientNavigationWait_hasNoPauseOverlay_andUsesFreshSilenceDeadline() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runCurrent()
        advanceTimeBy(20_000)
        runtime.listener.onCaptureChanged(false)
        runtime.listener.onWaitingForAudio(true)
        assertFalse(vm.uiState.value.paused)
        assertTrue(vm.uiState.value.waitingForAudio)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, runtime.followUps)
        runtime.listener.onWaitingForAudio(false)
        runtime.listener.onSessionReady("same-session")
        runtime.listener.onCaptureChanged(true)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, runtime.followUps)
        vm.pause()
        runtime.listener.onWaitingForAudio(false)
        runtime.listener.onCaptureChanged(true)
        assertTrue(vm.uiState.value.paused)
        assertFalse(vm.uiState.value.capturing)
        vm.finish()
    }

    @Test fun callsAndUnavailableSafety_blockEvenExplicitResume_untilKnownClear() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        runtime.callBlocked = true
        runtime.listener.onCallState(true, true)
        assertTrue(vm.uiState.value.paused)
        assertFalse(vm.uiState.value.resumeEnabled)
        vm.resume()
        assertEquals(1, runtime.starts)
        runtime.callBlocked = false
        runtime.listener.onCallState(false, true)
        vm.onSafetyChanged(true, true)
        vm.resume()
        assertEquals(1, runtime.starts)
        vm.onSafetyChanged(false, false)
        assertFalse(vm.uiState.value.resumeEnabled)
        assertEquals("차량 안전 상태를 확인할 수 없어요", vm.uiState.value.status)
        vm.onSafetyChanged(false, true)
        runtime.callAvailable = false
        runtime.listener.onCallState(false, false)
        vm.resume()
        assertEquals(1, runtime.starts)
        assertEquals("통화 상태를 확인할 수 없어요", vm.uiState.value.status)
        runtime.callAvailable = true
        runtime.listener.onCallState(false, true)
        assertTrue(vm.uiState.value.resumeEnabled)
        vm.resume()
        assertEquals(2, runtime.starts)
        vm.finish()
    }

    @Test fun background_requiresManualResume_andResumesSameConversation() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        vm.setForeground(false)
        assertTrue(vm.uiState.value.paused)
        assertFalse(vm.uiState.value.capturing)
        assertFalse(vm.uiState.value.resumeEnabled)
        vm.setForeground(true)
        assertEquals(1, runtime.starts)
        assertTrue(vm.uiState.value.resumeEnabled)
        vm.resume()
        assertEquals(2, runtime.starts)
        assertEquals("server-session", runtime.resumedSession)
        vm.finish()
    }

    @Test fun recreation_restoresQuestionAndSession_butNeverAutomaticallyCaptures() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(
            "drivingActive" to true, "drivingQuestion" to "이어서 할 질문", "drivingSessionId" to "retained",
        ))
        val runtime = FakeRuntime()
        val vm = started(runtime, saved)
        assertEquals(0, runtime.starts)
        assertTrue(vm.uiState.value.paused)
        assertEquals("이어서 할 질문", vm.uiState.value.question)
        vm.resume()
        assertEquals(1, runtime.starts)
        assertEquals("retained", runtime.resumedSession)
        vm.finish()
        assertFalse(saved.get<Boolean>("drivingActive")!!)
        assertNull(saved.get<String>("drivingSessionId"))
    }

    @Test fun finish_thenNewDrive_startsFreshWithoutOldSessionOrPauseLatch() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val vm = started(runtime)
        vm.finish()
        vm.start(RecommendedTopic("new", "새 여행 질문"), null)
        assertEquals(2, runtime.starts)
        assertFalse(vm.uiState.value.paused)
        assertEquals("새 여행 질문", vm.uiState.value.question)
        assertNull(runtime.resumedSession)
        vm.finish()
    }

    @Test fun serverMissing_keepsActualMicrophone_butNeverInventsFollowUp() = runTest(dispatcher) {
        val runtime = FakeRuntime().apply { ready = false }
        val vm = started(runtime)
        runtime.listener.onServerUnavailable("대화 서버가 설정되지 않았어요")
        runtime.listener.onAmplitude(33, false)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(0, runtime.followUps)
        assertTrue(vm.uiState.value.capturing)
        assertEquals(33, vm.uiState.value.amplitudes.last())
        assertEquals("처음 질문", vm.uiState.value.question)
        vm.finish()
    }

    private fun started(runtime: FakeRuntime, saved: SavedStateHandle = SavedStateHandle()): DrivingViewModel =
        DrivingViewModel(runtime, saved).apply {
            setForeground(true)
            onSafetyChanged(false, true)
            start(RecommendedTopic("topic", "처음 질문"), Destination("서울"))
        }

    private class FakeRuntime : DrivingRuntime {
        lateinit var listener: DrivingRuntime.Listener
        var starts = 0
        var followUps = 0
        var resumedSession: String? = null
        var callAvailable = true
        var callBlocked = false
        var ready = true
        override fun observe(listener: DrivingRuntime.Listener) { this.listener = listener; refreshCallState() }
        override fun refreshCallState() { listener.onCallState(callBlocked, callAvailable) }
        override fun start(topic: RecommendedTopic, destination: Destination?, sessionId: String?) {
            starts++
            resumedSession = sessionId
            if (ready) listener.onSessionReady("server-session")
            listener.onCaptureChanged(true)
        }
        override fun requestFollowUp(): Boolean { followUps++; return true }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
