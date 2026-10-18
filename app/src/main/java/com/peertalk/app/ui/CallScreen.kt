package com.peertalk.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.peertalk.app.CallService
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.rtc.UiState
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The in-call screen. This is the only screen that asks for the microphone: the prompt shows
 * up when it opens, after the other person has already accepted the call.
 */
@Composable
fun CallScreen(engine: PeerEngine, state: UiState, remoteVideo: VideoTrack?) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val peer = state.peerName.ifEmpty { "Friend" }

    // ---- microphone permission (asked here, never earlier) --------------------------------
    var micDenied by remember { mutableStateOf(false) }
    var micBlocked by remember { mutableStateOf(false) } // "don't ask again"
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = micGranted(ctx)
        micDenied = !ok
        if (!ok) {
            micBlocked = ctx.findActivity()?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false
        }
        engine.beginAudio(ok)
    }
    val askMic: () -> Unit = {
        if (micBlocked) {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            val missing = callPermissions(ctx)
            if (missing.isEmpty()) engine.beginAudio(micGranted(ctx)) else permLauncher.launch(missing)
        }
    }
    LaunchedEffect(Unit) {
        if (micGranted(ctx)) engine.beginAudio(true) else askMic()
    }
    // Coming back from system settings after granting the mic there.
    LifecycleResumeEffect(Unit) {
        if (micGranted(ctx)) {
            micDenied = false
            engine.beginAudio(true)
        }
        onPauseOrDispose { }
    }

    // ---- screen share -----------------------------------------------------------------
    val projectionManager = remember {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CallService.start(ctx, CallService.ACTION_SCREEN, data)
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

    // ---- call timer -------------------------------------------------------------------
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = if (state.callStartedAt > 0) ((now - state.callStartedAt) / 1000).coerceAtLeast(0) else 0L
    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.CallTop, Brand.CallBottom))),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // status row
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).background(if (state.linkUnstable) Brand.Amber else Brand.Green, CircleShape))
                Text(
                    if (state.linkUnstable) "Reconnecting…" else "Voice call",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(timer, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            }

            // main area: their screen if they share, otherwise the big avatar
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (state.remoteSharing) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                        RemoteVideo(remoteVideo, engine.eglBase, Modifier.fillMaxSize())
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                        ) {
                            Text(
                                "$peer's screen",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PulsingAvatar(peer, 112.dp, ringColor = Color.White)
                        Text(peer, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !state.micActive -> "Listening only"
                                state.micMuted -> "You're muted"
                                else -> "In call"
                            },
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // banners
            if (!state.micActive && micDenied) {
                Banner(
                    text = if (micBlocked) "Microphone is blocked, so $peer can't hear you."
                    else "Microphone is off, so $peer can't hear you.",
                    action = if (micBlocked) "Open settings" else "Allow",
                    onAction = askMic,
                )
            }
            if (state.sharingScreen) Banner(text = "You're sharing your screen", action = "Stop", onAction = engine::stopScreenShare)
            if (state.error != null) Banner(text = state.error, action = "Dismiss", onAction = engine::clearError)

            // controls
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                val idle = Color.White.copy(alpha = 0.14f)
                val labelColor = Color.White.copy(alpha = 0.8f)

                val micOff = !state.micActive || state.micMuted
                RoundAction(
                    icon = if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (!state.micActive) "Mic off" else if (state.micMuted) "Unmute" else "Mute",
                    container = if (micOff) Color.White else idle,
                    content = if (micOff) Color(0xFF14151F) else Color.White,
                    onClick = { if (!state.micActive) askMic() else engine.setMicMuted(!state.micMuted) },
                    labelColor = labelColor,
                    size = 60.dp,
                )
                RoundAction(
                    icon = if (state.speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    label = if (state.speakerOn) "Speaker" else "Earpiece",
                    container = if (state.speakerOn) idle else Color.White,
                    content = if (state.speakerOn) Color.White else Color(0xFF14151F),
                    onClick = { engine.setSpeakerOn(!state.speakerOn) },
                    labelColor = labelColor,
                    size = 60.dp,
                )
                RoundAction(
                    icon = if (state.sharingScreen) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                    label = if (state.sharingScreen) "Stop" else "Share",
                    container = if (state.sharingScreen) Color.White else idle,
                    content = if (state.sharingScreen) Color(0xFF14151F) else Color.White,
                    onClick = {
                        if (state.sharingScreen) engine.stopScreenShare()
                        else shareLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    labelColor = labelColor,
                    size = 60.dp,
                )
                RoundAction(
                    icon = Icons.Filled.CallEnd,
                    label = "End",
                    container = Brand.Red,
                    content = Color.White,
                    onClick = engine::endCall,
                    labelColor = labelColor,
                    size = 60.dp,
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
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
            TextButton(onClick = onAction) { Text(action, color = Color(0xFF9FA0FF)) }
        }
    }
}

@Composable
private fun RemoteVideo(track: VideoTrack?, eglBase: EglBase, modifier: Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                renderer = this
            }
        },
    )

    DisposableEffect(track, renderer) {
        val r = renderer
        if (track != null && r != null) track.addSink(r)
        onDispose {
            if (track != null && r != null) {
                try { track.removeSink(r) } catch (_: Throwable) {}
            }
        }
    }
    DisposableEffect(renderer) {
        val r = renderer
        onDispose { r?.release() }
    }
}
