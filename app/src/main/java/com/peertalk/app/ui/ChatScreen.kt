package com.peertalk.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peertalk.app.rtc.CallPhase
import com.peertalk.app.rtc.ChatMessage
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.rtc.UiState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(engine: PeerEngine, state: UiState, onLeave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val peer = state.peerName.ifEmpty { "Friend" }

    var draft by rememberSaveable { mutableStateOf("") }
    var editingName by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {

            // ---- header ----------------------------------------------------------------
            Surface(color = cs.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(peer, 44.dp)
                        Column(Modifier.weight(1f)) {
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
                        FilledTonalIconButton(
                            onClick = engine::requestCall,
                            enabled = state.callPhase == CallPhase.NONE,
                        ) { Icon(Icons.Filled.Call, contentDescription = "Voice call") }
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
                        Text("Calling $peer…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
                            "Tap the phone icon any time to ask for a voice call.",
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
                        if (m.system) SystemLine(m) else Bubble(m, timeFormat)
                    }
                }
            }

            ErrorBanner(state.error, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

            // ---- input --------------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
            IncomingCall(peer, onAccept = engine::acceptCall, onDecline = engine::declineCall)
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
private fun Bubble(m: ChatMessage, timeFormat: DateFormat) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (m.mine) 20.dp else 5.dp,
        bottomEnd = if (m.mine) 5.dp else 20.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.mine) cs.primary else cs.surface,
            contentColor = if (m.mine) cs.onPrimary else cs.onSurface,
            shape = shape,
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

@Composable
private fun IncomingCall(name: String, onAccept: () -> Unit, onDecline: () -> Unit) {
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
                Text("is asking for a voice call", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundAction(Icons.Filled.CallEnd, "Decline", Brand.Red, Color.White, onDecline, cs.onSurface)
                    RoundAction(Icons.Filled.Call, "Accept", Brand.Green, Color.White, onAccept, cs.onSurface)
                }
            }
        }
    }
}
