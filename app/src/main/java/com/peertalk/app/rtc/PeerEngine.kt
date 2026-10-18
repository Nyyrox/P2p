package com.peertalk.app.rtc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.peertalk.app.CallService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
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
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Process-wide WebRTC engine. No signaling server: the two SDP blobs are exchanged by the
 * users themselves (see [CodeCodec]). Everything after that flows peer to peer.
 *
 * A session starts as CHAT ONLY. Mic and camera are never opened (and no permission is needed)
 * until both people agree to a call:
 *
 *   caller: [requestCall]  ->  "call_req" (video flag)
 *   callee: [acceptCall] / [declineCall]
 *   either: [endCall] -> back to chat, session stays open
 *
 * Three media lines are negotiated up front, all sendrecv, and their senders stay empty until
 * needed, so starting or stopping mic / camera / screen share never needs renegotiation:
 *   m-line 0 = audio, m-line 1 = camera video, m-line 2 = screen share video.
 *
 * Files travel over the same data channel as chat (binary messages = file chunks).
 */
class PeerEngine private constructor(private val app: Context) {

    companion object {
        private const val TAG = "PeerTalk"
        @Volatile private var instance: PeerEngine? = null
        fun get(context: Context): PeerEngine =
            instance ?: synchronized(this) {
                instance ?: PeerEngine(context.applicationContext).also { instance = it }
            }

        private const val GATHER_TIMEOUT_MS = 6_000L
        private const val CALL_TIMEOUT_MS = 45_000L
        private const val CAMERA_W = 640
        private const val CAMERA_H = 480
        private const val CAMERA_FPS = 24
        private const val SCREEN_MAX_EDGE = 1280f
        private const val SCREEN_FPS = 15
        private const val FILE_PAYLOAD = 16 * 1024 - 4  // 4 bytes of header + payload = 16 KiB per message
        private const val BUFFER_HIGH = 512L * 1024
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

    /** Remote camera / screen frames, and the local camera preview. Renderers attach to these. */
    val remoteCameraRelay = VideoRelay(cache = true)
    val remoteScreenRelay = VideoRelay(cache = true)
    val localCameraRelay = VideoRelay(cache = false)

    // Software VP8 on both ends: hardware H.264 encoders behave differently from phone to phone
    // (the usual reason screen share works on one device and shows nothing on another).
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
    }

    // ---- per-session resources ------------------------------------------------------------
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audioSender: RtpSender? = null
    private var cameraSender: RtpSender? = null
    private var screenSender: RtpSender? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioModeSet = false

    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null

    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    @Volatile private var tearingDownScreen = false

    private var remoteVideoSeen = 0
    private val remoteAttached = CopyOnWriteArrayList<Pair<VideoTrack, VideoRelay>>()

    private var gathered = CompletableDeferred<Unit>()
    @Volatile private var callToken = 0

    // ---- file transfer --------------------------------------------------------------------
    private sealed interface RxMsg {
        class Chunk(val data: ByteArray) : RxMsg
        class Ctl(val json: JSONObject) : RxMsg
    }

    private class Outgoing { @Volatile var canceled = false }

    private class Incoming(
        val name: String,
        val size: Long,
        val file: File,
        val out: FileOutputStream,
    ) {
        var received = 0L
        var lastUi = 0L
        @Volatile var closed = false
    }

    private val rx = Channel<RxMsg>(Channel.UNLIMITED)
    private val outgoing = ConcurrentHashMap<Int, Outgoing>()
    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val nextFileId = AtomicInteger(0)

    init {
        // One consumer keeps file chunks and file control messages strictly in order, and
        // moves disk writes off WebRTC's callback thread.
        scope.launch(Dispatchers.IO) {
            for (m in rx) {
                try { processFileMsg(m) } catch (e: Throwable) { Log.w(TAG, "file rx failed", e) }
            }
        }
    }

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

    private fun postError(text: String) = _state.update { it.copy(error = text) }

    fun reportError(text: String) = postError(text)

    private fun addMessage(m: ChatMessage) = _state.update { it.copy(messages = it.messages + m) }

