package com.peertalk.app.rtc

const val MAX_NAME_LENGTH = 24

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
    /** Peers are linked. The chat screen is shown; a call is a separate, optional step. */
    CONNECTED,
    /** Peer left or the link failed. */
    ENDED,
}

/** Voice call state, layered on top of an established chat session. */
enum class CallPhase {
    NONE,
    /** We asked, waiting for the other person to accept or decline. */
    OUTGOING,
    /** The other person is calling us. */
    INCOMING,
    /** Both agreed. Call screen is shown, mic is requested there. */
    ACTIVE,
}

data class ChatMessage(
    val id: String,
    val text: String,
    val mine: Boolean,
    val time: Long = System.currentTimeMillis(),
    /** A centered event line ("Call ended", "Alex is now Sam") instead of a bubble. */
    val system: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    /** The code the local user has to hand to the other person. */
    val code: String = "",
    val error: String? = null,
    val endedReason: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val myName: String = "",
    val peerName: String = "",
    val callPhase: CallPhase = CallPhase.NONE,
    val callStartedAt: Long = 0L,
    /** True once the microphone track exists (permission was granted on the call screen). */
    val micActive: Boolean = false,
    val micMuted: Boolean = false,
    val speakerOn: Boolean = true,
    val sharingScreen: Boolean = false,
    val remoteSharing: Boolean = false,
    val linkUnstable: Boolean = false,
)
