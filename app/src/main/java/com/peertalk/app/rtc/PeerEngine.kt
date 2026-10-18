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
import org.webrtc.RtpSender
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
 * users themselves (see [CodeCodec]). Everything after that flows peer to peer.
 *
 * A session starts as CHAT ONLY. The microphone is never opened (and no permission is needed)
 * until both people agree to a voice call:
 *
 *   caller: [requestCall]  ->  "call_req"
 *   callee: [acceptCall] / [declineCall]  ->  "call_accept" / "call_decline"
 *   either: [endCall] -> "call_end" (back to chat, session stays open)
 *
 * The UI calls [beginAudio] from the call screen once it has asked for the mic permission.
 *
 * Both sides pre-negotiate one audio + one video m-line (sendrecv) plus a data channel. The audio
 * sender simply has no track until the call starts, so no renegotiation is ever needed.
 */
class PeerEngine private constructor(private val app: Context) {

    companion object {
        @Volatile private var instance: PeerEngine? = null
        fun get(context: Context): PeerEngine =
            instance ?: synchronized(this) {
                instance ?: PeerEngine(context.applicationContext).also { instance = it }
            }

        private const val GATHER_TIMEOUT_MS = 6_000L
        private const val CALL_TIMEOUT_MS = 45_000L
        private val STUN = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
        )
    }

    val eglBase: EglBase = EglBase.create()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val prefs = app.getSharedPreferences("peertalk", Context.MODE_PRIVATE)
    private var myName: String = prefs.getString("name", "") ?: ""

    private val _state = MutableStateFlow(UiState(myName = myName))
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
    private var audioSender: RtpSender? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioModeSet = false
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var gathered = CompletableDeferred<Unit>()
    @Volatile private var callToken = 0

    // =========================================================================================
    // Names
    // =========================================================================================

    private fun cleanName(raw: String) =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)

    private fun rememberName(raw: String) {
        val n = cleanName(raw)
        if (n.isEmpty()) return
        myName = n
        prefs.edit().putString("name", n).apply()
        _state.update { it.copy(myName = n) }
    }

    /** Change our display name. The other person sees the update immediately. */
    fun setMyName(raw: String) {
        val n = cleanName(raw)
        if (n.isEmpty() || n == myName) return
        rememberName(n)
        sendHello()
    }

    private fun sendHello() {
        if (myName.isNotEmpty()) sendControl(JSONObject().put("t", "hello").put("name", myName))
    }

    private fun blank(phase: Phase = Phase.IDLE, error: String? = null, endedReason: String? = null) =
        UiState(phase = phase, error = error, endedReason = endedReason, myName = myName)

    private fun sys(text: String) =
        ChatMessage(UUID.randomUUID().toString(), text, mine = false, system = true)

    // =========================================================================================
    // Connection setup (code exchange)
    // =========================================================================================

    /** Host step 1: create the invite code. */
    fun startHost(name: String, useStun: Boolean) {
        if (_state.value.phase != Phase.IDLE) return
        rememberName(name)
        _state.value = blank(Phase.WORKING)
        updateService(CallService.ACTION_SESSION)
        scope.launch {
            try {
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
    fun join(code: String, name: String, useStun: Boolean) {
        if (_state.value.phase != Phase.IDLE) return
        rememberName(name)
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
        _state.value = blank(Phase.WORKING)
        updateService(CallService.ACTION_SESSION)
        scope.launch {
            try {
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

    // =========================================================================================
    // Chat
    // =========================================================================================

    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = UUID.randomUUID().toString()
        if (sendControl(JSONObject().put("t", "chat").put("id", id).put("text", t))) {
            _state.update { it.copy(messages = it.messages + ChatMessage(id, t, mine = true)) }
        }
    }

    // =========================================================================================
    // Calling (optional, needs the other person to accept)
    // =========================================================================================

    /** Ask the other person to start a voice call. Nothing about audio is touched yet. */
    fun requestCall() {
        val s = _state.value
        if (s.phase != Phase.CONNECTED || s.callPhase != CallPhase.NONE) return
        if (!sendControl(JSONObject().put("t", "call_req"))) {
            _state.update { it.copy(error = "Can't reach your friend right now.") }
            return
        }
        _state.update { it.copy(callPhase = CallPhase.OUTGOING, error = null) }
        val token = ++callToken
        scope.launch {
            delay(CALL_TIMEOUT_MS)
            if (token == callToken && _state.value.callPhase == CallPhase.OUTGOING) {
                sendControl(JSONObject().put("t", "call_cancel"))
                _state.update { it.copy(callPhase = CallPhase.NONE, messages = it.messages + sys("No answer")) }
            }
        }
    }

    fun cancelCall() {
        if (_state.value.callPhase != CallPhase.OUTGOING) return
        callToken++
        sendControl(JSONObject().put("t", "call_cancel"))
        _state.update { it.copy(callPhase = CallPhase.NONE) }
    }

    fun acceptCall() {
        if (_state.value.callPhase != CallPhase.INCOMING) return
        sendControl(JSONObject().put("t", "call_accept"))
        activateCall()
    }

    fun declineCall() {
        if (_state.value.callPhase != CallPhase.INCOMING) return
        sendControl(JSONObject().put("t", "call_decline"))
        _state.update { it.copy(callPhase = CallPhase.NONE, messages = it.messages + sys("You declined a call")) }
    }

    /** End the call and go back to the chat. The session itself stays open. */
    fun endCall() {
        if (_state.value.callPhase != CallPhase.ACTIVE) return
        sendControl(JSONObject().put("t", "call_end"))
        finishCall("Call ended")
    }

    private fun activateCall() {
        _state.update { it.copy(callPhase = CallPhase.ACTIVE, callStartedAt = System.currentTimeMillis(), error = null) }
    }

    private fun finishCall(label: String) {
        val s = _state.value
        val duration = if (s.callStartedAt > 0) formatDuration(System.currentTimeMillis() - s.callStartedAt) else null
        stopScreenShare()
        endAudio()
        _state.update {
            it.copy(
                callPhase = CallPhase.NONE,
                callStartedAt = 0L,
                remoteSharing = false,
                messages = it.messages + sys(if (duration != null) "$label · $duration" else label),
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val t = ms / 1000
        return "%02d:%02d".format(t / 60, t % 60)
    }

    /**
     * Called by the call screen. [micGranted] is whatever the user answered on the permission
     * prompt. Without it we still route audio so the person can listen, but we do not send.
     * Safe to call repeatedly (e.g. after the user grants the mic later).
     */
    fun beginAudio(micGranted: Boolean) {
        if (_state.value.callPhase != CallPhase.ACTIVE || pc == null) return
        var changed = false
        if (!audioModeSet) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = _state.value.speakerOn
            audioModeSet = true
            changed = true
        }
        if (micGranted && audioTrack == null) {
            val src = factory.createAudioSource(MediaConstraints())
            val track = factory.createAudioTrack("pt-audio", src)
            audioSource = src
            audioTrack = track
            audioSender?.setTrack(track, false)
            _state.update { it.copy(micActive = true, micMuted = false) }
            changed = true
        }
        if (changed) updateService(if (audioTrack != null) CallService.ACTION_CALL else CallService.ACTION_SESSION)
    }

    private fun endAudio() {
        try { audioSender?.setTrack(null, false) } catch (_: Throwable) {}
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        audioTrack = null
        audioSource = null
        if (audioModeSet) {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioModeSet = false
        }
        _state.update { it.copy(micActive = false, micMuted = false) }
        if (pc != null) updateService(CallService.ACTION_SESSION)
    }

    fun setMicMuted(muted: Boolean) {
        val track = audioTrack ?: return
        track.setEnabled(!muted)
        _state.update { it.copy(micMuted = muted) }
    }

    @Suppress("DEPRECATION")
    fun setSpeakerOn(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
        _state.update { it.copy(speakerOn = on) }
    }

    // =========================================================================================
    // Screen share (inside a call)
    // =========================================================================================

    /** [projectionResult] is the Intent returned by MediaProjectionManager's permission screen. */
    fun startScreenShare(projectionResult: Intent) {
        val source = videoSource ?: return
        val track = videoTrack ?: return
        if (_state.value.callPhase != CallPhase.ACTIVE || capturer != null) return
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
        // Drop the mediaProjection service type again.
        if (wasSharing && pc != null) {
            updateService(if (audioTrack != null) CallService.ACTION_CALL else CallService.ACTION_SESSION)
        }
    }

    // =========================================================================================
    // Leaving
    // =========================================================================================

    /** User left the chat / cancelled the setup. */
    fun hangUp() {
        val told = sendControl(JSONObject().put("t", "bye"))
        scope.launch {
            if (told) delay(250) // let the "bye" leave the data channel before closing
            closeSession()
            _state.value = blank()
        }
    }

    /** Leave the "ended" screen, or back out of the setup screens. */
    fun reset() {
        closeSession()
        _state.value = blank()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // =========================================================================================
    // Session setup / teardown
    // =========================================================================================

    private fun updateService(action: String) {
        try { CallService.start(app, action) } catch (_: Throwable) {}
    }

    private fun openSession(useStun: Boolean): PeerConnection {
        releaseResources()
        gathered = CompletableDeferred()

        val f = factory
        // Video (screen) source is created up front but stays disabled. No permission needed.
        // Audio is NOT created here: the mic is only opened when a call starts.
        val vSource = f.createVideoSource(/* isScreencast = */ true)
        val vTrack = f.createVideoTrack("pt-screen", vSource)
        vTrack.setEnabled(false)
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

    /** Host side: add the audio + video m-lines ourselves. Audio sender stays empty for now. */
    private fun bindTracksToNewTransceivers(conn: PeerConnection) {
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        audioSender = conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init).sender
        conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
            .sender.setTrack(videoTrack, false)
    }

    /** Guest side: the m-lines already exist (from the offer); take the senders and send too. */
    private fun bindTracksToExistingTransceivers(conn: PeerConnection) {
        for (t in conn.transceivers) {
            t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> audioSender = t.sender
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
        _state.value = blank(error = message)
    }

    private fun endSession(reason: String) {
        closeSession()
        _state.value = blank(Phase.ENDED, endedReason = reason)
    }

    private fun closeSession() {
        callToken++
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
        audioSender = null

        try { videoTrack?.dispose() } catch (_: Throwable) {}
        try { videoSource?.dispose() } catch (_: Throwable) {}
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        videoTrack = null; videoSource = null; audioTrack = null; audioSource = null

        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioModeSet = false
    }

    // =========================================================================================
    // Data channel
    // =========================================================================================

    private fun attachChannel(dc: DataChannel?) {
        dc ?: return
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) sendHello()
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleControl(String(bytes, Charsets.UTF_8))
            }
        })
        // The guest may receive the channel already open.
        if (dc.state() == DataChannel.State.OPEN) sendHello()
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
                "hello" -> {
                    val n = cleanName(j.optString("name"))
                    if (n.isNotEmpty()) _state.update { s ->
                        val note = when {
                            s.peerName.isEmpty() -> "You're connected with $n"
                            s.peerName != n -> "${s.peerName} is now called $n"
                            else -> null
                        }
                        s.copy(
                            peerName = n,
                            messages = if (note != null) s.messages + sys(note) else s.messages,
                        )
                    }
                }
                "chat" -> {
                    val msg = ChatMessage(j.getString("id"), j.getString("text"), mine = false)
                    _state.update { it.copy(messages = it.messages + msg) }
                }
                "call_req" -> when (_state.value.callPhase) {
                    CallPhase.NONE -> _state.update { it.copy(callPhase = CallPhase.INCOMING) }
                    CallPhase.OUTGOING -> {
                        // Both people tapped call at the same moment: treat it as accepted.
                        sendControl(JSONObject().put("t", "call_accept"))
                        activateCall()
                    }
                    else -> {}
                }
                "call_accept" -> if (_state.value.callPhase == CallPhase.OUTGOING) activateCall()
                "call_decline" -> if (_state.value.callPhase == CallPhase.OUTGOING) {
                    callToken++
                    _state.update {
                        val who = it.peerName.ifEmpty { "Your friend" }
                        it.copy(callPhase = CallPhase.NONE, messages = it.messages + sys("$who declined the call"))
                    }
                }
                "call_cancel" -> if (_state.value.callPhase == CallPhase.INCOMING) {
                    _state.update {
                        val who = it.peerName.ifEmpty { "Your friend" }
                        it.copy(callPhase = CallPhase.NONE, messages = it.messages + sys("Missed call from $who"))
                    }
                }
                "call_end" -> if (_state.value.callPhase == CallPhase.ACTIVE) {
                    scope.launch { finishCall("Call ended") }
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