    private fun updateFile(msgId: String, change: (FileInfo) -> FileInfo) {
        _state.update { s ->
            s.copy(messages = s.messages.map { m ->
                val f = m.file
                if (m.id == msgId && f != null) m.copy(file = change(f)) else m
            })
        }
    }

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
            addMessage(ChatMessage(id, t, mine = true))
        }
    }

    // =========================================================================================
    // Calling (optional, needs the other person to accept)
    // =========================================================================================

    /** Ask the other person to start a call. Nothing about mic or camera is touched yet. */
    fun requestCall(video: Boolean = false) {
        val s = _state.value
        if (s.phase != Phase.CONNECTED || s.callPhase != CallPhase.NONE) return
        if (!sendControl(JSONObject().put("t", "call_req").put("video", video))) {
            postError("Can't reach your friend right now.")
            return
        }
        _state.update { it.copy(callPhase = CallPhase.OUTGOING, callVideo = video, error = null) }
        val token = ++callToken
        scope.launch {
            delay(CALL_TIMEOUT_MS)
            if (token == callToken && _state.value.callPhase == CallPhase.OUTGOING) {
                sendControl(JSONObject().put("t", "call_cancel"))
                _state.update {
                    it.copy(callPhase = CallPhase.NONE, callVideo = false, messages = it.messages + sys("No answer"))
                }
            }
        }
    }

    fun cancelCall() {
        if (_state.value.callPhase != CallPhase.OUTGOING) return
        callToken++
        sendControl(JSONObject().put("t", "call_cancel"))
        _state.update { it.copy(callPhase = CallPhase.NONE, callVideo = false) }
    }

    fun acceptCall() {
        if (_state.value.callPhase != CallPhase.INCOMING) return
        sendControl(JSONObject().put("t", "call_accept"))
        activateCall()
    }

    fun declineCall() {
        if (_state.value.callPhase != CallPhase.INCOMING) return
        sendControl(JSONObject().put("t", "call_decline"))
        _state.update {
            it.copy(callPhase = CallPhase.NONE, callVideo = false, messages = it.messages + sys("You declined a call"))
        }
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
        stopCamera()
        endAudio()
        _state.update {
            it.copy(
                callPhase = CallPhase.NONE,
                callVideo = false,
                callStartedAt = 0L,
                remoteSharing = false,
                remoteCameraOn = false,
                messages = it.messages + sys(if (duration != null) "$label · $duration" else label),
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val t = ms / 1000
        return "%02d:%02d".format(t / 60, t % 60)
    }

    // ---- audio ----------------------------------------------------------------------------

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
        if (changed) refreshService()
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
        if (pc != null) refreshService()
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

    // ---- camera ---------------------------------------------------------------------------

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            Log.w(TAG, "camera error: $errorDescription")
            postError("Camera error: ${errorDescription ?: "unknown"}")
            scope.launch { stopCamera() }
        }
        override fun onCameraDisconnected() {
            postError("The camera was disconnected.")
            scope.launch { stopCamera() }
        }
        override fun onCameraFreezed(errorDescription: String?) { Log.w(TAG, "camera froze: $errorDescription") }
        override fun onCameraOpening(cameraName: String?) {}
        override fun onFirstFrameAvailable() {}
        override fun onCameraClosed() {}
    }

    /** Turn the camera on. The UI must already hold the CAMERA permission (asked on the call screen). */
    fun startCamera(front: Boolean = true) {
        if (_state.value.callPhase != CallPhase.ACTIVE || pc == null || cameraCapturer != null) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission is needed for video.")
            return
        }
        try {
            val enumerator = Camera2Enumerator(app)
            val names = enumerator.deviceNames
            val pick = names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
                ?: names.firstOrNull()
                ?: error("No camera found on this device.")
            val cap = enumerator.createCapturer(pick, cameraEvents) ?: error("Could not open the camera.")
            val source = factory.createVideoSource(false)
            val helper = SurfaceTextureHelper.create("PeerTalkCamera", eglBase.eglBaseContext)
            cameraCapturer = cap
            cameraSource = source
            cameraHelper = helper
            cap.initialize(helper, app, source.capturerObserver)
            cap.startCapture(CAMERA_W, CAMERA_H, CAMERA_FPS)

            val track = factory.createVideoTrack("pt-camera", source)
            track.addSink(localCameraRelay)
            cameraTrack = track
            cameraSender?.setTrack(track, false)

            _state.update { it.copy(cameraOn = true, frontCamera = enumerator.isFrontFacing(pick), error = null) }
            sendControl(JSONObject().put("t", "cam").put("on", true))
            refreshService()
        } catch (e: Throwable) {
            Log.e(TAG, "startCamera failed", e)
            teardownCamera()
            _state.update { it.copy(cameraOn = false) }
            postError("Couldn't start the camera: ${e.message}")
        }
    }

    fun stopCamera() {
        val was = cameraCapturer != null || cameraTrack != null
        teardownCamera()
        _state.update { it.copy(cameraOn = false) }
        if (was) {
            sendControl(JSONObject().put("t", "cam").put("on", false))
            if (pc != null) refreshService()
        }
    }

    fun switchCamera() {
        val cap = cameraCapturer ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _state.update { it.copy(frontCamera = isFrontCamera) }
            }
            override fun onCameraSwitchError(errorDescription: String?) {
                postError("Couldn't switch camera.")
            }
        })
    }

    /** Frees camera resources only: no state change, no messages, safe during session teardown. */
    private fun teardownCamera() {
        try { cameraTrack?.removeSink(localCameraRelay) } catch (_: Throwable) {}
        try { cameraSender?.setTrack(null, false) } catch (_: Throwable) {}
        try { cameraCapturer?.stopCapture() } catch (_: Throwable) {}
        try { cameraCapturer?.dispose() } catch (_: Throwable) {}
        try { cameraHelper?.dispose() } catch (_: Throwable) {}
        try { cameraTrack?.dispose() } catch (_: Throwable) {}
        try { cameraSource?.dispose() } catch (_: Throwable) {}
        cameraCapturer = null
        cameraHelper = null
        cameraTrack = null
        cameraSource = null
    }

    // =========================================================================================
    // Screen share (inside a call)
    // =========================================================================================

    /** [projectionResult] is the Intent returned by MediaProjectionManager's permission screen. */
    fun startScreenShare(projectionResult: Intent) {
        if (_state.value.callPhase != CallPhase.ACTIVE || pc == null) {
            postError("Start a call first, then share your screen.")
            return
        }
        if (capturer != null) return
        val sender = screenSender ?: run { postError("Screen share isn't available in this session."); return }
        try {
            Log.i(TAG, "screen share: starting")
            val source = factory.createVideoSource(true)
            val helper = SurfaceTextureHelper.create("PeerTalkScreen", eglBase.eglBaseContext)
            val cap = ScreenCapturerAndroid(projectionResult, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "screen share: projection stopped by the system or user")
                    if (!tearingDownScreen) stopScreenShare()
                }
            })
            screenSource = source
            captureHelper = helper
            capturer = cap
            cap.initialize(helper, app, source.capturerObserver)

            val dm = app.resources.displayMetrics
            val scale = min(1f, SCREEN_MAX_EDGE / max(dm.widthPixels, dm.heightPixels))
            val w = max(16, ((dm.widthPixels * scale).toInt() / 16) * 16)
            val h = max(16, ((dm.heightPixels * scale).toInt() / 16) * 16)

            val track = factory.createVideoTrack("pt-screen", source)
            screenTrack = track
            sender.setTrack(track, false)

            // Tell the viewer first so their renderer is attached before the first frame.
            _state.update { it.copy(sharingScreen = true, error = null) }
            sendControl(JSONObject().put("t", "screen").put("on", true))
            cap.startCapture(w, h, SCREEN_FPS)
            Log.i(TAG, "screen share: capturing ${w}x$h@$SCREEN_FPS")
        } catch (e: Throwable) {
            Log.e(TAG, "screen share failed", e)
            stopScreenShare()
            postError("Screen share failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stopScreenShare() {
        val was = capturer != null || screenTrack != null || _state.value.sharingScreen
        teardownScreen()
        _state.update { it.copy(sharingScreen = false) }
        if (was) {
            sendControl(JSONObject().put("t", "screen").put("on", false))
            if (pc != null) refreshService()
        }
    }

    private fun teardownScreen() {
        if (tearingDownScreen) return
        tearingDownScreen = true
        try {
            try { screenSender?.setTrack(null, false) } catch (_: Throwable) {}
            try { capturer?.stopCapture() } catch (_: Throwable) {}
            try { capturer?.dispose() } catch (_: Throwable) {}
            try { captureHelper?.dispose() } catch (_: Throwable) {}
            try { screenTrack?.dispose() } catch (_: Throwable) {}
            try { screenSource?.dispose() } catch (_: Throwable) {}
            capturer = null
            captureHelper = null
            screenTrack = null
            screenSource = null
        } finally {
            tearingDownScreen = false
        }
    }

    // =========================================================================================
    // File sharing
    // =========================================================================================

    private class Meta(val name: String, val size: Long, val mime: String)

    private fun safeName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().take(120).ifEmpty { "file" }

    private fun fileMeta(uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        val n = safeName(name ?: uri.lastPathSegment ?: "file")
        val mime = app.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Meta(n, size, mime)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        for (i in 1..99) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!f.exists()) return f
        }
        return File(dir, "${System.currentTimeMillis()}_$name")
    }

    /** Send a file the user picked. The other side receives it automatically and can cancel. */
    fun sendFile(uri: Uri) {
        if (_state.value.phase != Phase.CONNECTED) return
        scope.launch(Dispatchers.IO) {
            val ch = channel
            if (ch == null || ch.state() != DataChannel.State.OPEN) {
                postError("Not connected.")
                return@launch
            }
            val meta = try { fileMeta(uri) } catch (e: Throwable) {
                postError("Couldn't read that file.")
                return@launch
            }
            val id = nextFileId.incrementAndGet()
            val msgId = "out-$id"
            val out = Outgoing()
            outgoing[id] = out
            addMessage(
                ChatMessage(
                    msgId, meta.name, mine = true,
                    file = FileInfo(meta.name, meta.size, meta.mime, FileStatus.SENDING),
                ),
            )
            val started = sendControl(
                JSONObject().put("t", "file_start").put("id", id)
                    .put("name", meta.name).put("size", meta.size).put("mime", meta.mime),
            )
            if (!started) {
                outgoing.remove(id)
                updateFile(msgId) { it.copy(status = FileStatus.FAILED) }
                return@launch
            }
            var sent = 0L
            try {
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file")
                input.use { stream ->
                    val payload = ByteArray(FILE_PAYLOAD)
                    var lastUi = 0L
                    while (!out.canceled) {
                        val n = stream.read(payload)
                        if (n < 0) break
                        while (!out.canceled && ch.bufferedAmount() > BUFFER_HIGH) delay(10)
                        if (out.canceled) break
                        val bb = ByteBuffer.allocate(4 + n)
                        bb.putInt(id)
                        bb.put(payload, 0, n)
                        bb.flip()
                        if (!ch.send(DataChannel.Buffer(bb, true))) throw IOException("Connection lost")
                        sent += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi > 120) {
                            lastUi = now
                            val s = sent
                            updateFile(msgId) { it.copy(transferred = s) }
                        }
                    }
                }
                if (!out.canceled) {
                    sendControl(JSONObject().put("t", "file_end").put("id", id))
                    val s = sent
                    updateFile(msgId) { it.copy(status = FileStatus.DONE, transferred = s) }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "send file failed", e)
                sendControl(JSONObject().put("t", "file_cancel").put("id", id).put("by", "sender"))
                updateFile(msgId) { it.copy(status = FileStatus.FAILED) }
            } finally {
                outgoing.remove(id)
            }
        }
    }

    /** Cancel a transfer in either direction (message ids are "out-N" / "in-N"). */
    fun cancelFile(msgId: String) {
        scope.launch(Dispatchers.IO) {
            val id = msgId.substringAfter('-').toIntOrNull() ?: return@launch
            if (msgId.startsWith("out-")) {
                outgoing[id]?.canceled = true
                sendControl(JSONObject().put("t", "file_cancel").put("id", id).put("by", "sender"))
            } else {
                incoming.remove(id)?.let { discard(it) }
                sendControl(JSONObject().put("t", "file_cancel").put("id", id).put("by", "receiver"))
            }
            updateFile(msgId) { it.copy(status = FileStatus.CANCELED) }
        }
    }

    /** Copy a received file to a place the user picked (Storage Access Framework). */
    fun exportFile(path: String, dest: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                File(path).inputStream().use { input ->
                    val out = app.contentResolver.openOutputStream(dest) ?: throw IOException("Can't write there")
                    out.use { input.copyTo(it) }
                }
                addMessage(sys("Saved ${File(path).name}"))
            } catch (e: Throwable) {
                postError("Couldn't save the file.")
            }
        }
    }

    private fun discard(inc: Incoming) {
        inc.closed = true
        try { inc.out.close() } catch (_: Throwable) {}
        inc.file.delete()
    }

    private fun processFileMsg(m: RxMsg) {
        when (m) {
            is RxMsg.Chunk -> onChunk(m.data)
            is RxMsg.Ctl -> {
                val j = m.json
                when (j.getString("t")) {
                    "file_start" -> onFileStart(j)
                    "file_end" -> onFileEnd(j.getInt("id"))
                    "file_cancel" -> onFileCancel(j.getInt("id"), j.optString("by"))
                }
            }
        }
    }

    private fun onFileStart(j: JSONObject) {
        val id = j.getInt("id")
        val name = safeName(j.optString("name", "file"))
        val size = j.optLong("size", -1L)
        val mime = j.optString("mime", "application/octet-stream")
        try {
            val dir = File(app.filesDir, "received").apply { mkdirs() }
            val target = uniqueFile(dir, name)
            incoming[id] = Incoming(name, size, target, FileOutputStream(target))
            addMessage(ChatMessage("in-$id", name, mine = false, file = FileInfo(name, size, mime, FileStatus.RECEIVING)))
        } catch (e: Throwable) {
            Log.w(TAG, "can't receive $name", e)
            sendControl(JSONObject().put("t", "file_cancel").put("id", id).put("by", "receiver"))
            addMessage(sys("Couldn't receive $name"))
        }
    }

    private fun onChunk(data: ByteArray) {
        if (data.size <= 4) return
        val id = ByteBuffer.wrap(data, 0, 4).getInt()
        val inc = incoming[id] ?: return
        if (inc.closed) return
        try {
            inc.out.write(data, 4, data.size - 4)
            inc.received += (data.size - 4)
        } catch (e: IOException) {
            incoming.remove(id)
            discard(inc)
            sendControl(JSONObject().put("t", "file_cancel").put("id", id).put("by", "receiver"))
            updateFile("in-$id") { it.copy(status = FileStatus.FAILED) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - inc.lastUi > 120) {
            inc.lastUi = now
            val got = inc.received
            updateFile("in-$id") { it.copy(transferred = got) }
        }
    }

    private fun onFileEnd(id: Int) {
        val inc = incoming.remove(id) ?: return
        inc.closed = true
        try { inc.out.flush(); inc.out.close() } catch (_: Throwable) {}
        val complete = inc.size <= 0 || inc.received == inc.size
        if (complete) {
            updateFile("in-$id") {
                it.copy(status = FileStatus.DONE, transferred = inc.received, path = inc.file.absolutePath)
            }
        } else {
            inc.file.delete()
            updateFile("in-$id") { it.copy(status = FileStatus.FAILED) }
        }
    }

    private fun onFileCancel(id: Int, by: String) {
        if (by == "sender") {
            incoming.remove(id)?.let { discard(it) }
            updateFile("in-$id") { it.copy(status = FileStatus.CANCELED) }
        } else {
            outgoing[id]?.canceled = true
            updateFile("out-$id") { it.copy(status = FileStatus.CANCELED) }
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
        try {
            CallService.start(app, action)
        } catch (e: Throwable) {
            Log.w(TAG, "service update refused", e)
        }
    }

    /** Re-publish the foreground notification so its types match what is running right now. */
    private fun refreshService() {
        val active = audioTrack != null || cameraCapturer != null || capturer != null
        updateService(if (active) CallService.ACTION_CALL else CallService.ACTION_SESSION)
    }

    private fun openSession(useStun: Boolean): PeerConnection {
        releaseResources()
        gathered = CompletableDeferred()
        remoteVideoSeen = 0

        val servers = if (useStun) {
            listOf(PeerConnection.IceServer.builder(STUN).createIceServer())
        } else emptyList()

        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val conn = factory.createPeerConnection(config, pcObserver)
            ?: error("Could not create the peer connection.")
        pc = conn
        _state.update { it.copy(micMuted = false, speakerOn = true) }
        return conn
    }

    /** Host side: add the three media lines ourselves (audio, camera, screen). Senders stay empty. */
    private fun bindTracksToNewTransceivers(conn: PeerConnection) {
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        audioSender = conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init).sender
        cameraSender = conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init).sender
        screenSender = conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init).sender
    }

    /**
     * Guest side: the m-lines already exist (from the offer), in the same order the host added
     * them. Take their senders once: PeerConnection.getTransceivers() disposes the previous
     * wrappers each time it is called, so it must not be called again.
     */
    private fun bindTracksToExistingTransceivers(conn: PeerConnection) {
        var videoIndex = 0
        for (t in conn.transceivers) {
            t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> audioSender = t.sender
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    if (videoIndex == 0) cameraSender = t.sender else screenSender = t.sender
                    videoIndex++
                }
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
        teardownScreen()
        teardownCamera()

        for ((track, relay) in remoteAttached) {
            try { track.removeSink(relay) } catch (_: Throwable) {}
        }
        remoteAttached.clear()
        remoteCameraRelay.clear()
        remoteScreenRelay.clear()

        for (o in outgoing.values) o.canceled = true
        outgoing.clear()
        for (inc in incoming.values) discard(inc)
        incoming.clear()

        try { channel?.unregisterObserver() } catch (_: Throwable) {}
        try { channel?.close() } catch (_: Throwable) {}
        try { channel?.dispose() } catch (_: Throwable) {}
        channel = null

        try { pc?.close() } catch (_: Throwable) {}
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null
        audioSender = null
        cameraSender = null
        screenSender = null

        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        audioTrack = null
        audioSource = null

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
                if (buffer.binary) {
                    rx.trySend(RxMsg.Chunk(bytes))
                    return
                }
                try {
                    val j = JSONObject(String(bytes, Charsets.UTF_8))
                    // File control messages stay in order with the file chunks.
                    if (j.optString("t").startsWith("file_")) rx.trySend(RxMsg.Ctl(j)) else handleControl(j)
                } catch (_: Throwable) {
                    // Ignore malformed messages.
                }
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

    private fun handleControl(j: JSONObject) {
        try {
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
                "chat" -> addMessage(ChatMessage(j.getString("id"), j.getString("text"), mine = false))
                "call_req" -> {
                    val video = j.optBoolean("video", false)
                    when (_state.value.callPhase) {
                        CallPhase.NONE -> _state.update { it.copy(callPhase = CallPhase.INCOMING, callVideo = video) }
                        CallPhase.OUTGOING -> {
                            // Both people tapped call at the same moment: treat it as accepted.
                            _state.update { it.copy(callVideo = it.callVideo || video) }
                            sendControl(JSONObject().put("t", "call_accept"))
                            activateCall()
                        }
                        else -> {}
                    }
                }
                "call_accept" -> if (_state.value.callPhase == CallPhase.OUTGOING) activateCall()
                "call_decline" -> if (_state.value.callPhase == CallPhase.OUTGOING) {
                    callToken++
                    _state.update {
                        val who = it.peerName.ifEmpty { "Your friend" }
                        it.copy(
                            callPhase = CallPhase.NONE, callVideo = false,
                            messages = it.messages + sys("$who declined the call"),
                        )
                    }
                }
                "call_cancel" -> if (_state.value.callPhase == CallPhase.INCOMING) {
                    _state.update {
                        val who = it.peerName.ifEmpty { "Your friend" }
                        it.copy(
                            callPhase = CallPhase.NONE, callVideo = false,
                            messages = it.messages + sys("Missed call from $who"),
                        )
                    }
                }
                "call_end" -> if (_state.value.callPhase == CallPhase.ACTIVE) {
                    scope.launch { finishCall("Call ended") }
                }
                "cam" -> _state.update { it.copy(remoteCameraOn = j.getBoolean("on")) }
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
            val track = transceiver?.receiver?.track() as? VideoTrack ?: return
            // onTrack fires in m-line order: first video line = camera, second = screen.
            val relay = if (remoteVideoSeen++ == 0) remoteCameraRelay else remoteScreenRelay
            track.setEnabled(true)
            track.addSink(relay)
            remoteAttached.add(track to relay)
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
