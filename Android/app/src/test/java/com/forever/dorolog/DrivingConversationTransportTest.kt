package com.forever.dorolog

import com.google.gson.JsonParser
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class DrivingConversationTransportTest {
    @Test fun startsWithConversationContext_andOnlySendsAudioAfterReady() {
        val fixture = Fixture()
        fixture.transport.connect(RecommendedTopic("topic", "첫 질문"), Destination("서울"), "retained",
            "trip", "서울특별시 종로구", 37.57, 126.98)
        val socket = fixture.factory.sockets.single()
        socket.open()
        val start = JsonParser.parseString(socket.texts.single()).asJsonObject
        assertEquals("session.start", start["type"].asString)
        assertEquals("retained", start["session_id"].asString)
        assertEquals("서울", start["destination_name"].asString)
        assertEquals("trip", start["trip_id"].asString)
        assertEquals("서울특별시 종로구", start["current_address"].asString)
        assertEquals(37.57, start["latitude"].asDouble, 0.0)
        assertEquals(126.98, start["longitude"].asDouble, 0.0)
        assertEquals(24_000, start["audio"].asJsonObject["sample_rate_hz"].asInt)
        assertFalse(fixture.transport.sendAudio(ByteArray(960)))
        socket.message("""{"type":"session.ready","session_id":"retained"}""")
        assertEquals("retained", fixture.events.sessionId)
        assertTrue(fixture.transport.sendAudio(ByteArray(960)))
        assertEquals(960, socket.audio.single().size)
        assertFalse(fixture.transport.sendAudio(ByteArray(961)))
    }

    @Test fun latestAddress_isSentBeforeAudio_andUnavailableAddressClearsPreviousValue() {
        val fixture = Fixture()
        fixture.transport.connect(RecommendedTopic("topic", "첫 질문"), null, null, "trip", "서울")
        val socket = fixture.factory.sockets.single()
        fixture.transport.updateLocation(VehicleLocation("종로구", 37.57, 126.98))
        socket.open()
        assertEquals("종로구", JsonParser.parseString(socket.texts.single()).asJsonObject["current_address"].asString)
        fixture.transport.updateLocation(VehicleLocation("강남구", 37.51, 127.05))
        assertEquals(1, socket.texts.size)
        socket.message("""{"type":"session.ready","session_id":"s1"}""")
        val changed = JsonParser.parseString(socket.texts.last()).asJsonObject
        assertEquals("location.changed", changed["type"].asString)
        assertEquals("trip", changed["trip_id"].asString)
        assertEquals("강남구", changed["current_address"].asString)
        assertEquals(37.51, changed["latitude"].asDouble, 0.0)
        assertEquals(127.05, changed["longitude"].asDouble, 0.0)
        fixture.transport.updateLocation(VehicleLocation(" 강남구 ", 37.51, 127.05))
        assertEquals(2, socket.texts.size)
        assertTrue(fixture.transport.sendAudio(ByteArray(960)))
        assertEquals(listOf("session.start", "location.changed", "audio"), socket.messages)
        fixture.transport.updateLocation(VehicleLocation())
        val cleared = JsonParser.parseString(socket.texts.last()).asJsonObject
        assertTrue(cleared["current_address"].isJsonNull)
        assertTrue(cleared["latitude"].isJsonNull)
        assertTrue(cleared["longitude"].isJsonNull)
        fixture.transport.updateLocation(VehicleLocation(" "))
        assertEquals(3, socket.texts.size)
    }

    @Test fun resumedSessionWithUnknownLocation_explicitlyClearsAllLocationFields() {
        val fixture = Fixture()
        fixture.transport.connect(RecommendedTopic("topic", "첫 질문"), null, "retained", "trip")
        val socket = fixture.factory.sockets.single()
        socket.open()
        val start = JsonParser.parseString(socket.texts.single()).asJsonObject
        assertEquals("retained", start["session_id"].asString)
        assertFalse(start.has("destination_name"))
        for (field in listOf("current_address", "latitude", "longitude")) {
            assertTrue(start.has(field))
            assertTrue(start[field].isJsonNull)
        }
    }

    @Test fun addressBackpressure_stopsAudioRatherThanSendingWithStaleLocation() {
        val fixture = Fixture().apply { connectReady() }
        val socket = fixture.factory.sockets.single().apply { queuedBytes = 47_500 }
        fixture.transport.updateLocation(VehicleLocation("가".repeat(200)))
        assertTrue(socket.cancelled)
        assertFalse(fixture.transport.sendAudio(ByteArray(960)))
        assertEquals(1, fixture.events.failures.size)
    }

    @Test fun invalidOrPartialCoordinatePair_isClearedWhileAddressIsRetained() {
        val fixture = Fixture().apply { connectReady() }
        val socket = fixture.factory.sockets.single()
        val valid = VehicleLocation("서울", 37.57, 126.98)
        for (invalid in listOf(
            valid.copy(latitude = Double.NaN), valid.copy(longitude = Double.POSITIVE_INFINITY),
            valid.copy(latitude = 90.1), valid.copy(longitude = -180.1), valid.copy(longitude = null),
        )) {
            fixture.transport.updateLocation(valid)
            fixture.transport.updateLocation(invalid)
            val changed = JsonParser.parseString(socket.texts.last()).asJsonObject
            assertEquals("서울", changed["current_address"].asString)
            assertTrue(changed["latitude"].isJsonNull)
            assertTrue(changed["longitude"].isJsonNull)
        }
    }

    @Test fun coordinatesChangingWithinSameAddress_areStillReported() {
        val fixture = Fixture().apply { connectReady() }
        fixture.transport.updateLocation(VehicleLocation("서울", 37.57, 126.98))
        fixture.transport.updateLocation(VehicleLocation("서울", 37.58, 126.98))
        val texts = fixture.factory.sockets.single().texts
        assertEquals(3, texts.size)
        assertEquals(37.58, JsonParser.parseString(texts.last()).asJsonObject["latitude"].asDouble, 0.0)
    }

    @Test fun streamsOnlyCurrentResponse_andFollowUpUsesServerHistory() {
        val fixture = Fixture().apply { connectReady() }
        val socket = fixture.factory.sockets.single()
        assertTrue(fixture.transport.requestFollowUp())
        val request = JsonParser.parseString(socket.texts.last()).asJsonObject
        assertEquals("response.create", request["type"].asString)
        assertEquals(5_000, request["silence_ms"].asInt)
        socket.message("""{"type":"response.started","response_id":"r1"}""")
        assertFalse(fixture.transport.requestFollowUp())
        socket.message("""{"type":"response.text","response_id":"old","text":"무시"}""")
        socket.message("""{"type":"response.text","response_id":"r1","text":"다음 질문"}""")
        socket.listener.onMessage(socket, byteArrayOf(1, 0).toByteString())
        socket.message("""{"type":"response.done","response_id":"r1"}""")
        assertEquals(listOf("다음 질문"), fixture.events.questions)
        assertEquals(1, fixture.events.audio.size)
        assertEquals(1, fixture.events.started)
        assertEquals(1, fixture.events.done)
        assertTrue(fixture.transport.requestFollowUp())
    }

    @Test fun cancellationAndClosedConnections_dropLateOutput() {
        val fixture = Fixture().apply { connectReady() }
        val old = fixture.factory.sockets.single()
        old.message("""{"type":"response.started","response_id":"r1"}""")
        fixture.transport.cancelResponse()
        old.message("""{"type":"response.text","response_id":"r1","text":"무시"}""")
        old.listener.onMessage(old, byteArrayOf(1, 0).toByteString())
        fixture.transport.connect(RecommendedTopic("topic", "첫 질문"), null, "s1", "trip")
        old.message("""{"type":"session.ready","session_id":"stale"}""")
        old.listener.onFailure(old, IllegalStateException(), null)
        assertTrue(old.cancelled)
        assertEquals("s1", fixture.events.sessionId)
        assertTrue(fixture.events.questions.isEmpty())
        assertTrue(fixture.events.audio.isEmpty())
        assertTrue(fixture.events.failures.isEmpty())
    }

    @Test fun stalledUpload_isBoundedAndStopsConnection() {
        val fixture = Fixture().apply { connectReady() }
        val socket = fixture.factory.sockets.single().apply { queuedBytes = 48_000 }
        assertFalse(fixture.transport.sendAudio(ByteArray(960)))
        assertTrue(socket.cancelled)
        assertEquals(1, fixture.events.failures.size)
        assertFalse(fixture.transport.requestFollowUp())
    }

    @Test fun serverSpeechEvents_requireReady_andClosedConnectionEventsAreIgnored() {
        for (type in listOf("input.speech_started", "input.speech_stopped")) {
            val fixture = Fixture()
            fixture.transport.connect(RecommendedTopic("topic", "첫 질문"), null, null, "trip")
            fixture.factory.sockets.single().apply {
                open()
                message("""{"type":"$type"}""")
                assertTrue(cancelled)
            }
            assertTrue(fixture.events.speech.isEmpty())
            assertEquals(1, fixture.events.failures.size)
        }

        val fixture = Fixture().apply { connectReady() }
        val old = fixture.factory.sockets.single()
        old.message("""{"type":"input.speech_started"}""")
        old.message("""{"type":"input.speech_stopped"}""")
        assertEquals(listOf(true, false), fixture.events.speech)
        fixture.transport.close()
        old.message("""{"type":"input.speech_started"}""")
        old.message("""{"type":"input.speech_stopped"}""")
        assertEquals(listOf(true, false), fixture.events.speech)
        assertTrue(fixture.events.failures.isEmpty())
    }

    @Test fun badConfigurationAndMalformedMessages_reportUnavailableWithoutThrowing() {
        val factory = SocketFactory()
        val events = Events()
        val transport = DrivingConversationTransport("ws://insecure.example", events, factory)
        transport.connect(RecommendedTopic("topic", "첫 질문"), null, null, "trip")
        assertTrue(factory.sockets.isEmpty())
        assertEquals(1, events.failures.size)

        val fixture = Fixture().apply { connectReady() }
        val socket = fixture.factory.sockets.single()
        socket.message("[]")
        assertTrue(socket.cancelled)
        assertEquals(1, fixture.events.failures.size)
    }

    @Test fun missingTripId_neverOpensAVoiceConnection() {
        val fixture = Fixture()
        fixture.transport.connect(RecommendedTopic("topic", "첫 질문"), null, null, " ")
        assertTrue(fixture.factory.sockets.isEmpty())
        assertEquals(1, fixture.events.failures.size)
    }

    private class Fixture {
        val factory = SocketFactory()
        val events = Events()
        val transport = DrivingConversationTransport("wss://voice.example/conversation", events, factory)
        fun connectReady() {
            transport.connect(RecommendedTopic("topic", "첫 질문"), null, null, "trip")
            factory.sockets.last().apply {
                open()
                message("""{"type":"session.ready","session_id":"s1"}""")
            }
        }
    }

    private class Events : DrivingConversationTransport.Listener {
        var sessionId: String? = null
        val questions = mutableListOf<String>()
        val audio = mutableListOf<ByteArray>()
        val failures = mutableListOf<String>()
        val speech = mutableListOf<Boolean>()
        var started = 0
        var done = 0
        override fun onReady(sessionId: String) { this.sessionId = sessionId }
        override fun onQuestion(text: String) { questions += text }
        override fun onAudio(pcm: ByteArray) { audio += pcm }
        override fun onResponseStarted() { started++ }
        override fun onResponseDone() { done++ }
        override fun onFailure(message: String) { failures += message }
        override fun onSpeechChanged(speaking: Boolean) { speech += speaking }
    }

    private class SocketFactory : WebSocket.Factory {
        val sockets = mutableListOf<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { sockets += it }
    }

    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val texts = mutableListOf<String>()
        val audio = mutableListOf<ByteString>()
        val messages = mutableListOf<String>()
        var queuedBytes = 0L
        var cancelled = false
        override fun request(): Request = request
        override fun queueSize(): Long = queuedBytes
        override fun send(text: String): Boolean {
            texts += text
            messages += JsonParser.parseString(text).asJsonObject["type"].asString
            return !cancelled
        }
        override fun send(bytes: ByteString): Boolean { audio += bytes; messages += "audio"; return !cancelled }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() { cancelled = true }
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
        fun message(text: String) = listener.onMessage(this, text)
    }
}
