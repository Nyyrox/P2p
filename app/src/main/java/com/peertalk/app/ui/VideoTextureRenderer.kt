package com.peertalk.app.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * WebRTC video renderer built on a TextureView.
 *
 * WebRTC's own SurfaceViewRenderer is a SurfaceView, which punches a hole through the window.
 * Inside Compose that hole is lost (black picture) whenever an ancestor has a clip, a fade or any
 * other graphics layer: our call screen has all three (screen cross-fade, rounded preview,
 * auto-hiding controls). A TextureView is an ordinary view in the layer tree, so it composes
 * correctly with all of them.
 */
class VideoTextureRenderer(context: Context) :
    TextureView(context), VideoSink, TextureView.SurfaceTextureListener {

    private val eglRenderer = EglRenderer("PeerTalkRenderer")
    private val measureHelper = RendererCommon.VideoLayoutMeasure()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var rotatedWidth = 0
    @Volatile private var rotatedHeight = 0
    private var initialized = false
    private var released = false
    private var firstFrameDelivered = false
    private var scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT

    var onFirstFrame: (() -> Unit)? = null
    var onSurfaceReady: (() -> Unit)? = null

    fun init(sharedContext: EglBase.Context) {
        if (initialized) return
        initialized = true
        measureHelper.setScalingType(scalingType)
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
    }

    fun setMirror(mirror: Boolean) {
        if (initialized && !released) eglRenderer.setMirror(mirror)
    }

    fun setScaling(type: RendererCommon.ScalingType) {
        if (type == scalingType) return
        scalingType = type
        measureHelper.setScalingType(type)
        requestLayout()
    }

    fun release() {
        if (!initialized || released) return
        released = true
        eglRenderer.release()
    }

    // ---- VideoSink: called on WebRTC / camera threads ---------------------------------------
    override fun onFrame(frame: VideoFrame) {
        if (!initialized || released) return
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != rotatedWidth || h != rotatedHeight) {
            rotatedWidth = w
            rotatedHeight = h
            main.post { requestLayout() }
        }
        eglRenderer.onFrame(frame)
        if (!firstFrameDelivered) {
            firstFrameDelivered = true
            main.post { onFirstFrame?.invoke() }
        }
    }

    // ---- layout ------------------------------------------------------------------------------
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = measureHelper.measure(widthMeasureSpec, heightMeasureSpec, rotatedWidth, rotatedHeight)
        setMeasuredDimension(size.x, size.y)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (initialized && !released && w > 0 && h > 0) {
            eglRenderer.setLayoutAspectRatio(w.toFloat() / h.toFloat())
        }
    }

    // ---- SurfaceTextureListener ----------------------------------------------------------------
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        eglRenderer.createEglSurface(surface)
        onSurfaceReady?.invoke()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (!released) {
            val latch = CountDownLatch(1)
            eglRenderer.releaseEglSurface { latch.countDown() }
            latch.await()
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
