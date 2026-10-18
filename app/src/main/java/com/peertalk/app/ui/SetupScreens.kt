package com.peertalk.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peertalk.app.rtc.Phase
import com.peertalk.app.rtc.UiState

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(
    useStun: Boolean,
    onUseStun: (Boolean) -> Unit,
    error: String?,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Hero()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            ActionCard(
                icon = Icons.Filled.Add,
                title = "Create a connection",
                subtitle = "Make an invite code and send it to a friend",
                filled = true,
                onClick = onCreate,
            )
            ActionCard(
                icon = Icons.Filled.Link,
                title = "Join with a code",
                subtitle = "Paste the invite code you were sent",
                filled = false,
                onClick = onJoin,
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = cs.surface,
            border = BorderStroke(1.dp, cs.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).background(cs.secondaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Public, contentDescription = null, tint = cs.onSecondaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Connect over the internet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (useStun) "Asks a public STUN server for your public address, nothing else. Turn off for same Wi-Fi only."
                        else "Same Wi-Fi or hotspot only. No outside server is contacted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Switch(checked = useStun, onCheckedChange = onUseStun)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStep("1", "Share your invite code", Modifier.weight(1f))
            MiniStep("2", "Get a reply code back", Modifier.weight(1f))
            MiniStep("3", "Chat, then call if you like", Modifier.weight(1f))
        }

        ErrorBanner(error)
    }
}

@Composable
private fun Hero() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Brand.Indigo, Brand.Teal)))
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(width = 64.dp, height = 40.dp)) {
                Box(Modifier.size(40.dp).align(Alignment.CenterStart).background(Color.White.copy(alpha = 0.95f), CircleShape))
                Box(Modifier.size(40.dp).align(Alignment.CenterEnd).background(Color.White.copy(alpha = 0.45f), CircleShape))
            }
            Text("PeerTalk", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "Private chat and calls straight between two phones. No account, no server.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, filled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = if (filled) cs.primaryContainer else cs.surface,
        border = if (filled) null else BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(48.dp).background(if (filled) cs.primary else cs.surfaceVariant, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (filled) cs.onPrimary else cs.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniStep(number: String, text: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(number, style = MaterialTheme.typography.titleMedium, color = cs.primary)
            Text(text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Create (host)
// ---------------------------------------------------------------------------------------------

@Composable
fun HostScreen(
    state: UiState,
    name: String,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onReply: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var reply by rememberSaveable { mutableStateOf("") }
    SetupScaffold("Create a connection", onCancel) {
        when (state.phase) {
            Phase.IDLE -> {
                StepCard(1, "What should your friend call you?") {
                    NameField(name, onName)
                    Text(
                        "You can change it any time from the chat screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryButton("Generate invite code", onClick = onCreate, enabled = name.isNotBlank())
                ErrorBanner(state.error)
            }
            Phase.HOST_CODE_READY -> {
                StepCard(1, "Send this invite code to your friend") { CodeCard(state.code) }
                StepCard(2, "Paste the reply code they send back") {
                    PasteField(reply, { reply = it }, "Reply code")
                }
                PrimaryButton("Connect", onClick = { onReply(reply) }, enabled = reply.isNotBlank())
                ErrorBanner(state.error)
            }
            else -> CenterBusy(if (state.phase == Phase.CONNECTING) "Connecting…" else "Creating your code…")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Join (guest)
// ---------------------------------------------------------------------------------------------

@Composable
fun JoinScreen(
    state: UiState,
    name: String,
    onName: (String) -> Unit,
    onCreateReply: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var invite by rememberSaveable { mutableStateOf("") }
    SetupScaffold("Join with a code", onCancel) {
        when (state.phase) {
            Phase.IDLE -> {
                StepCard(1, "What should your friend call you?") { NameField(name, onName) }
                StepCard(2, "Paste the invite code you were sent") {
                    PasteField(invite, { invite = it }, "Invite code")
                }
                PrimaryButton(
                    "Create reply code",
                    onClick = { onCreateReply(invite) },
                    enabled = invite.isNotBlank() && name.isNotBlank(),
                )
                ErrorBanner(state.error)
            }
            Phase.GUEST_CODE_READY -> {
                StepCard(3, "Send this reply code back") {
                    CodeCard(state.code)
                    Busy("Waiting for your friend to connect…")
                }
            }
            else -> CenterBusy("Working…")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ended
// ---------------------------------------------------------------------------------------------

@Composable
fun EndedScreen(reason: String?, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp).background(cs.errorContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CallEnd, contentDescription = null, tint = cs.onErrorContainer, modifier = Modifier.size(40.dp))
        }
        Text("Chat ended", style = MaterialTheme.typography.headlineSmall)
        Text(
            reason ?: "The connection closed.",
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryButton("Back to home", onClick = onBack)
    }
}
