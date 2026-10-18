package com.peertalk.app.rtc

import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Sits between a WebRTC video track and the on-screen renderers.
 *
 * With [cache] on, the last frame is kept and replayed to any renderer that attaches later.
 * That matters for screen sharing: a still screen produces no new frames, so a renderer that
 * is created after the last frame (screen rotated, app resumed, view recreated) would stay
 * black until something on the sharer's screen changed.
 *
 * Only used with software-decoded (I420) frames, so retaining one is cheap and safe. Local
 * camera frames are texture-backed, so the local relay never caches.
 */
class VideoRelay(private val cache: Boolean) : VideoSink {
    private val sinks = CopyOnWriteArraySet<VideoSink>()
    private val lock = Any()
    private var last: VideoFrame? = null

    override fun onFrame(frame: VideoFrame) {
        if (cache) synchronized(lock) {
            frame.retain()
            last?.release()
            last = frame
        }
        for (s in sinks) s.onFrame(frame)
    }

    fun addSink(sink: VideoSink) {
        sinks.add(sink)
        if (cache) synchronized(lock) { last?.let { sink.onFrame(it) } }
    }

    fun removeSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    fun clear() {
        synchronized(lock) {
            last?.release()
            last = null
        }
    }
}
