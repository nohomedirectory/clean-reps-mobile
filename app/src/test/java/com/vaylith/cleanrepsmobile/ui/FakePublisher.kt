package com.vaylith.cleanrepsmobile.ui

import android.graphics.Bitmap
import android.view.SurfaceView
import com.vaylith.cleanrepsmobile.media.CanonicalSourcePublisher
import com.vaylith.cleanrepsmobile.media.CaptureGeometry
import com.vaylith.cleanrepsmobile.media.PublisherListener
import com.vaylith.cleanrepsmobile.media.PublisherResult
import com.vaylith.cleanrepsmobile.media.PublisherStatus
import com.vaylith.cleanrepsmobile.model.SourceEpoch

/**
 * The publisher interface with no RootEncoder behind it, for rendering CameraScreen on the JVM:
 * nothing here opens a camera, builds GL or encodes. [start] reports CONNECTING like
 * MediaMtxSrtPublisher, [live] stands in for the transport reaching the server, and [stop]
 * reports STOPPED. Every call is recorded in [calls], and [attachedViews] holds the SurfaceViews
 * CameraScreen handed over, so a test can see that the screen used this publisher.
 */
internal class FakePublisher(
    private val listener: PublisherListener,
    override val preparedGeometry: CaptureGeometry,
    private val sourceId: String,
) : CanonicalSourcePublisher {
    val calls = mutableListOf<String>()
    val attachedViews = mutableListOf<SurfaceView>()

    override suspend fun start(epoch: SourceEpoch): PublisherResult {
        calls += "start:${epoch.value}"
        listener.onPublisherStatus(PublisherStatus.CONNECTING, "Connecting one SRT source for epoch ${epoch.displayId}...")
        return PublisherResult.Connecting(sourceId)
    }

    override suspend fun stop() {
        calls += "stop"
        listener.onPublisherStatus(PublisherStatus.STOPPED, "Capture stopped.")
    }

    fun live() = listener.onPublisherStatus(PublisherStatus.LIVE, "Canonical SRT source is live: $sourceId")

    override val isAvailable = true
    override val sensorOrientationDeg: Int = preparedGeometry.sensorOrientationDeg

    override fun attachPreview(view: SurfaceView) {
        calls += "attachPreview"
        attachedViews += view
    }

    override fun releasePreview() {
        calls += "releasePreview"
    }

    override fun reopenCamera() {
        calls += "reopenCamera"
    }

    override fun frameCheck(callback: (Bitmap?, Int, Int) -> Unit) = callback(null, 0, 0)

    override fun release() {
        calls += "release"
    }
}
