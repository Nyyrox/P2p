package com.peertalk.app.rtc

enum class Phase {
    /** Nothing in progress. */
    IDLE,
    /** Generating a code / applying a code. */
    WORKING,
    /** Host: invite code is ready, waiting for the guest's reply code. */
    HOST_CODE_READY,
    /** Guest: reply code is ready, waiting for the host to apply it. */
    GUEST_CODE_READY,
    /** Host applied the reply code, ICE is negotiating. */
    CONNECTING,
    CONNECTED,
    /** Peer left or the link failed. */
    ENDED,
}

data class ChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val time: Long = System.currentTimeMillis(),
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    /** The code the local user has to hand to the other person. */
    val code: String = "",
    val error: String? = null,
    val endedReason: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val micMuted: Boolean = false,
    val speakerOn: Boolean = true,
    val sharingScreen: Boolean = false,
    val remoteSharing: Boolean = false,
    val linkUnstable: Boolean = false,
)
