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

/**
 * Draws a [VideoRelay] on a [VideoTextureRenderer].
 *
 *  - [fit] = true letterboxes (whole picture visible, right for screen shares);
 *    false fills and crops (right for faces).
 *  - [onFirstFrame] fires once the first picture is handed to the renderer, so callers can hide a spinner.
 *
 * The relay replays its last frame when a renderer attaches and again when its surface becomes
 * ready, so a re-created view is not black while the remote screen is still.
 */
@Composable
fun VideoView(
    relay: VideoRelay?,
    eglBase: EglBase,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    mirror: Boolean = false,
    onFirstFrame: () -> Unit = {},
) {
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val currentRelay by rememberUpdatedState(relay)
    var view by remember { mutableStateOf<VideoTextureRenderer?>(null) }

    // For "fit" the renderer must be allowed to shrink to the picture's aspect ratio (wrap content);
    // for "fill" it takes all the space and crops.
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (fit) Modifier.wrapContentSize() else Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoTextureRenderer(ctx).apply {
                    init(eglBase.eglBaseContext)
                    // `this.` matters: the VideoView parameter of the same name would otherwise win.
                    this.onFirstFrame = { firstFrame() }
                    this.onSurfaceReady = { currentRelay?.replay(this) }
                    view = this
                }
            },
            update = { v ->
                v.setScaling(
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
