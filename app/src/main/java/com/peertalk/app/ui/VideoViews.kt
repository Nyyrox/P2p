package com.peertalk.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.peertalk.app.rtc.VideoRelay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Draws a [VideoRelay] on a SurfaceViewRenderer.
 *
 *  - [fit] = true letterboxes (whole picture visible, right for screen shares);
 *    false fills and crops (right for faces).
 *  - [overlay] puts the surface above other surfaces (small picture-in-picture previews).
 *  - [onFirstFrame] fires once the first picture is on screen, so callers can hide a spinner.
 *
 * The relay replays its last frame on attach, so a re-created view is not black while the
 * remote screen is still.
 */
@Composable
fun VideoView(
    relay: VideoRelay?,
    eglBase: EglBase,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    mirror: Boolean = false,
    overlay: Boolean = false,
    onFirstFrame: () -> Unit = {},
) {
    val firstFrame by rememberUpdatedState(onFirstFrame)
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // For "fit", the renderer must be allowed to shrink to the picture's aspect ratio (wrap content);
    // for "fill" it takes all the space and crops.
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (fit) Modifier.wrapContentSize() else Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(
                        eglBase.eglBaseContext,
                        object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() { post { firstFrame() } }
                            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
                        },
                    )
                    setEnableHardwareScaler(true)
                    setZOrderMediaOverlay(overlay)
                    view = this
                }
            },
            update = { v ->
                v.setScalingType(
                    if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                )
                v.setMirror(mirror)
            },
        )
    }

    DisposableEffect(view, relay) {
        val v = view
        if (v != null) relay?.addSink(v)
        onDispose { if (v != null) relay?.removeSink(v) }
    }
    DisposableEffect(view) {
        val v = view
        onDispose { v?.release() }
    }
}
