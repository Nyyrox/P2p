package com.peertalk.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.peertalk.app.CallService
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.rtc.UiState
import kotlinx.coroutines.delay

/**
 * The in-call screen: voice, camera video and screen sharing share one stage.
 *
 * Stage priority: their screen share, then their camera, then their avatar. Your own camera is a
 * small preview in the corner. This is the only screen that asks for the microphone / camera.
 *
 * [onMinimize] sends the person back to the chat while the call keeps running (the chat shows a
 * "tap to return" bar). [pip] is true while Android shows this screen in its small floating window:
 * then only the picture is drawn, with no controls.
 */
@Composable
fun CallScreen(engine: PeerEngine, state: UiState, pip: Boolean, onMinimize: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val peer = state.peerName.ifEmpty { "Friend" }

    // ---- permissions (asked here, never earlier) ---------------------------------------
    var micDenied by remember { mutableStateOf(false) }
    var micBlocked by remember { mutableStateOf(false) } // "don't ask again"

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = micGranted(ctx)
        micDenied = !mic
        if (!mic) {
            micBlocked = ctx.findActivity()?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false
        }
        engine.beginAudio(mic)
        if (state.callVideo) {
            if (camGranted(ctx)) engine.startCamera()
            else engine.reportError("Camera permission was denied, so video is off.")
        }
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) engine.startCamera() else engine.reportError("Camera permission is needed to turn video on.")
    }
    val askMic: () -> Unit = {
        if (micBlocked) {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            val missing = callPermissions(ctx, withCamera = false)
            if (missing.isEmpty()) engine.beginAudio(micGranted(ctx)) else permLauncher.launch(missing)
        }
    }
    val toggleCamera: () -> Unit = {
        when {
            state.cameraOn -> engine.stopCamera()
            camGranted(ctx) -> engine.startCamera()
            else -> camLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(Unit) {
        val missing = callPermissions(ctx, withCamera = state.callVideo)
        if (missing.isEmpty()) {
            engine.beginAudio(micGranted(ctx))
            if (state.callVideo) engine.startCamera()
        } else {
            permLauncher.launch(missing)
        }
    }
    // Coming back from system settings after granting the mic there.
    LifecycleResumeEffect(Unit) {
        if (micGranted(ctx)) {
            micDenied = false
            engine.beginAudio(true)
        }
        onPauseOrDispose { }
    }

    // ---- screen share launcher ---------------------------------------------------------
    val projectionManager = remember {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                CallService.start(ctx, CallService.ACTION_SCREEN, data)
            } catch (e: Throwable) {
                engine.reportError("Couldn't start screen sharing: ${e.message}")
            }
        }
    }

    // ---- dark call chrome + keep screen on ---------------------------------------------
    DisposableEffect(Unit) {
        val controller = ctx.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            controller?.isAppearanceLightStatusBars = prevStatus ?: false
            controller?.isAppearanceLightNavigationBars = prevNav ?: false
        }
    }

    // ---- call timer --------------------------------------------------------------------
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = if (state.callStartedAt > 0) ((now - state.callStartedAt) / 1000).coerceAtLeast(0) else 0L
    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)

    // ---- stage state -------------------------------------------------------------------
    val remoteScreenOn = state.remoteSharing
    val remoteCamOn = state.remoteCameraOn
    val hasRemoteVideo = remoteScreenOn || remoteCamOn
    var controlsVisible by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var fit by remember { mutableStateOf(true) }
    var gotFrame by remember(remoteScreenOn, remoteCamOn) { mutableStateOf(false) }
    var slow by remember(remoteScreenOn, remoteCamOn) { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, tick, hasRemoteVideo) {
        if (controlsVisible && hasRemoteVideo) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(hasRemoteVideo) { if (!hasRemoteVideo) controlsVisible = true }
    LaunchedEffect(remoteScreenOn, remoteCamOn, gotFrame) {
        if (hasRemoteVideo && !gotFrame) {
            delay(8_000)
            slow = true
        }
    }

    BackHandler(enabled = !pip) { onMinimize() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ---- stage -----------------------------------------------------------------------
        when {
            remoteScreenOn -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoView(
                    relay = engine.remoteScreenRelay,
                    eglBase = engine.eglBase,
                    modifier = Modifier.fillMaxSize(),
                    fit = fit,
                    onFirstFrame = { gotFrame = true },
                )
            }
            remoteCamOn -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoView(
                    relay = engine.remoteCameraRelay,
                    eglBase = engine.eglBase,
                    modifier = Modifier.fillMaxSize(),
                    fit = false,
                    onFirstFrame = { gotFrame = true },
                )
            }
            pip -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Avatar(peer, 64.dp, container = Color.White, content = Color.Black)
                Text(
                    peer,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )
            }
            else -> Column(
                Modifier.fillMaxSize().padding(bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PulsingAvatar(peer, 112.dp, ringColor = Color.White, container = Color.White, content = Color.Black)
                Text(peer, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !state.micActive -> "Listening only"
                        state.micMuted -> "You're muted"
                        else -> "In call"
                    },
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (hasRemoteVideo && !gotFrame && !pip) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                Text(
                    if (slow) "Still waiting for the picture. If it never shows, ask $peer to stop and share again."
                    else if (remoteScreenOn) "Waiting for $peer's screen…" else "Waiting for $peer's camera…",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Tap the picture to show / hide the controls.
        if (hasRemoteVideo && !pip) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    controlsVisible = !controlsVisible
                    tick++
                },
            )
        }

        // ---- top bar -------------------------------------------------------------------
        AnimatedVisibility(
            visible = controlsVisible && !pip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = if (hasRemoteVideo) 0.5f else 0f))
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimise call", tint = Color.White)
                }
                Box(Modifier.size(8.dp).background(if (state.linkUnstable) Brand.Amber else Brand.Green, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (remoteScreenOn) "$peer's screen" else peer,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            state.linkUnstable -> "Reconnecting…"
                            remoteScreenOn -> "Sharing live"
                            remoteCamOn -> "Video call"
                            else -> "Voice call"
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (remoteScreenOn) {
                    IconButton(onClick = { fit = !fit; tick++ }) {
                        Icon(
                            if (fit) Icons.Filled.Fullscreen else Icons.Filled.FullscreenExit,
                            contentDescription = if (fit) "Fill screen" else "Fit to screen",
                            tint = Color.White,
                        )
                    }
                }
                Text(timer, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
            }
        }

        // ---- picture-in-picture: your camera (and their camera while they also share) -----
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 72.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.cameraOn && !pip) {
                Box(Modifier.size(width = 104.dp, height = 144.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                    VideoView(
                        relay = engine.localCameraRelay,
                        eglBase = engine.eglBase,
                        modifier = Modifier.fillMaxSize(),
                        mirror = state.frontCamera,
                    )
                    IconButton(
                        onClick = engine::switchCamera,
                        modifier = Modifier.align(Alignment.BottomEnd).size(36.dp),
                    ) {
                        Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "Switch camera", tint = Color.White)
                    }
                }
            }
            if (remoteScreenOn && remoteCamOn && !pip) {
                Box(Modifier.size(width = 104.dp, height = 144.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                    VideoView(
                        relay = engine.remoteCameraRelay,
                        eglBase = engine.eglBase,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ---- bottom: banners + controls --------------------------------------------------
        if (!pip) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.sharingScreen) SharingCard(peer, onStop = engine::stopScreenShare)
            if (!state.micActive && micDenied) {
                Banner(
                    text = if (micBlocked) "Microphone is blocked, so $peer can't hear you."
                    else "Microphone is off, so $peer can't hear you.",
                    action = if (micBlocked) "Open settings" else "Allow",
                    onAction = askMic,
                )
            }
            state.error?.let { Banner(text = it, action = "Dismiss", onAction = engine::clearError) }

            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = Color(0xFF141414), shape = RoundedCornerShape(32.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top,
                    ) {
                        val idle = Color(0xFF2A2A2A)
                        val labelColor = Color.White.copy(alpha = 0.85f)
                        val dark = Color.Black
                        val micOff = !state.micActive || state.micMuted

                        RoundAction(
                            icon = if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (!state.micActive) "Mic off" else if (state.micMuted) "Unmute" else "Mute",
                            container = if (micOff) Color.White else idle,
                            content = if (micOff) dark else Color.White,
                            onClick = { tick++; if (!state.micActive) askMic() else engine.setMicMuted(!state.micMuted) },
                            labelColor = labelColor,
                            size = 52.dp,
                        )
                        RoundAction(
                            icon = if (state.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            label = "Camera",
                            container = if (state.cameraOn) Color.White else idle,
                            content = if (state.cameraOn) dark else Color.White,
                            onClick = { tick++; toggleCamera() },
                            labelColor = labelColor,
                            size = 52.dp,
                        )
                        RoundAction(
                            icon = if (state.sharingScreen) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                            label = if (state.sharingScreen) "Stop" else "Share",
                            container = if (state.sharingScreen) Color.White else idle,
                            content = if (state.sharingScreen) dark else Color.White,
                            onClick = {
                                tick++
                                if (state.sharingScreen) engine.stopScreenShare()
                                else shareLauncher.launch(projectionManager.createScreenCaptureIntent())
                            },
                            labelColor = labelColor,
                            size = 52.dp,
                        )
                        RoundAction(
                            icon = if (state.speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            label = if (state.speakerOn) "Speaker" else "Earpiece",
                            container = if (state.speakerOn) idle else Color.White,
                            content = if (state.speakerOn) Color.White else dark,
                            onClick = { tick++; engine.setSpeakerOn(!state.speakerOn) },
                            labelColor = labelColor,
                            size = 52.dp,
                        )
                        RoundAction(
                            icon = Icons.Filled.CallEnd,
                            label = "End",
                            container = Brand.Red,
                            content = Color.White,
                            onClick = engine::endCall,
                            labelColor = labelColor,
                            size = 52.dp,
                        )
                    }
                }
            }
        }
    }
}

/** Shown to the person who is sharing: unmistakable, with a one-tap stop. */
@Composable
private fun SharingCard(peer: String, onStop: () -> Unit) {
    val t = rememberInfiniteTransition(label = "live")
    val blink by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "blink",
    )
    Surface(
        color = Color(0xFF1C1C1C),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF333333)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(10.dp).alpha(blink).background(Brand.Red, CircleShape))
            Column(Modifier.weight(1f)) {
                Text("You're sharing your screen", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Open any app. $peer sees it live.",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Red, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Stop") }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(color = Color(0xFF1C1C1C), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(action, color = Color.White) }
        }
    }
}
