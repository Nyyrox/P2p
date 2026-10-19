package com.peertalk.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peertalk.app.rtc.CallPhase
import com.peertalk.app.rtc.PeerEngine
import com.peertalk.app.rtc.Phase

private enum class Mode { HOME, HOST, JOIN }
private enum class Screen { HOME, HOST, JOIN, CHAT, CALL, ENDED }

/**
 * Flow:  Home -> (Create | Join: name + codes) -> CHAT -> (call accepted) -> CALL -> back to CHAT.
 * Joining always lands in the chat. The call screen only appears after the other person accepts,
 * and it is the only place the microphone permission is requested.
 */
@Composable
fun PeerTalkApp(engine: PeerEngine, inPip: Boolean = false) {
    val state by engine.state.collectAsStateWithLifecycle()

    var mode by rememberSaveable { mutableStateOf(Mode.HOME) }
    var useStun by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf(state.myName) }
    // While true the call keeps running and the chat shows a "tap to return" bar.
    var callMinimized by rememberSaveable { mutableStateOf(false) }

    val screen = when {
        state.phase == Phase.ENDED -> Screen.ENDED
        state.phase == Phase.CONNECTED && state.callPhase == CallPhase.ACTIVE && (inPip || !callMinimized) -> Screen.CALL
        state.phase == Phase.CONNECTED -> Screen.CHAT
        mode == Mode.HOST -> Screen.HOST
        mode == Mode.JOIN -> Screen.JOIN
        else -> Screen.HOME
    }

    LaunchedEffect(state.callPhase) { if (state.callPhase != CallPhase.ACTIVE) callMinimized = false }
    LaunchedEffect(inPip) { if (inPip) callMinimized = false }

    val backToHome = { engine.reset(); mode = Mode.HOME }
    BackHandler(enabled = screen == Screen.HOST || screen == Screen.JOIN) { backToHome() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { s ->
            when (s) {
                Screen.HOME -> HomeScreen(
                    useStun = useStun,
                    onUseStun = { useStun = it },
                    error = state.error,
                    onCreate = { engine.clearError(); mode = Mode.HOST },
                    onJoin = { engine.clearError(); mode = Mode.JOIN },
                )
                Screen.HOST -> HostScreen(
                    state = state,
                    name = name,
                    onName = { name = it },
                    onCreate = { engine.startHost(name, useStun) },
                    onReply = { engine.acceptReply(it) },
                    onCancel = backToHome,
                )
                Screen.JOIN -> JoinScreen(
                    state = state,
                    name = name,
                    onName = { name = it },
                    onCreateReply = { code -> engine.join(code, name, useStun) },
                    onCancel = backToHome,
                )
                Screen.CHAT -> ChatScreen(
                    engine = engine,
                    state = state,
                    onLeave = { engine.hangUp(); mode = Mode.HOME },
                    onReturnToCall = { callMinimized = false },
                )
                Screen.CALL -> CallScreen(engine = engine, state = state, pip = inPip, onMinimize = { callMinimized = true })
                Screen.ENDED -> EndedScreen(state.endedReason, onBack = backToHome)
            }
        }
    }
}
