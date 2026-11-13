package com.forever.dorolog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

internal class DrivingAudioSession(
    context: Context,
    private val endpoint: String = BuildConfig.DRIVING_VOICE_URL,
) : DrivingRuntime {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val telecom = app.getSystemService(TelecomManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: DrivingRuntime.Listener? = null
    private var closed = false
    @Volatile private var active = false
    @Volatile private var focusHeld = false
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var generation = 0
    private var focusGeneration = 0
    private var topic: RecommendedTopic? = null
    private var destination: Destination? = null
    private var sessionId: String? = null
    private var tripId: String? = null
    private var location = VehicleLocation()
    private var transport: DrivingConversationTransport? = null
    private var serverReady = false
    private var responseActive = false
    private var followUpPending = false
    private var responseReceived = false
    private var responseTimeout: Job? = null
    private var connectionTimeout: Job? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackQueue: Channel<ByteArray>? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    private var phoneListener: PhoneStateListener? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var monitorJob: Job? = null
    private var monitorGeneration = 0
    private var telecomAvailable = false
    private var telecomBlocked = false
    private var telephonyAvailable = false
    private var telephonyBlocked = false
    private var externalCallAvailable = false
    private var externalCallBlocked = false
    private var callAvailable = false
    private var callBlocked = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun observe(listener: DrivingRuntime.Listener) {
        this.listener = listener
        if (closed) return
        refreshCallState()
    }

    @Suppress("DEPRECATION")
    override fun setForeground(foreground: Boolean) {
        if (closed) return
        if (!foreground) {
            monitorGeneration++
            monitorJob?.cancel()
            monitorJob = null
            if (Build.VERSION.SDK_INT >= 31) modeListener?.let { runCatching { audio.removeOnModeChangedListener(it) } }
            modeListener = null
            phoneListener?.let { runCatching { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) } }
            phoneListener = null
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && modeListener == null) {
            val monitor = monitorGeneration
            val callback = AudioManager.OnModeChangedListener {
                if (monitor == monitorGeneration) refreshCallState()
            }
            runCatching { audio.addOnModeChangedListener(app.mainExecutor, callback) }
                .onSuccess { modeListener = callback }
        }
        if (monitorJob == null) {
            monitorJob = scope.launch {
                while (isActive) {
                    refreshCallState()
                    if (active && !microphoneAvailable()) fail("마이크 권한 또는 개인정보 보호 설정을 확인해 주세요")
                    delay(500)
                }
            }
        }
        refreshCallState()
    }

    // Only an authoritative vehicle call provider may supply this; audio focus never identifies calls.
    fun updateCallState(blocked: Boolean, available: Boolean) {
        externalCallBlocked = blocked
        externalCallAvailable = available
        refreshCallState()
    }

    @Suppress("DEPRECATION", "MissingPermission")
    override fun refreshCallState() {
        if (closed) return
        val hasTelephony = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasTelecom = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
        val permitted = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        telecomAvailable = false
        telephonyAvailable = false
        if (hasTelecom && permitted && telecom != null) {
            runCatching { telecom.isInCall }
                .onSuccess { telecomBlocked = it; telecomAvailable = true }
        }
        if (hasTelephony && permitted && telephony != null) {
            try {
                telephonyBlocked = telephony.callState != TelephonyManager.CALL_STATE_IDLE
                if (phoneListener == null && monitorJob != null) {
                    val monitor = monitorGeneration
                    val callback = object : PhoneStateListener() {
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            if (closed || monitor != monitorGeneration) return
                            telephonyBlocked = state != TelephonyManager.CALL_STATE_IDLE
                            publishCallState()
                        }
                    }
                    telephony.listen(callback, PhoneStateListener.LISTEN_CALL_STATE)
                    phoneListener = callback
                }
                telephonyAvailable = true
            } catch (_: RuntimeException) {
                telephonyAvailable = false
            }
        } else if ((!hasTelephony || !permitted) && phoneListener != null) {
            runCatching { telephony?.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
            phoneListener = null
        }
        publishCallState()
    }

    private fun publishCallState() {
        val mode = runCatching { audio.mode }.getOrNull()
        val available = mode != null && (telecomAvailable || telephonyAvailable || externalCallAvailable)
        val blocked = (telecomAvailable && telecomBlocked) || (telephonyAvailable && telephonyBlocked) ||
            (externalCallAvailable && externalCallBlocked) || (mode != null && mode != AudioManager.MODE_NORMAL)
        val changed = available != callAvailable || blocked != callBlocked
        callAvailable = available
        callBlocked = blocked
        if (active && (blocked || !available)) stop()
        if (changed || !active) listener?.onCallState(blocked, available)
    }

    override fun start(topic: RecommendedTopic, destination: Destination?, sessionId: String?,
        tripId: String) {
        if (closed) return
        stop()
        this.topic = topic
        this.destination = destination
        this.sessionId = sessionId
        this.tripId = tripId
        refreshCallState()
        if (!callAvailable || callBlocked) {
            listener?.onUnavailable(if (callBlocked) "통화가 끝나면 재개할 수 있어요" else "통화 상태를 확인할 수 없어요")
            return
        }
        if (!microphoneAvailable()) {
            listener?.onUnavailable("마이크 권한 또는 개인정보 보호 설정을 확인해 주세요")
            return
        }
        active = true
        val requestGeneration = ++focusGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (active && focusGeneration == requestGeneration && focusRequest != null) handleFocus(change)
            }, main)
            .build()
        focusRequest = request
        val result = runCatching { audio.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                focusHeld = true
                startListening()
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> listener?.onWaitingForAudio(true)
            else -> fail("다른 오디오를 사용 중이에요. 잠시 후 재개해 주세요")
        }
    }

    override fun updateLocation(location: VehicleLocation) {
        this.location = location
        transport?.updateLocation(location)
    }

    private fun handleFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                refreshCallState()
                if (active && callAvailable && !callBlocked) {
                    focusHeld = true
                    listener?.onWaitingForAudio(false)
                    startListening()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                refreshCallState()
                if (!active) return
                focusHeld = false
                suspendAudio()
                listener?.onWaitingForAudio(true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> fail("오디오 사용이 중단됐어요. 다시 재개해 주세요")
        }
    }

    private fun startListening() {
        if (!active || !focusHeld || transport != null || captureJob?.isActive == true || responseActive) return
        if (!microphoneAvailable()) { fail("마이크 권한 또는 개인정보 보호 설정을 확인해 주세요"); return }
        val current = generation
        if (endpoint.isBlank()) {
            listener?.onServerUnavailable("대화 서버가 설정되지 않았어요. 마이크 입력만 확인할 수 있어요")
            startCapture(current)
        } else {
            listener?.onServerUnavailable("대화 서버에 연결하고 있어요")
            val pendingCallbacks = AtomicInteger()
            val overflowed = AtomicBoolean()
            val socket = DrivingConversationTransport(endpoint, object : DrivingConversationTransport.Listener {
                private fun dispatch(block: () -> Unit) {
                    if (overflowed.get()) return
                    val pending = pendingCallbacks.incrementAndGet()
                    if (pending > 100) {
                        pendingCallbacks.decrementAndGet()
                        if (overflowed.compareAndSet(false, true)) main.post {
                            if (active && current == generation) serverFailure("서버 응답 버퍼가 가득 찼어요")
                        }
                        return
                    }
                    main.post {
                        pendingCallbacks.decrementAndGet()
                        if (active && current == generation && focusHeld) block()
                    }
                }
                override fun onReady(sessionId: String) = dispatch {
                    this@DrivingAudioSession.sessionId = sessionId
                    serverReady = true
                    connectionTimeout?.cancel()
                    listener?.onSessionReady(sessionId)
                    startCapture(current)
                }
                override fun onQuestion(text: String) = dispatch { listener?.onQuestion(text) }
                override fun onSpeechChanged(speaking: Boolean) = dispatch {
                    if (speaking && !responseActive) {
                        if (followUpPending) transport?.cancelResponse()
                        followUpPending = false
                        responseTimeout?.cancel()
                    }
                    listener?.onSpeechChanged(speaking)
                }
                override fun onResponseStarted() = dispatch { beginResponse(current, received = true) }
                override fun onAudio(pcm: ByteArray) = dispatch { enqueueAudio(pcm, current) }
                override fun onResponseDone() = dispatch {
                    responseTimeout?.cancel()
                    if (playbackQueue == null) finishResponse(current) else playbackQueue?.close()
                }
                override fun onFailure(message: String) = dispatch { serverFailure(message) }
            })
            transport = socket
            socket.connect(requireNotNull(topic), destination, sessionId, requireNotNull(tripId),
                location.address, location.latitude, location.longitude)
            connectionTimeout = scope.launch {
                delay(15_000)
                if (active && current == generation && !serverReady) serverFailure("대화 서버 연결 시간이 초과됐어요")
            }
        }
    }

    private fun microphoneAvailable(): Boolean {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (runCatching { audio.isMicrophoneMute }.getOrDefault(true)) return false
        return true
    }

    @Suppress("MissingPermission")
    private fun startCapture(current: Int) {
        if (!active || !focusHeld || responseActive || captureJob?.isActive == true) return
        captureJob = scope.launch(Dispatchers.IO) {
            var capture: AudioRecord? = null
            var privacyCallback: AudioManager.AudioRecordingCallback? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                capture = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, FRAME_SAMPLES * 8))
                    .build()
                check(capture.state == AudioRecord.STATE_INITIALIZED)
                val local = capture
                if (Build.VERSION.SDK_INT >= 29) {
                    privacyCallback = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            if (configs.any { it.clientAudioSessionId == local.audioSessionId && it.isClientSilenced }) {
                                main.post { if (active && current == generation && recorder === local) fail("시스템이 마이크 입력을 차단했어요") }
                            }
                        }
                    }
                    local.registerAudioRecordingCallback(app.mainExecutor, privacyCallback)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (active && current == generation && focusHeld && !responseActive) {
                        recorder = local
                        local.startRecording()
                        check(local.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    } else throw CancellationException()
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    var attempts = 0
                    while (local.activeRecordingConfiguration == null && attempts++ < 10) delay(50)
                    val configuration = local.activeRecordingConfiguration
                    check(configuration != null && !configuration.isClientSilenced)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (active && current == generation && recorder === local && !responseActive) {
                        listener?.onCaptureChanged(true)
                    } else throw CancellationException()
                }
                val samples = ShortArray(FRAME_SAMPLES)
                while (isActive && current == generation) {
                    val count = local.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    check(count > 0)
                    val pcm = ByteArray(count * 2)
                    var squareSum = 0.0
                    for (index in 0 until count) {
                        val value = samples[index].toInt()
                        pcm[index * 2] = value.toByte()
                        pcm[index * 2 + 1] = (value shr 8).toByte()
                        squareSum += value.toDouble() * value
                    }
                    val rms = sqrt(squareSum / count)
                    val amplitude = if (rms < 1) 0 else (((20 * log10(rms / 32768) + 60) / 60) * 100).toInt().coerceIn(0, 100)
                    withContext(Dispatchers.Main.immediate) {
                        if (active && current == generation && recorder === local && !responseActive) {
                            listener?.onAmplitude(amplitude, speech = false)
                            if (serverReady && transport?.sendAudio(pcm) == false) serverFailure("대화 서버 전송이 지연됐어요. 재개하면 다시 연결해요")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                withContext(Dispatchers.Main.immediate) {
                    if (active && current == generation && !responseActive) fail("마이크를 사용할 수 없어요. 권한과 연결을 확인해 주세요")
                }
            } finally {
                if (Build.VERSION.SDK_INT >= 29 && privacyCallback != null) runCatching { capture?.unregisterAudioRecordingCallback(privacyCallback) }
                runCatching { capture?.stop() }
                runCatching { capture?.release() }
            }
        }
    }

    override fun requestFollowUp(): Boolean {
        if (!active || !focusHeld || !serverReady || responseActive || followUpPending) return false
        if (transport?.requestFollowUp() != true) return false
        followUpPending = true
        awaitResponse(generation)
        return true
    }

    private fun beginResponse(current: Int, received: Boolean = false) {
        if (!active || current != generation) return
        if (received && responseReceived) { serverFailure("이전 음성 재생이 끝나기 전에 다음 응답이 도착했어요"); return }
        if (received) responseReceived = true
        followUpPending = false
        responseActive = true
        stopCapture()
        listener?.onResponseStarted()
        awaitResponse(current)
    }

    private fun awaitResponse(current: Int) {
        responseTimeout?.cancel()
        responseTimeout = scope.launch {
            delay(30_000)
            if (active && current == generation && (responseActive || followUpPending)) serverFailure("서버 응답이 지연됐어요. 재개하면 다시 연결해요")
        }
    }

    private fun enqueueAudio(pcm: ByteArray, current: Int) {
        if (!responseActive || pcm.isEmpty() || pcm.size % 2 != 0) { serverFailure("서버 음성 형식을 확인해 주세요"); return }
        awaitResponse(current)
        if (playbackQueue == null) {
            val queue = Channel<ByteArray>(100)
            playbackQueue = queue
            playbackJob = scope.launch(Dispatchers.IO) { playResponse(queue, current) }
        }
        val queue = playbackQueue ?: return
        for (offset in pcm.indices step FRAME_SAMPLES * 2) {
            if (!queue.trySend(pcm.copyOfRange(offset, minOf(offset + FRAME_SAMPLES * 2, pcm.size))).isSuccess) {
                serverFailure("서버 음성 버퍼가 가득 찼어요. 재개하면 다시 연결해요")
                return
            }
        }
    }

    private suspend fun playResponse(queue: Channel<ByteArray>, current: Int) {
        var track: AudioTrack? = null
        try {
            val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, FRAME_SAMPLES * 8)).build()
            check(track.state == AudioTrack.STATE_INITIALIZED)
            val local = track
            withContext(Dispatchers.Main.immediate) {
                if (!active || !focusHeld || current != generation) throw CancellationException()
                player = local
                if (Build.VERSION.SDK_INT >= 31) local.setStartThresholdInFrames(1)
                local.play()
                listener?.onPlaybackChanged(true)
            }
            var writtenFrames = 0L
            for (pcm in queue) {
                var offset = 0
                while (offset < pcm.size) {
                    check(current == generation && active && focusHeld)
                    val count = local.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0)
                    offset += count
                    writtenFrames += count / 2
                }
            }
            if (Build.VERSION.SDK_INT < 31 && writtenFrames < local.bufferSizeInFrames) {
                val padding = ByteArray((local.bufferSizeInFrames - writtenFrames.toInt()) * 2)
                var offset = 0
                while (offset < padding.size) {
                    val count = local.write(padding, offset, padding.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0)
                    offset += count
                }
            }
            val remainingFrames = writtenFrames - (local.playbackHeadPosition.toLong() and 0xffffffffL)
            check(withTimeoutOrNull(maxOf(remainingFrames * 1_000 / SAMPLE_RATE, 0) + 2_000) {
                while ((local.playbackHeadPosition.toLong() and 0xffffffffL) < writtenFrames) {
                    check(current == generation && active && focusHeld)
                    delay(10)
                }
                true
            } == true)
            withContext(Dispatchers.Main.immediate) { finishResponse(current) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            withContext(Dispatchers.Main.immediate) {
                if (active && current == generation) fail("음성을 재생할 수 없어요. 오디오 연결을 확인해 주세요")
            }
        } finally {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
        }
    }

    private fun finishResponse(current: Int) {
        if (!active || current != generation) return
        responseActive = false
        followUpPending = false
        responseReceived = false
        responseTimeout?.cancel()
        playbackQueue = null
        player = null
        listener?.onPlaybackChanged(false)
        listener?.onResponseFinished()
        startCapture(current)
    }

    private fun serverFailure(message: String) {
        suspendAudio()
        listener?.onServerUnavailable(message)
        startCapture(generation)
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        val oldRecorder = recorder
        recorder = null
        runCatching { oldRecorder?.stop() }
        listener?.onCaptureChanged(false)
    }

    private fun stopPlayback() {
        playbackQueue?.cancel()
        playbackQueue = null
        playbackJob?.cancel()
        playbackJob = null
        val oldPlayer = player
        player = null
        runCatching { oldPlayer?.pause() }
        runCatching { oldPlayer?.flush() }
    }

    private fun suspendAudio() {
        generation++
        transport?.cancelResponse()
        transport?.close()
        transport = null
        serverReady = false
        responseActive = false
        followUpPending = false
        responseReceived = false
        responseTimeout?.cancel()
        connectionTimeout?.cancel()
        stopCapture()
        stopPlayback()
    }

    override fun stop() {
        focusGeneration++
        active = false
        focusHeld = false
        suspendAudio()
        focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun fail(message: String) {
        stop()
        listener?.onUnavailable(message)
    }

    @Suppress("DEPRECATION")
    override fun close() {
        if (closed) return
        stop()
        closed = true
        phoneListener?.let { runCatching { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) } }
        if (Build.VERSION.SDK_INT >= 31) modeListener?.let { runCatching { audio.removeOnModeChangedListener(it) } }
        scope.cancel()
        listener = null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val FRAME_SAMPLES = 480
    }
}
