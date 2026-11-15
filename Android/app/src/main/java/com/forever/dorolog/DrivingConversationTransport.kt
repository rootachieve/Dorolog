package com.forever.dorolog

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Backend contract v1 (WSS only; this app contains no provider credentials).
 * Client JSON: session.start { protocol_version:1, session_id?:string, topic_id:string,
 * question:string, destination_name?:string, trip_id:string,
 * current_address?:string, latitude?:number, longitude?:number,
 * audio:{encoding:"pcm_s16le",sample_rate_hz:24000,
 * channels:1,frame_duration_ms:20} }; response.create { reason:"silence",silence_ms:5000 };
 * response.cancel {}. Client binary messages are microphone PCM (normally 960 bytes / 20 ms).
 * location.changed {trip_id:string,current_address:string|null,
 * latitude:number|null,longitude:number|null} precedes PCM recorded at the updated location.
 * Null clears an unavailable value. SDK coordinates are sent only as a valid pair.
 * The server associates all PCM with the connection's trip and latest location.
 * A new input speech event must also cancel a pending silence response before response.started,
 * including generation already accepted by the server; cancelled output must never be emitted.
 * Server JSON: session.ready {session_id:string}; response.started {response_id:string};
 * response.text {response_id:string,text:string}; response.done {response_id:string}; error {}.
 * While receiving microphone PCM, the server must use authoritative voice activity detection
 * that distinguishes speech from vehicle/road noise and emit paired input.speech_started {}
 * and input.speech_stopped {} events after session.ready. The client suppresses its silence
 * timer while speech is active and requests a follow-up 5 seconds after speech stops.
 * If response.create arrives while server VAD detects speech, decline it and resend
 * input.speech_started instead of starting an assistant response. Input capture continues
 * until response.started acknowledges acceptance, so in-flight speech is not clipped.
 * response.text contains the latest complete question, not a delta. Text-only responses are
 * spoken by Pleos TTS after response.done. Optional server binary PCM uses the same format
 * and belongs to the current response; when present, it replaces local TTS. The server must
 * stop a cancelled response before starting another, and send done only after its final PCM.
 * Pace PCM near real time (at most one second ahead); the client has a bounded two-second queue.
 * Responses are half-duplex: microphone capture resumes only after speaker output drains.
 * Disconnect cancels the current response. A resumed session retains history on the server,
 * including cancelled turns, but must not replay abandoned output from the old connection.
 */
