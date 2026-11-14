package com.forever.dorolog

import android.Manifest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrivingQuestionSpeechTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textOnlyFollowUp_readsLatestQuestionOnce_andWaitsForSpeechBeforeCapture() {
        val conversation = conversation()
        try {
            compose.runOnIdle {
                assertTrue(conversation.runtime.requestFollowUp())
                conversation.socket.response("response.started")
                conversation.socket.response("response.text", "오늘은")
                conversation.socket.response("response.text", QUESTION)
            }
            compose.runOnIdle {
                assertEquals(QUESTION, conversation.question.get())
                assertEquals(0, conversation.playbacks.get())
                assertFalse(conversation.capturing.get())
                conversation.socket.response("response.done")
            }
            conversation.await { conversation.playbacks.get() == 1 }
            assertEquals(1, conversation.socket.followUps.get())
            assertEquals(1, conversation.captures.get())
            assertEquals(0, conversation.finishes.get())
            assertFalse(conversation.capturing.get())
            conversation.await(timeoutMillis = 60_000) {
                conversation.finishes.get() == 1 && conversation.captures.get() == 2
            }
            assertEquals(1, conversation.playbacks.get())
            assertFalse(conversation.playing.get())
        } finally {
            conversation.close()
        }
    }

    @Test fun pcmFollowUp_playsOnce_withoutAlsoReadingQuestionText() {
        val conversation = conversation()
        val question = QUESTION.repeat(3)
        try {
            compose.runOnIdle {
                assertTrue(conversation.runtime.requestFollowUp())
                conversation.socket.response("response.started")
                conversation.socket.response("response.text", question)
                conversation.socket.pcm(ByteArray(24_000))
                conversation.socket.response("response.done")
            }
            conversation.await(timeoutMillis = 10_000) {
                conversation.finishes.get() == 1 && conversation.captures.get() == 2
            }
            assertEquals(question, conversation.question.get())
            assertEquals(1, conversation.playbacks.get())
            assertFalse(conversation.playing.get())
        } finally {
            conversation.close()
        }
    }

    @Test fun stopDuringQuestionSpeech_cancelsOutput_andNeverRestartsCapture() {
        val conversation = conversation()
        try {
            compose.runOnIdle {
                assertTrue(conversation.runtime.requestFollowUp())
                conversation.socket.response("response.started")
                conversation.socket.response("response.text", QUESTION)
                conversation.socket.response("response.done")
            }
            conversation.await { conversation.playbacks.get() == 1 }
            assertEquals(0, conversation.finishes.get())
            compose.runOnIdle {
                conversation.runtime.stop()
                conversation.stopped.set(true)
                conversation.socket.response("response.done")
                conversation.socket.response("response.text", "중단된 질문")
            }
            assertTrue(conversation.socket.cancelled.get())
            assertFalse("Capture restarted after speech was stopped",
                conversation.captureAfterStop.await(1, TimeUnit.SECONDS))
            assertEquals(1, conversation.captures.get())
            assertEquals(0, conversation.finishes.get())
            assertFalse(conversation.capturing.get())
            assertNull(conversation.failure.get())
        } finally {
            conversation.close()
        }
    }

    private fun conversation(): Conversation {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.setContent { }
        return Conversation().apply {
            try {
                compose.runOnIdle {
                    runtime = DrivingAudioSession(context, endpoint = "wss://voice.example/conversation",
                        socketFactory = object : WebSocket.Factory {
                            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                                Socket(request, listener).also { socketReference.set(it) }
                        })
                    runtime.observe(this)
                    runtime.updateCallState(blocked = false, available = true)
                    runtime.setForeground(true)
                    runtime.start(RecommendedTopic("speech-test", ""), null, null, "speech-test-trip")
                }
                await { socketReference.get() != null }
                compose.runOnIdle {
                    socket.open()
                    socket.message("""{"type":"session.ready","session_id":"speech-test-session"}""")
                }
                await { captures.get() == 1 }
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
    }

    private inner class Conversation : DrivingRuntime.Listener {
        lateinit var runtime: DrivingAudioSession
        val socketReference = AtomicReference<Socket>()
        val socket get() = requireNotNull(socketReference.get())
        val question = AtomicReference<String>()
        val failure = AtomicReference<String>()
        val capturing = AtomicBoolean()
        val playing = AtomicBoolean()
        val stopped = AtomicBoolean()
        val captureAfterStop = CountDownLatch(1)
        val captures = AtomicInteger()
        val playbacks = AtomicInteger()
        val finishes = AtomicInteger()

        fun await(timeoutMillis: Long = 30_000, condition: () -> Boolean) {
            compose.waitUntil(timeoutMillis) { failure.get() != null || condition() }
            assertNull("Audio runtime failed", failure.get())
        }

        fun close() = compose.runOnIdle { if (::runtime.isInitialized) runtime.close() }
        override fun onCallState(blocked: Boolean, available: Boolean) = Unit
        override fun onSessionReady(sessionId: String) = Unit
        override fun onAmplitude(amplitude: Int, speech: Boolean) = Unit
        override fun onQuestion(question: String) { this.question.set(question) }
        override fun onResponseStarted() = Unit
        override fun onCaptureChanged(capturing: Boolean) {
            this.capturing.set(capturing)
            if (capturing) {
                if (playing.get()) failure.set("Microphone captured question playback")
                if (stopped.get()) captureAfterStop.countDown()
                captures.incrementAndGet()
            }
        }
        override fun onPlaybackChanged(playing: Boolean) {
            this.playing.set(playing)
            if (playing) {
                if (capturing.get()) failure.set("Question playback started during capture")
                playbacks.incrementAndGet()
            }
        }
        override fun onResponseFinished() { finishes.incrementAndGet() }
        override fun onWaitingForAudio(waiting: Boolean) = Unit
        override fun onServerUnavailable(message: String) {
            if (message != "대화 서버에 연결하고 있어요") failure.set(message)
        }
        override fun onUnavailable(message: String) { failure.set(message) }
    }

    private class Socket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
        val cancelled = AtomicBoolean()
        val followUps = AtomicInteger()
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (cancelled.get()) return false
            if (JsonParser.parseString(text).asJsonObject["type"].asString == "response.create") followUps.incrementAndGet()
            return true
        }
        override fun send(bytes: ByteString): Boolean = !cancelled.get()
        override fun close(code: Int, reason: String?): Boolean { cancelled.set(true); return true }
        override fun cancel() { cancelled.set(true) }
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        fun message(text: String) = listener.onMessage(this, text)
        fun pcm(bytes: ByteArray) = listener.onMessage(this, bytes.toByteString())
        fun response(type: String, text: String? = null) = message(JsonObject().apply {
            addProperty("type", type)
            addProperty("response_id", "question-response")
            text?.let { addProperty("text", it) }
        }.toString())
    }

    private companion object {
        const val QUESTION = "오늘 여행에서 가장 기대되는 순간은 무엇인가요? 도착하면 누구와 어떤 시간을 보내고 싶은지도 들려주세요."
    }
}
