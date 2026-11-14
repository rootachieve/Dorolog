package com.forever.dorolog

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrivingAudioSessionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstQuestion_isSpokenBeforeCapture_withoutBackend_andNotRepeatedAfterResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.setContent { }
        val playbackStarted = CountDownLatch(1)
        val playbackFinished = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val playbackCount = AtomicInteger()
        val speakerActive = AtomicBoolean()
        val frameCount = AtomicInteger()
        val failure = AtomicReference<String?>()
        lateinit var runtime: DrivingAudioSession
        compose.runOnIdle {
            runtime = DrivingAudioSession(context, endpoint = "")
            runtime.observe(object : DrivingRuntime.Listener {
                override fun onCallState(blocked: Boolean, available: Boolean) = Unit
                override fun onSessionReady(sessionId: String) = Unit
                override fun onAmplitude(amplitude: Int, speech: Boolean) {
                    if (speakerActive.get()) failure.set("Microphone captured its own question")
                    frameCount.incrementAndGet()
                    captured.countDown()
                }
                override fun onCaptureChanged(capturing: Boolean) {
                    if (capturing && playbackFinished.count != 0L) failure.set("Capture started before question finished")
                }
                override fun onQuestion(question: String) = Unit
                override fun onResponseStarted() = Unit
                override fun onPlaybackChanged(playing: Boolean) {
                    speakerActive.set(playing)
                    if (playing) {
                        playbackCount.incrementAndGet()
                        playbackStarted.countDown()
                    }
                }
                override fun onResponseFinished() { playbackFinished.countDown() }
                override fun onWaitingForAudio(waiting: Boolean) = Unit
                override fun onServerUnavailable(message: String) = Unit
                override fun onUnavailable(message: String) { failure.set(message) }
            })
            runtime.updateCallState(blocked = false, available = true)
            runtime.start(RecommendedTopic("tts-test", "오늘은 어떤 하루였나요?"), null, null, "tts-test-trip")
        }
        try {
            assertTrue("First question was not spoken: ${failure.get()}", playbackStarted.await(30, TimeUnit.SECONDS))
            assertTrue("Question did not finish: ${failure.get()}", playbackFinished.await(30, TimeUnit.SECONDS))
            assertTrue("Capture did not resume: ${failure.get()}", captured.await(5, TimeUnit.SECONDS))
            var previousFrames = 0
            compose.runOnIdle {
                runtime.stop()
                previousFrames = frameCount.get()
                runtime.start(RecommendedTopic("tts-test", "오늘은 어떤 하루였나요?"), null, null, "tts-test-trip")
            }
            compose.waitUntil(5_000) { frameCount.get() > previousFrames }
            assertEquals(1, playbackCount.get())
            assertNull(failure.get())
        } finally {
            compose.runOnIdle { runtime.close() }
        }
    }

    @Test fun microphone_yieldsToNavigation_thenResumesRealFrames_andStopsWithoutBackend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.setContent { }
        val frames = CountDownLatch(5)
        val navigationRequested = AtomicBoolean()
        val waitingForNavigation = AtomicBoolean()
        val navigationWait = CountDownLatch(1)
        val captureYielded = CountDownLatch(1)
        val focusReturned = CountDownLatch(1)
        val resumedFrames = CountDownLatch(5)
        val manuallyStopped = AtomicBoolean()
        val framesAfterStop = CountDownLatch(1)
        val audio = context.getSystemService(AudioManager::class.java)
        val navigationFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()
        var navigationHeld = false
        val frameCount = AtomicInteger()
        val failure = AtomicReference<String?>()
        val firstQuestionFinished = CountDownLatch(1)
        lateinit var runtime: DrivingAudioSession
        compose.runOnIdle {
            runtime = DrivingAudioSession(context, endpoint = "")
            runtime.observe(object : DrivingRuntime.Listener {
                override fun onCallState(blocked: Boolean, available: Boolean) = Unit
                override fun onSessionReady(sessionId: String) = Unit
                override fun onAmplitude(amplitude: Int, speech: Boolean) {
                    if (amplitude !in 0..100) failure.set("Invalid amplitude: $amplitude")
                    frameCount.incrementAndGet()
                    frames.countDown()
                    if (waitingForNavigation.get()) failure.set("Microphone captured during navigation focus")
                    if (focusReturned.count == 0L) resumedFrames.countDown()
                    if (manuallyStopped.get()) framesAfterStop.countDown()
                }
                override fun onCaptureChanged(capturing: Boolean) {
                    if (!capturing && navigationRequested.get()) captureYielded.countDown()
                }
                override fun onQuestion(question: String) = Unit
                override fun onResponseStarted() = Unit
                override fun onPlaybackChanged(playing: Boolean) = Unit
                override fun onResponseFinished() { firstQuestionFinished.countDown() }
                override fun onWaitingForAudio(waiting: Boolean) {
                    waitingForNavigation.set(waiting)
                    if (waiting) navigationWait.countDown()
                    else if (navigationRequested.get()) focusReturned.countDown()
                }
                override fun onServerUnavailable(message: String) = Unit
                override fun onUnavailable(message: String) { failure.set(message) }
            })
            // This test supplies call clearance; production still requires an authoritative provider.
            runtime.updateCallState(blocked = false, available = true)
            runtime.setForeground(true)
            runtime.start(RecommendedTopic("microphone-test", "마이크 확인"), null, null, "microphone-test-trip")
        }
        try {
            assertTrue("First question did not finish: ${failure.get()}", firstQuestionFinished.await(30, TimeUnit.SECONDS))
            assertTrue("Actual AudioRecord frames missing: ${failure.get()}", frames.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            compose.runOnIdle {
                navigationRequested.set(true)
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(navigationFocus))
                navigationHeld = true
            }
            assertTrue("Runtime did not yield to navigation", navigationWait.await(5, TimeUnit.SECONDS))
            assertTrue("Microphone did not stop for navigation", captureYielded.await(5, TimeUnit.SECONDS))
            assertTrue(waitingForNavigation.get())
            compose.runOnIdle {
                audio.abandonAudioFocusRequest(navigationFocus)
                navigationHeld = false
            }
            assertTrue("Runtime did not regain focus", focusReturned.await(5, TimeUnit.SECONDS))
            assertTrue("Microphone frames did not resume: ${failure.get()}", resumedFrames.await(5, TimeUnit.SECONDS))
            assertFalse(waitingForNavigation.get())
            assertNull(failure.get())
            compose.runOnIdle { runtime.stop(); manuallyStopped.set(true) }
            val stoppedCount = frameCount.get()
            assertFalse("Microphone continued after stop", framesAfterStop.await(150, TimeUnit.MILLISECONDS))
            assertEquals(stoppedCount, frameCount.get())
        } finally {
            compose.runOnIdle {
                runtime.close()
                if (navigationHeld) audio.abandonAudioFocusRequest(navigationFocus)
            }
        }
    }
}
