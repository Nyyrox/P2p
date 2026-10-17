package com.peertalk.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peertalk.app.CallService
import com.peertalk.app.rtc.ChatMessage
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.rtc.Phase
import com.peertalk.app.rtc.UiState
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private enum class Mode { HOME, HOST, JOIN }

// ---------------------------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------------------------

@Composable
fun PeerTalkTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------------------------

private fun missingPermissions(ctx: Context): List<String> {
    val wanted = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return wanted.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
}

private fun micGranted(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun PeerTalkApp(engine: PeerEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    val remoteVideo by engine.remoteVideo.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var mode by rememberSaveable { mutableStateOf(Mode.HOME) }
    var useStun by rememberSaveable { mutableStateOf(true) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permError by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (micGranted(ctx)) {
            permError = null
            pending?.invoke()
        } else {
            permError = "Microphone permission is required for voice calls."
        }
        pending = null
    }
    val withPermissions: (() -> Unit) -> Unit = { action ->
        val missing = missingPermissions(ctx)
        if (missing.isEmpty()) action() else {
            pending = action
            permLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == Phase.IDLE && mode == Mode.HOST) mode = Mode.HOME
    }

    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when {
                state.phase == Phase.CONNECTED -> CallScreen(
                    engine = engine,
                    state = state,
                    remoteVideo = remoteVideo,
                    onHangUp = { engine.hangUp(); mode = Mode.HOME },
                )
                state.phase == Phase.ENDED -> EndedScreen(state.endedReason) {
                    engine.reset(); mode = Mode.HOME
                }
                mode == Mode.HOME -> HomeScreen(
                    useStun = useStun,
                    onUseStun = { useStun = it },
                    error = permError ?: state.error,
                    onHost = { withPermissions { engine.startHost(useStun); mode = Mode.HOST } },
                    onJoin = { engine.clearError(); mode = Mode.JOIN },
                )
                mode == Mode.HOST -> HostScreen(
                    state = state,
                    onReply = { engine.acceptReply(it) },
                    onCancel = { engine.hangUp(); mode = Mode.HOME },
                )
                else -> JoinScreen(
                    state = state,
                    error = permError ?: state.error,
                    onCreateReply = { code -> withPermissions { engine.join(code, useStun) } },
                    onCancel = { engine.hangUp(); mode = Mode.HOME },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Setup screens
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    useStun: Boolean,
    onUseStun: (Boolean) -> Unit,
    error: String?,
    onHost: () -> Unit,
    onJoin: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("PeerTalk", style = MaterialTheme.typography.displayMedium)
        Text(
            "Chat, talk and share your screen straight to another phone. " +
                "No account, no server. You just swap two codes.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onHost, modifier = Modifier.fillMaxWidth()) { Text("Create a connection") }
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Join with a code") }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = useStun, onCheckedChange = onUseStun)
            Column {
                Text("Connect over the internet", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (useStun) {
                        "Asks a public STUN server (Google) for your public address, nothing else. " +
                            "Turn off for same Wi-Fi only."
                    } else {
                        "Same Wi-Fi or hotspot only. No outside server is contacted at all."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ErrorText(error)
    }
}

@Composable
private fun HostScreen(state: UiState, onReply: (String) -> Unit, onCancel: () -> Unit) {
    var reply by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Create a connection", style = MaterialTheme.typography.headlineSmall)
        when (state.phase) {
            Phase.HOST_CODE_READY -> {
                Text("1. Send this code to your friend", style = MaterialTheme.typography.titleSmall)
                CodeCard(state.code)
                Text("2. Paste the reply code they send back", style = MaterialTheme.typography.titleSmall)
                PasteField(reply, { reply = it }, "Reply code")
                Button(
                    onClick = { onReply(reply) },
                    enabled = reply.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
                ErrorText(state.error)
            }
            else -> Busy(if (state.phase == Phase.CONNECTING) "Connecting…" else "Creating your code…")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun JoinScreen(
    state: UiState,
    error: String?,
    onCreateReply: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var invite by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Join with a code", style = MaterialTheme.typography.headlineSmall)
        when (state.phase) {
            Phase.IDLE -> {
                Text("1. Paste the code your friend sent you", style = MaterialTheme.typography.titleSmall)
                PasteField(invite, { invite = it }, "Invite code")
                Button(
                    onClick = { onCreateReply(invite) },
                    enabled = invite.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create reply code") }
                ErrorText(error)
            }
            Phase.GUEST_CODE_READY -> {
                Text("2. Send this reply code back to your friend", style = MaterialTheme.typography.titleSmall)
                CodeCard(state.code)
                Busy("Waiting for your friend to connect…")
            }
            else -> Busy("Working…")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun EndedScreen(reason: String?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Session ended", style = MaterialTheme.typography.headlineSmall)
        Text(reason ?: "The connection closed.")
        Button(onClick = onBack) { Text("Back") }
    }
}

// ---------------------------------------------------------------------------------------------
// In-call screen
// ---------------------------------------------------------------------------------------------

@Composable
private fun CallScreen(
    engine: PeerEngine,
    state: UiState,
    remoteVideo: VideoTrack?,
    onHangUp: () -> Unit,
) {
    val ctx = LocalContext.current
    val projectionManager = remember {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CallService.start(ctx, CallService.ACTION_SCREEN, data)
        }
    }

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    val send = {
        engine.sendChat(draft)
        draft = ""
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(10.dp).background(
                    if (state.linkUnstable) Color(0xFFE0A100) else Color(0xFF2EA043),
                    CircleShape,
                ),
            )
            Text(
                if (state.linkUnstable) "Reconnecting…" else "Connected",
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.sharingScreen) {
                Spacer(Modifier.weight(1f))
                Text("Sharing your screen", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (state.remoteSharing) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                RemoteVideo(remoteVideo, engine.eglBase, Modifier.fillMaxSize())
            }
        }

        if (state.messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Say hi. Voice is already live.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages, key = { it.id }) { Bubble(it) }
            }
        }

        if (state.error != null) {
            Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            IconButton(onClick = { send() }, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(
                icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                description = if (state.micMuted) "Unmute" else "Mute",
                highlighted = state.micMuted,
            ) { engine.setMicMuted(!state.micMuted) }

            RoundButton(
                icon = if (state.speakerOn) Icons.AutoMirrored.Filled.VolumeUp
                else Icons.AutoMirrored.Filled.VolumeOff,
                description = if (state.speakerOn) "Switch to earpiece" else "Switch to speaker",
                highlighted = !state.speakerOn,
            ) { engine.setSpeakerOn(!state.speakerOn) }

            RoundButton(
                icon = if (state.sharingScreen) Icons.Filled.StopScreenShare else Icons.Filled.ScreenShare,
                description = if (state.sharingScreen) "Stop sharing" else "Share screen",
                highlighted = state.sharingScreen,
            ) {
                if (state.sharingScreen) engine.stopScreenShare()
                else shareLauncher.launch(projectionManager.createScreenCaptureIntent())
            }

            FilledIconButton(
                onClick = onHangUp,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Icon(Icons.Filled.CallEnd, contentDescription = "End") }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (m.mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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

// ---------------------------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun CodeCard(code: String) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                code.take(64) + "…",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            Text("${code.length} characters. Copy the whole thing.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (copied) "Copied" else "Copy")
                }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, code)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Send code"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun PasteField(value: String, onChange: (String) -> Unit, label: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        )
        TextButton(onClick = { clipboard.getText()?.text?.let(onChange) }) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Paste from clipboard")
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
}