internal class DrivingConversationTransport(
    private val endpoint: String,
    private val listener: Listener,
    private val socketFactory: WebSocket.Factory = client,
) {
    interface Listener {
        fun onReady(sessionId: String)
        fun onQuestion(text: String)
        fun onAudio(pcm: ByteArray)
        fun onResponseStarted()
        fun onResponseDone()
        fun onFailure(message: String)
        fun onSpeechChanged(speaking: Boolean) = Unit
    }

    private val lock = Any()
    private val gson = Gson()
    private var socket: WebSocket? = null
    private var generation = 0
    private var ready = false
    private var responseId: String? = null
    private var tripId: String? = null
    private var location = VehicleLocation()
    private var sentLocation = VehicleLocation()

    fun connect(topic: RecommendedTopic, destination: Destination?, sessionId: String?,
        tripId: String, currentAddress: String? = null,
        latitude: Double? = null, longitude: Double? = null) {
        synchronized(lock) {
            closeLocked()
            this.tripId = tripId
            location = validLocation(VehicleLocation(currentAddress, latitude, longitude))
            val connection = generation
            val request = try {
                require(tripId.isNotBlank())
                val uri = URI(endpoint)
                require(uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
                    uri.userInfo == null && uri.fragment == null)
                Request.Builder().url(endpoint).build()
            } catch (_: Exception) {
                listener.onFailure("음성 서버 주소를 확인해 주세요")
                return
            }
            try {
                socket = socketFactory.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        synchronized(lock) {
                            if (connection != generation) return
                            val start = gson.toJsonTree(mapOf(
                                "type" to "session.start", "protocol_version" to 1,
                                "session_id" to sessionId, "topic_id" to topic.id,
                                "question" to topic.question, "destination_name" to destination?.name,
                                "trip_id" to tripId,
                                "audio" to mapOf("encoding" to "pcm_s16le", "sample_rate_hz" to 24_000,
                                    "channels" to 1, "frame_duration_ms" to 20),
                            ).filterValues { it != null }).asJsonObject.apply {
                                addProperty("current_address", location.address)
                                addProperty("latitude", location.latitude)
                                addProperty("longitude", location.longitude)
                            }
                            if (!webSocket.send(start.toString())) failLocked("음성 서버에 연결할 수 없어요")
                            else sentLocation = location
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        synchronized(lock) {
                            if (connection != generation) return
                            val message = try {
                                require(text.length <= 16_384)
                                JsonParser.parseString(text).asJsonObject
                            } catch (_: Exception) {
                                failLocked("음성 서버 응답을 확인할 수 없어요")
                                return
                            }
                            handleMessage(message)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        synchronized(lock) {
                            if (connection != generation || responseId == null) return
                            if (bytes.size == 0 || bytes.size > 24_000 || bytes.size % 2 != 0) {
                                failLocked("음성 서버 오디오 형식을 확인할 수 없어요")
                                return
                            }
                            listener.onAudio(bytes.toByteArray())
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        synchronized(lock) {
                            if (connection == generation) failLocked("음성 서버 연결이 종료됐어요")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        synchronized(lock) {
                            if (connection == generation) failLocked("음성 서버에 연결할 수 없어요")
                        }
                    }
                })
            } catch (_: Exception) {
                failLocked("음성 서버에 연결할 수 없어요")
            }
        }
    }

    fun updateLocation(location: VehicleLocation) {
        synchronized(lock) {
            this.location = validLocation(location)
            if (ready) sendLocationIfChanged()
        }
    }

    private fun sendLocationIfChanged(): Boolean {
        if (sentLocation == location) return true
        val message = JsonObject().apply {
            addProperty("type", "location.changed")
            tripId?.let { addProperty("trip_id", it) }
            addProperty("current_address", location.address)
            addProperty("latitude", location.latitude)
            addProperty("longitude", location.longitude)
        }
        if (!sendControl(message.toString())) return false
        sentLocation = location
        return true
    }

    private fun validLocation(location: VehicleLocation): VehicleLocation {
        val validCoordinates = location.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            location.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
        return VehicleLocation(
            location.address?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1_000 },
            location.latitude.takeIf { validCoordinates }, location.longitude.takeIf { validCoordinates },
        )
    }

    fun sendAudio(pcm: ByteArray): Boolean = synchronized(lock) {
        if (!ready || pcm.isEmpty() || pcm.size > 960 || pcm.size % 2 != 0) return false
        val connection = socket ?: return false
        if (connection.queueSize() + pcm.size > MAX_QUEUED_BYTES || !connection.send(pcm.toByteString())) {
            failLocked("음성 서버 연결이 지연되고 있어요. 다시 시작해 주세요")
            return false
        }
        true
    }

    fun requestFollowUp(): Boolean = synchronized(lock) {
        if (!ready || responseId != null) return false
        sendControl(mapOf("type" to "response.create", "reason" to "silence", "silence_ms" to 5_000))
    }

    fun cancelResponse() {
        synchronized(lock) {
            responseId = null
            if (ready) sendControl(mapOf("type" to "response.cancel"))
        }
    }

    fun close() { synchronized(lock) { closeLocked() } }

    private fun handleMessage(message: JsonObject) {
        when (message.string("type")) {
            "session.ready" -> {
                val id = message.string("session_id")
                if (ready || id == null || id.length > 256) {
                    failLocked("음성 대화를 시작할 수 없어요")
                    return
                }
                ready = true
                if (!sendLocationIfChanged()) return
                listener.onReady(id)
            }
            "input.speech_started", "input.speech_stopped" -> {
                if (!ready) {
                    failLocked("음성 서버 발화 상태를 확인할 수 없어요")
                    return
                }
                listener.onSpeechChanged(message.string("type") == "input.speech_started")
            }
            "response.started" -> {
                val id = message.string("response_id")
                if (!ready || responseId != null || id == null || id.length > 256) {
                    failLocked("음성 서버 응답 순서를 확인할 수 없어요")
                    return
                }
                responseId = id
                listener.onResponseStarted()
            }
            "response.text" -> {
                if (responseId == null || message.string("response_id") != responseId) return
                val text = message.string("text")
                if (text == null || text.length > 4_000) {
                    failLocked("음성 서버 질문을 확인할 수 없어요")
                    return
                }
                listener.onQuestion(text)
            }
            "response.done" -> {
                if (responseId == null || message.string("response_id") != responseId) return
                responseId = null
                listener.onResponseDone()
            }
            "error" -> failLocked("음성 서버에서 대화를 처리할 수 없어요")
            else -> failLocked("음성 서버 응답 형식을 확인할 수 없어요")
        }
    }

    private fun sendControl(message: Map<String, Any>): Boolean = sendControl(gson.toJson(message))

    private fun sendControl(text: String): Boolean {
        val connection = socket ?: return false
        if (connection.queueSize() + text.toByteArray(Charsets.UTF_8).size > MAX_QUEUED_BYTES || !connection.send(text)) {
            failLocked("음성 서버에 요청을 보낼 수 없어요")
            return false
        }
        return true
    }

    private fun failLocked(message: String) {
        closeLocked()
        listener.onFailure(message)
    }

    private fun closeLocked() {
        generation++
        ready = false
        responseId = null
        socket?.cancel()
        socket = null
    }

    private fun JsonObject.string(name: String): String? = get(name)?.let {
        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.takeIf(String::isNotBlank) else null
    }

    companion object {
        private const val MAX_QUEUED_BYTES = 48_000L
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS).build()
    }
}
