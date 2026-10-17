package com.peertalk.app.rtc

import android.util.Base64
import org.webrtc.SessionDescription
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Turns a full (non-trickle) SDP into a copy/paste friendly string and back.
 *
 * Format: 1 tag char ('O' = offer / invite, 'A' = answer / reply) followed by
 * URL-safe Base64 of the GZIP-compressed SDP.
 */
object CodeCodec {

    fun encode(desc: SessionDescription): String {
        val tag = if (desc.type == SessionDescription.Type.OFFER) 'O' else 'A'
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(desc.description.toByteArray(Charsets.UTF_8)) }
        val b64 = Base64.encodeToString(
            bos.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return tag + b64
    }

    /** @throws IllegalArgumentException when the text is not a valid PeerTalk code. */
    fun decode(raw: String): SessionDescription {
        val clean = raw.filterNot { it.isWhitespace() }
        require(clean.length > 16) { "That doesn't look like a PeerTalk code." }
        val type = when (clean[0]) {
            'O' -> SessionDescription.Type.OFFER
            'A' -> SessionDescription.Type.ANSWER
            else -> throw IllegalArgumentException("That doesn't look like a PeerTalk code.")
        }
        val sdp = try {
            val bytes = Base64.decode(
                clean.substring(1),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            throw IllegalArgumentException("The code is damaged or incomplete. Copy it again in full.")
        }
        require(sdp.startsWith("v=0")) { "That doesn't look like a PeerTalk code." }
        return SessionDescription(type, sdp)
    }
}
