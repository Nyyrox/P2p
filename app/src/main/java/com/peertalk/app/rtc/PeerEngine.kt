package com.peertalk.app.rtc

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import com.peertalk.app.CallService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Process-wide WebRTC engine. No signaling server: the two SDP blobs are exchanged by the
 * users themselves (see [CodeCodec]). Everything after that - chat, voice, screen video -
 * flows peer to peer. Control messages (chat, "screen on/off", "bye") ride the data channel.
 *
 * Both sides pre-negotiate one audio + one video m-line (sendrecv) plus a data channel, so
 * starting/stopping screen share never needs a new offer/answer round.
 */
class PeerEngine private constructor(private val app: Context) {

    companion object {
        @Volatile private var instance: PeerEngine? = null
        fun get(context: Context): PeerEngine =
            instance ?: synchronized(this) {
                instance ?: PeerEngine(context.applicationContext).also { instance = it }
            }

        private const val GATHER_TIMEOUT_MS = 6_000L
        private val STUN = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
        )
    }

    val eglBase: EglBase = EglBase.create()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    // ---- per-session resources ------------------------------------------------------------
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var gathered = CompletableDeferred<Unit>()
    private var isHost = false

    // =========================================================================================
    // Public API
    // =========================================================================================

    /** Host step 1: create the invite code. */
    fun startHost(useStun: Boolean) {
        if (_state.value.phase != Phase.IDLE) return
        _state.value = UiState(phase = Phase.WORKING)
        CallService.start(app, CallService.ACTION_CALL)
        scope.launch {
            try {
                isHost = true
                val conn = openSession(useStun)
                attachChannel(conn.createDataChannel("peertalk", DataChannel.Init()))
                bindTracksToNewTransceivers(conn)
                val offer = conn.createOfferS()
                conn.setLocalS(offer)
                awaitGathering(conn)
                val local = conn.localDescription ?: error("No local description")
                _state.update { it.copy(phase = Phase.HOST_CODE_READY, code = CodeCodec.encode(local)) }
            } catch (e: Throwable) {
                failSession(e.message ?: "Could not create the connection code.")
            }
        }
    }

    /** Host step 2: apply the guest's reply code. */
    fun acceptReply(code: String) {
        if (_state.value.phase != Phase.HOST_CODE_READY) return
        val conn = pc ?: return
        val desc = try {
            CodeCodec.decode(code).also {
                require(it.type == SessionDescription.Type.ANSWER) {
                    "That is an invite code. Paste the reply code from your friend."
                }
            }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
            return
        }
        _state.update { it.copy(phase = Phase.CONNECTING, error = null) }
        scope.launch {
            try {
                conn.setRemoteS(desc)
            } catch (e: Throwable) {
                failSession(e.message ?: "Could not apply the reply code.")
            }
        }
    }

    /** Guest: apply the host's invite code and produce the reply code. */
    fun join(code: String, useStun: Boolean) {
        if (_state.value.phase != Phase.IDLE) return
        val offer = try {
            CodeCodec.decode(code).also {
                require(it.type == SessionDescription.Type.OFFER) {
                    "That is a reply code. Paste the invite code from the host."
                }
            }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
            return
        }
        _state.value = UiState(phase = Phase.WORKING)
        CallService.start(app, CallService.ACTION_CALL)
        scope.launch {
            try {
                isHost = false
                val conn = openSession(useStun)
                conn.setRemoteS(offer)
                bindTracksToExistingTransceivers(conn)
                val answer = conn.createAnswerS()
                conn.setLocalS(answer)
                awaitGathering(conn)
                val local = conn.localDescription ?: error("No local description")
                _state.update { it.copy(phase = Phase.GUEST_CODE_READY, code = CodeCodec.encode(local)) }
            } catch (e: Throwable) {
                failSession(e.message ?: "Could not use that invite code.")
            }
        }
    }

    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = UUID.randomUUID().toString()
        if (sendControl(JSONObject().put("t", "chat").put("id", id).put("text", t))) {
            _state.update { it.copy(messages = it.messages + ChatMessage(id, t, mine = true)) }
        }
    }

    fun setMicMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        _state.update { it.copy(micMuted = muted) }
    }

    @Suppress("DEPRECATION")
    fun setSpeakerOn(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
        _state.update { it.copy(speakerOn = on) }
    }

    /** [projectionResult] is the Intent returned by MediaProjectionManager's permission screen. */
    fun startScreenShare(projectionResult: Intent) {
        val source = videoSource ?: return
        val track = videoTrack ?: return
        if (_state.value.phase != Phase.CONNECTED || capturer != null) return
        try {
            val cap = ScreenCapturerAndroid(projectionResult, object : MediaProjection.Callback() {
                override fun onStop() {
                    stopScreenShare()
                }
            })
            val helper = SurfaceTextureHelper.create("PeerTalkScreen", eglBase.eglBaseContext)
            cap.initialize(helper, app, source.capturerObserver)

            val dm = app.resources.displayMetrics
            val scale = min(1f, 1280f / max(dm.widthPixels, dm.heightPixels))
            val w = ((dm.widthPixels * scale).toInt() / 2) * 2
            val h = ((dm.heightPixels * scale).toInt() / 2) * 2
            cap.startCapture(w, h, 15)

            capturer = cap
            captureHelper = helper
            track.setEnabled(true)
            _state.update { it.copy(sharingScreen = true, error = null) }
            sendControl(JSONObject().put("t", "screen").put("on", true))
        } catch (e: Throwable) {
            stopScreenShare()
            _state.update { it.copy(error = "Screen share failed: ${e.message}") }
        }
    }

    fun stopScreenShare() {
        val wasSharing = capturer != null
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        try { captureHelper?.dispose() } catch (_: Throwable) {}
        capturer = null
        captureHelper = null
        videoTrack?.setEnabled(false)
        if (wasSharing) sendControl(JSONObject().put("t", "screen").put("on", false))
        _state.update { it.copy(sharingScreen = false) }
        // Drop back to a microphone-only foreground service.
        if (wasSharing && pc != null) {
            try { CallService.start(app, CallService.ACTION_CALL) } catch (_: Throwable) {}
        }
    }

    /** User ended the call / cancelled the setup. */
    fun hangUp() {
        val told = sendControl(JSONObject().put("t", "bye"))
        scope.launch {
            if (told) delay(250) // let the "bye" leave the data channel before closing
            closeSession()
            _state.value = UiState()
        }
    }

    /** Leave the "ended" screen. */
    fun reset() {
        closeSession()
        _state.value = UiState()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // =========================================================================================
    // Session setup / teardown
    // =========================================================================================

    private fun openSession(useStun: Boolean): PeerConnection {
        releaseResources()
        gathered = CompletableDeferred()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true

        val f = factory
        val aSource = f.createAudioSource(MediaConstraints())
        val aTrack = f.createAudioTrack("pt-audio", aSource)
        val vSource = f.createVideoSource(/* isScreencast = */ true)
        val vTrack = f.createVideoTrack("pt-screen", vSource)
        vTrack.setEnabled(false)
        audioSource = aSource; audioTrack = aTrack
        videoSource = vSource; videoTrack = vTrack

        val servers = if (useStun) {
            listOf(PeerConnection.IceServer.builder(STUN).createIceServer())
        } else emptyList()

        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val conn = f.createPeerConnection(config, pcObserver)
            ?: error("Could not create the peer connection.")
        pc = conn
        _state.update { it.copy(micMuted = false, speakerOn = true) }
        return conn
    }

    /** Host side: add the audio + video m-lines ourselves. */
    private fun bindTracksToNewTransceivers(conn: PeerConnection) {
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
            .sender.setTrack(audioTrack, false)
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
            .sender.setTrack(videoTrack, false)
    }

    /** Guest side: the m-lines already exist (from the offer); attach our tracks and send too. */
    private fun bindTracksToExistingTransceivers(conn: PeerConnection) {
        for (t in conn.transceivers) {
            t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> t.sender.setTrack(audioTrack, false)
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> t.sender.setTrack(videoTrack, false)
                else -> {}
            }
        }
    }

    private suspend fun awaitGathering(conn: PeerConnection) {
        if (conn.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
        withTimeoutOrNull(GATHER_TIMEOUT_MS) { gathered.await() }
    }

    private fun failSession(message: String) {
        closeSession()
        _state.value = UiState(error = message)
    }

    private fun endSession(reason: String) {
        closeSession()
        _state.value = UiState(phase = Phase.ENDED, endedReason = reason)
    }

    private fun closeSession() {
        releaseResources()
        app.stopService(Intent(app, CallService::class.java))
    }

    private fun releaseResources() {
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        try { captureHelper?.dispose() } catch (_: Throwable) {}
        capturer = null; captureHelper = null

        _remoteVideo.value = null

        try { channel?.unregisterObserver() } catch (_: Throwable) {}
        try { channel?.close() } catch (_: Throwable) {}
        try { channel?.dispose() } catch (_: Throwable) {}
        channel = null

        try { pc?.close() } catch (_: Throwable) {}
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null

        try { videoTrack?.dispose() } catch (_: Throwable) {}
        try { videoSource?.dispose() } catch (_: Throwable) {}
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        videoTrack = null; videoSource = null; audioTrack = null; audioSource = null

        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
    }

    // =========================================================================================
    // Data channel
    // =========================================================================================

    private fun attachChannel(dc: DataChannel?) {
        dc ?: return
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleControl(String(bytes, Charsets.UTF_8))
            }
        })
    }

    private fun sendControl(json: JSONObject): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val data = ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
        return dc.send(DataChannel.Buffer(data, false))
    }

    private fun handleControl(raw: String) {
        try {
            val j = JSONObject(raw)
            when (j.getString("t")) {
                "chat" -> {
                    val msg = ChatMessage(j.getString("id"), j.getString("text"), mine = false)
                    _state.update { it.copy(messages = it.messages + msg) }
                }
                "screen" -> _state.update { it.copy(remoteSharing = j.getBoolean("on")) }
                "bye" -> endSession("The other person left.")
            }
        } catch (_: Throwable) {
            // Ignore malformed control messages.
        }
    }

    // =========================================================================================
    // PeerConnection observer
    // =========================================================================================

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onRenegotiationNeeded() {}

        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }

        override fun onDataChannel(dc: DataChannel?) {
            // Guest receives the host's channel here.
            attachChannel(dc)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            if (track is VideoTrack) {
                track.setEnabled(true)
                _remoteVideo.value = track
            }
        }

        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED ->
                    _state.update { it.copy(phase = Phase.CONNECTED, linkUnstable = false, code = "", error = null) }
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    _state.update { it.copy(linkUnstable = true) }
                PeerConnection.PeerConnectionState.FAILED ->
                    if (_state.value.phase == Phase.CONNECTED) endSession("The connection was lost.")
                    else failSession("Could not connect. Check both devices are online and try again.")
                else -> {}
            }
        }
    }

    // =========================================================================================
    // SDP helpers
    // =========================================================================================

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private suspend fun PeerConnection.createOfferS(): SessionDescription =
        suspendCancellableCoroutine { c ->
            createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(d: SessionDescription?) { c.resume(d!!) }
                override fun onCreateFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.createAnswerS(): SessionDescription =
        suspendCancellableCoroutine { c ->
            createAnswer(object : SdpAdapter() {
                override fun onCreateSuccess(d: SessionDescription?) { c.resume(d!!) }
                override fun onCreateFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.setLocalS(d: SessionDescription) =
        suspendCancellableCoroutine<Unit> { c ->
            setLocalDescription(object : SdpAdapter() {
                override fun onSetSuccess() { c.resume(Unit) }
                override fun onSetFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
            }, d)
        }

    private suspend fun PeerConnection.setRemoteS(d: SessionDescription) =
        suspendCancellableCoroutine<Unit> { c ->
            setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() { c.resume(Unit) }
                override fun onSetFailure(e: String?) { c.resumeWithException(IllegalStateException(e)) }
            }, d)
        }
}
