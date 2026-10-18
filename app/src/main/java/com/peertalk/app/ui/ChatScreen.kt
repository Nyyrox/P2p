package com.peertalk.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.peertalk.app.rtc.CallPhase
import com.peertalk.app.rtc.ChatMessage
import com.peertalk.app.rtc.FileInfo
import com.peertalk.app.rtc.FileStatus
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.rtc.UiState
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(engine: PeerEngine, state: UiState, onLeave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val peer = state.peerName.ifEmpty { "Friend" }

    var draft by rememberSaveable { mutableStateOf("") }
    var editingName by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<FileInfo?>(null) }
    val listState = rememberLazyListState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { engine.sendFile(it) }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingSave
        val path = f?.path
        if (uri != null && path != null) engine.exportFile(path, uri)
        pendingSave = null
    }

    BackHandler { confirmLeave = true }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(4_000)
            engine.clearError()
        }
    }

    val send: () -> Unit = {
        if (draft.isNotBlank()) {
            engine.sendChat(draft)
            draft = ""
        }
    }
    val callIdle = state.callPhase == CallPhase.NONE

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {

            // ---- header ----------------------------------------------------------------
            Surface(color = cs.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Avatar(peer, 44.dp)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(peer, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    Modifier.size(8.dp).background(
                                        if (state.linkUnstable) Brand.Amber else Brand.Green,
                                        CircleShape,
                                    ),
                                )
                                Text(
                                    if (state.linkUnstable) "Reconnecting…" else "Connected",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        FilledTonalIconButton(onClick = { engine.requestCall(video = false) }, enabled = callIdle) {
                            Icon(Icons.Filled.Call, contentDescription = "Voice call")
                        }
                        FilledTonalIconButton(onClick = { engine.requestCall(video = true) }, enabled = callIdle) {
                            Icon(Icons.Filled.Videocam, contentDescription = "Video call")
                        }
                        IconButton(onClick = { confirmLeave = true }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Leave chat")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = cs.surfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { editingName = true },
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "You: ${state.myName.ifEmpty { "…" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                                Icon(Icons.Filled.Edit, contentDescription = "Edit your name", modifier = Modifier.size(14.dp))
                            }
                        }
                        Box(Modifier.weight(1f))
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
                        Text(
                            " Encrypted, peer to peer",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- ringing banner -----------------------------------------------------------
            AnimatedVisibility(visible = state.callPhase == CallPhase.OUTGOING) {
                Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            if (state.callVideo) "Video calling $peer…" else "Calling $peer…",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = engine::cancelCall) { Text("Cancel") }
                    }
                }
            }

            // ---- messages -----------------------------------------------------------------
            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(peer, 72.dp)
                        Text("Say hi to $peer", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Send messages and files, or tap the phone or camera icon to call.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages, key = { it.id }) { m ->
                        val file = m.file
                        when {
                            m.system -> SystemLine(m)
                            file != null -> FileBubble(
                                m = m,
                                file = file,
                                time = timeFormat.format(Date(m.time)),
                                onCancel = { engine.cancelFile(m.id) },
                                onOpen = { openFile(ctx, file) { engine.reportError(it) } },
                                onSave = { pendingSave = file; saveFile.launch(file.name) },
                            )
                            else -> Bubble(m, timeFormat)
                        }
                    }
                }
            }

            ErrorBanner(state.error, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

            // ---- input --------------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Send a file")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surface,
                        unfocusedContainerColor = cs.surface,
                        unfocusedBorderColor = cs.outlineVariant,
                    ),
                )
                FilledIconButton(onClick = send, enabled = draft.isNotBlank(), modifier = Modifier.size(54.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }

        // ---- incoming call (covers the chat) ----------------------------------------------
        if (state.callPhase == CallPhase.INCOMING) {
            IncomingCall(peer, state.callVideo, onAccept = engine::acceptCall, onDecline = engine::declineCall)
        }
    }

    if (editingName) {
        EditNameDialog(
            current = state.myName,
            onSave = { engine.setMyName(it); editingName = false },
            onDismiss = { editingName = false },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this chat?") },
            text = { Text("This closes the connection for both of you. You'll need new codes to connect again.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

private fun openFile(ctx: Context, file: FileInfo, onError: (String) -> Unit) {
    val path = file.path ?: return
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(view, "Open with"))
    } catch (e: ActivityNotFoundException) {
        onError("No app on this phone can open that file. Use Save instead.")
    } catch (e: Throwable) {
        onError("Couldn't open the file.")
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@Composable
private fun EditNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit your name") },
        text = { NameField(value, { value = it }) },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SystemLine(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
            Text(
                m.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun bubbleShape(mine: Boolean) = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = if (mine) 20.dp else 5.dp,
    bottomEnd = if (mine) 5.dp else 20.dp,
)

@Composable
private fun Bubble(m: ChatMessage, timeFormat: DateFormat) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.mine) cs.primary else cs.surface,
            contentColor = if (m.mine) cs.onPrimary else cs.onSurface,
            shape = bubbleShape(m.mine),
            tonalElevation = if (m.mine) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    timeFormat.format(Date(m.time)),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** A file in the chat: name, size, live progress, and Cancel / Open / Save. */
@Composable
private fun FileBubble(
    m: ChatMessage,
    file: FileInfo,
    time: String,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val container = if (m.mine) cs.primary else cs.surface
    val content = if (m.mine) cs.onPrimary else cs.onSurface
    val inProgress = file.status == FileStatus.SENDING || file.status == FileStatus.RECEIVING
    val known = file.size > 0

    val status = when (file.status) {
        FileStatus.SENDING, FileStatus.RECEIVING -> {
            val verb = if (file.status == FileStatus.SENDING) "Sending" else "Receiving"
            if (known) "$verb · ${(file.transferred * 100 / file.size).coerceIn(0, 100)}% · " +
                "${formatSize(file.transferred)} of ${formatSize(file.size)}"
            else "$verb · ${formatSize(file.transferred)}"
        }
        FileStatus.DONE -> (if (m.mine) "Sent" else "Received") + " · " + formatSize(if (known) file.size else file.transferred)
        FileStatus.FAILED -> "Failed. Ask them to send it again."
        FileStatus.CANCELED -> "Canceled"
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = container,
            contentColor = content,
            shape = bubbleShape(m.mine),
            tonalElevation = if (m.mine) 0.dp else 1.dp,
            modifier = Modifier.widthIn(min = 240.dp, max = 300.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).background(content.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Description, contentDescription = null) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(status, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.8f))
                    }
                    if (inProgress) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel transfer")
                        }
                    }
                }
                if (inProgress) {
                    val bar = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    if (known) {
                        LinearProgressIndicator(
                            progress = { (file.transferred.toFloat() / file.size).coerceIn(0f, 1f) },
                            modifier = bar,
                            color = content,
                            trackColor = content.copy(alpha = 0.25f),
                        )
                    } else {
                        LinearProgressIndicator(modifier = bar, color = content, trackColor = content.copy(alpha = 0.25f))
                    }
                }
                if (!m.mine && file.status == FileStatus.DONE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpen, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Open")
                        }
                        TextButton(onClick = onSave, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Save")
                        }
                    }
                }
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun IncomingCall(name: String, video: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = cs.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulsingAvatar(name, 88.dp)
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (video) "is asking for a video call" else "is asking for a voice call",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundAction(Icons.Filled.CallEnd, "Decline", Brand.Red, Color.White, onDecline, cs.onSurface)
                    RoundAction(
                        if (video) Icons.Filled.Videocam else Icons.Filled.Call,
                        "Accept", Brand.Green, Color.White, onAccept, cs.onSurface,
                    )
                }
            }
        }
    }
}
